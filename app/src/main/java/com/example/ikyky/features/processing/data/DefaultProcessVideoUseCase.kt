package com.example.ikyky.features.processing.data

import android.graphics.Bitmap
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.FrameSamplingRequest
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.VideoFrameExtractor
import com.example.ikyky.core.media.VideoMetadataReader
import com.example.ikyky.core.ml.detector.FaceDetector
import com.example.ikyky.core.ml.quality.FaceQuality
import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.ml.tracking.FaceTracker
import com.example.ikyky.core.ml.tracking.Tracklet
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.VideoFrame
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.ProcessingDiagnostics
import com.example.ikyky.features.processing.domain.model.ProcessingOutcome
import com.example.ikyky.features.processing.domain.model.ProcessingProgress
import com.example.ikyky.features.processing.domain.model.ProcessingStage
import com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository
import com.example.ikyky.features.processing.domain.usecase.AppearanceSegmenter
import com.example.ikyky.features.processing.domain.usecase.ProcessVideoUseCase
import com.example.ikyky.features.processing.domain.usecase.ProgressModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Real Phase-2 pipeline orchestrator.
 *
 *   URI → metadata → planned timestamps → [VideoFrameExtractor] (cold Flow)
 *       → per frame: [FaceDetector] → canonical [FaceObservation]s + quality
 *       → [FaceTracker] → [Tracklet]s → [AppearanceSegmenter]
 *       → [AppearanceCandidate]s → repository
 *
 * Memory: exactly one [VideoFrame] bitmap is alive at a time — it is recycled
 * the instant detection + quality extraction finish. No bitmap is retained past
 * its frame. Only the lightweight [FaceObservation] list accumulates (a few
 * numbers + a box per detected face), then the tracker runs once over it.
 *
 * Threading: the whole body runs on [DispatcherProvider.default]; the extractor
 * decodes on [DispatcherProvider.io] via its own `flowOn`; ML Kit detection uses
 * its own worker and suspends.
 *
 * Cancellation: `ensureActive()` every frame; `CancellationException` is never
 * caught. The extractor releases its retriever in a `finally`. The detector is
 * owned by the container and NOT closed here.
 *
 * Stateless & reusable — no mutable pipeline state on the instance.
 */
class DefaultProcessVideoUseCase(
    private val metadataReader: VideoMetadataReader,
    private val frameExtractor: VideoFrameExtractor,
    private val faceDetector: FaceDetector,
    private val faceTracker: FaceTracker,
    private val resultRepository: ProcessingResultRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val segmenter: AppearanceSegmenter = AppearanceSegmenter(),
    private val fps: Float = PipelineDefaults.FRAME_SAMPLE_FPS,
) : ProcessVideoUseCase {

    override suspend fun invoke(
        sessionId: String,
        uriString: String,
        onProgress: (ProcessingProgress) -> Unit,
    ): AppResult<ProcessingOutcome> = withContext(dispatchers.default) {
        val startedAt = System.currentTimeMillis()
        val obsIdSeq = AtomicLong(0)

        // --- 1. metadata -----------------------------------------------------
        onProgress(ProcessingProgress(ProcessingStage.LOADING_VIDEO, 0f))
        val metadata = when (val r = metadataReader.read(uriString)) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return@withContext AppResult.Failure(r.error)
        }

        val request = FrameSamplingRequest(uriString = uriString, metadata = metadata, fps = fps)
        val framesPlanned = frameExtractor.plannedFrameCount(request)
        if (framesPlanned <= 0) {
            return@withContext AppResult.Failure(
                AppError.FrameExtraction(
                    "Nothing to sample (duration ${metadata.durationMs} ms, fps $fps)"
                )
            )
        }

        var diag = ProcessingDiagnostics(framesPlanned = framesPlanned)
        val perFrameMs = ArrayList<Long>(framesPlanned)
        val allObservations = ArrayList<FaceObservation>()

        // --- 2. frame loop: extract + detect -------------------------------
        try {
            frameExtractor.extractFrames(request).collect { frame ->
                currentCoroutineContext().ensureActive()
                val frameStart = System.currentTimeMillis()

                val observations = detectAndScore(frame, obsIdSeq)
                frame.bitmap.recycleQuietly()

                allObservations += observations
                perFrameMs += System.currentTimeMillis() - frameStart

                val n = frame.index + 1
                diag = diag.copy(
                    framesSampled = n,
                    framesDecodedOk = diag.framesDecodedOk + 1,
                    totalFaceObservations = diag.totalFaceObservations + observations.size,
                    framesWithFaces = diag.framesWithFaces + if (observations.isNotEmpty()) 1 else 0,
                    multiFaceFrames = diag.multiFaceFrames + if (observations.size >= 2) 1 else 0,
                    maxFacesInAnyFrame = maxOf(diag.maxFacesInAnyFrame, observations.size),
                    observationsLowQuality = diag.observationsLowQuality +
                        observations.count { !it.usable },
                )

                val faceWord = if (observations.size == 1) "face" else "faces"
                onProgress(
                    ProcessingProgress(
                        stage = if (n == 1) ProcessingStage.EXTRACTING_FRAMES
                        else ProcessingStage.DETECTING_FACES,
                        fraction = ProgressModel.frameLoopFraction(n, framesPlanned),
                        detail = "frame $n / $framesPlanned · ${observations.size} $faceWord",
                        diagnostics = diag,
                    )
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: MediaMetadataFrameExtractor.FrameExtractionException) {
            return@withContext AppResult.Failure(e.appError)
        } catch (t: Throwable) {
            return@withContext AppResult.Failure(
                AppError.FrameExtraction("Frame processing failed", t)
            )
        }

        // --- 3. tracking ---------------------------------------------------
        currentCoroutineContext().ensureActive()
        onProgress(
            ProcessingProgress(
                ProcessingStage.TRACKING_APPEARANCES,
                ProgressModel.stageFraction(ProcessingStage.TRACKING_APPEARANCES),
                diagnostics = diag,
            )
        )
        val tracklets: List<Tracklet> = faceTracker.buildTracklets(allObservations)

        // --- 4. appearance segmentation ----------------------------------
        onProgress(
            ProcessingProgress(
                ProcessingStage.FINALIZING_APPEARANCES,
                ProgressModel.stageFraction(ProcessingStage.FINALIZING_APPEARANCES),
                diagnostics = diag,
            )
        )
        val candidates: List<AppearanceCandidate> = segmenter.segment(tracklets)

        val totalMs = System.currentTimeMillis() - startedAt
        diag = diag.copy(
            rawTracklets = tracklets.size,
            trackletsFilteredOut = tracklets.size - candidates.size,
            trackletsAfterFilter = candidates.size,
            appearancesDetected = candidates.size,
            totalProcessingMs = totalMs,
            avgFrameProcessingMs = if (perFrameMs.isEmpty()) 0.0 else perFrameMs.average(),
        )

        resultRepository.setResult(sessionId, candidates, diag, sourceUri = uriString)
        logger.i(TAG, "Phase 2 complete: $diag")

        if (diag.totalFaceObservations == 0) {
            return@withContext AppResult.Failure(AppError.NoFacesDetected())
        }

        onProgress(ProcessingProgress(ProcessingStage.COMPLETED, 1f, diagnostics = diag))
        AppResult.Success(
            ProcessingOutcome(
                sessionId = sessionId,
                appearanceCandidateCount = candidates.size,
                diagnostics = diag,
            )
        )
    }

    private suspend fun detectAndScore(
        frame: VideoFrame,
        obsIdSeq: AtomicLong,
    ): List<FaceObservation> {
        val faces: List<DetectedFace> =
            when (val r = faceDetector.detect(frame.bitmap, rotationDegrees = 0)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> {
                    logger.w(TAG, "detect failed @${frame.timestampMs}ms: ${r.error.message}")
                    emptyList()
                }
            }
        if (faces.isEmpty()) return emptyList()

        val out = ArrayList<FaceObservation>(faces.size)
        for (f in faces) {
            val canonicalBox = frame.geometry.toCanonical(f.boundingBox)
            val w = canonicalBox.width
            val h = canonicalBox.height
            val longEdge = maxOf(w, h)
            val shortEdge = minOf(w, h)
            // reject degenerate / non-face-shaped boxes (Phase 2.5: ML Kit emits
            // e.g. 530x0, 901x8 on hard frames)
            if (longEdge < PipelineDefaults.MIN_FACE_SIZE_PX) continue
            if (shortEdge < PipelineDefaults.MIN_FACE_EDGE_PX) continue
            if (shortEdge > 0 && longEdge.toFloat() / shortEdge > PipelineDefaults.MAX_FACE_ASPECT_RATIO) continue

            val canonicalFace = f.copy(
                boundingBox = canonicalBox,
                landmarks = f.landmarks.map { frame.geometry.toCanonical(it) },
            )
            val patch = cropForBlur(frame.bitmap, f)
            val q = FaceQuality.evaluate(
                face = canonicalFace,
                frameWidth = frame.geometry.uprightWidth,
                frameHeight = frame.geometry.uprightHeight,
                facePatch = patch,
            )
            patch?.recycleQuietly()

            out += FaceObservation(
                id = "obs_${obsIdSeq.incrementAndGet()}",
                frameIndex = frame.index,
                timestampMs = frame.timestampMs,
                face = canonicalFace,
                qualityScore = q.score,
                usable = q.isUsable,
            )
        }
        return out
    }

    /** Small centre crop of the face in *decoded* space, used for the blur metric only. */
    private fun cropForBlur(bitmap: Bitmap, face: DetectedFace): Bitmap? {
        val b = face.boundingBox
        val left = b.left.coerceIn(0, bitmap.width - 1)
        val top = b.top.coerceIn(0, bitmap.height - 1)
        val right = b.right.coerceIn(left + 1, bitmap.width)
        val bottom = b.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return null
        return try {
            val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
            val maxEdge = 64
            if (maxOf(w, h) <= maxEdge) {
                crop
            } else {
                val s = maxEdge.toFloat() / maxOf(w, h)
                val scaled = Bitmap.createScaledBitmap(
                    crop,
                    (w * s).toInt().coerceAtLeast(1),
                    (h * s).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== crop) crop.recycle()
                scaled
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun Bitmap.recycleQuietly() {
        if (!isRecycled) runCatching { recycle() }
    }

    private companion object {
        const val TAG = "ProcessVideo"
    }
}
