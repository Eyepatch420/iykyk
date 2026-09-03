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
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.media.VideoMetadataReader
import com.example.ikyky.core.ml.detector.FaceDetector
import com.example.ikyky.core.ml.quality.FaceQuality
import com.example.ikyky.core.ml.shots.DefaultShotScanner
import com.example.ikyky.core.ml.shots.ShotScan
import com.example.ikyky.core.ml.shots.ShotScanner
import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.ml.tracking.ShotAwareFaceTracker
import com.example.ikyky.core.ml.tracking.Tracklet
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.FaceEmbedding
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
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The frozen Phase 5I pipeline orchestrator (ported in Phase 6).
 *
 * ```
 *   URI -> metadata
 *       -> PASS 1: every-frame shot scan  ......... ShotScanner  -> transitions
 *       -> PASS 2: sampled frames @ 8 FPS ......... VideoFrameExtractor
 *            per frame: FaceDetector -> canonical FaceObservations + quality
 *                       TrackerAppearanceCrop -> gate embedding (expanded crop)
 *       -> ShotAwareFaceTracker (barriers + appearance gate) -> Tracklets
 *       -> AppearanceSegmenter (>= 2 observations) -> AppearanceCandidates
 *       -> repository
 * ```
 *
 * ## Why two passes
 *
 * The shot scan must see EVERY decoded frame. A whip-pan in these clips lasts
 * ~7 frames at 25 FPS; sampling at 8 FPS steps over it entirely, so the
 * transition would be invisible to a single-pass design. The scan is cheap by
 * construction — thumbnails and integer arithmetic, no neural network — so a
 * second decode pass is the right trade for correct barriers.
 *
 * If the scan yields no transitions the tracker still runs, just without
 * barriers; a video that genuinely has no cuts needs none.
 *
 * ## Two crops, never conflated
 *
 * The gate embedding here uses
 * [com.example.ikyky.core.ml.preprocessing.TrackerAppearanceCrop] (expanded
 * box) — Option A. Recognition later uses the 5-point crop. See those types.
 *
 * Memory: exactly one [VideoFrame] bitmap is alive at a time in either pass; it
 * is recycled the instant its derived data is extracted. Only lightweight
 * observations and 192-float gate vectors accumulate.
 *
 * Threading: the body runs on [DispatcherProvider.default]; the extractor
 * decodes on [DispatcherProvider.io] via its own `flowOn`; ML Kit detection uses
 * its own worker and suspends.
 *
 * Cancellation: `ensureActive()` every frame; `CancellationException` is never
 * caught. The extractor releases its retriever in a `finally`. The detector and
 * embedder are owned by the container and NOT closed here.
 *
 * Stateless & reusable — no mutable pipeline state on the instance.
 *
 * @param gateEmbedder supplies the tracker's appearance-gate embeddings. When
 *   null the appearance gate abstains and tracking is shot-barrier + geometry
 *   only; this is the degraded path, not the frozen one.
 */
class DefaultProcessVideoUseCase(
    private val metadataReader: VideoMetadataReader,
    private val frameExtractor: VideoFrameExtractor,
    private val faceDetector: FaceDetector,
    private val resultRepository: ProcessingResultRepository,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val shotScanner: ShotScanner = DefaultShotScanner(),
    private val tracker: ShotAwareFaceTracker = ShotAwareFaceTracker(),
    private val gateEmbedder: TrackerGateEmbedder? = null,
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
        val gateEmbeddings = HashMap<String, FaceEmbedding>()

        // --- 2. PASS 1: every-frame shot / whip-pan scan ---------------------
        // Must see EVERY decoded frame: a whip-pan lasts ~7 frames at 25 FPS and
        // is invisible to the 8 FPS sampling grid. Cheap by construction —
        // thumbnails only, no neural network.
        onProgress(
            ProcessingProgress(
                ProcessingStage.EXTRACTING_FRAMES,
                0f,
                detail = "scanning for shot changes",
                diagnostics = diag,
            )
        )
        val shotScanStart = System.currentTimeMillis()
        val shotScan = scanShots(uriString, metadata)
        diag = diag.copy(
            shotScanFramesAnalysed = shotScan.frameCount,
            shotBoundaries = shotScan.boundaries.size,
            whipPanTransitions = shotScan.transitions.size,
            shotScanMs = System.currentTimeMillis() - shotScanStart,
        )
        logger.i(
            TAG,
            "shot scan: ${shotScan.frameCount} frames @ ${"%.2f".format(shotScan.fps)} fps -> " +
                "${shotScan.boundaries.size} spikes -> ${shotScan.transitions.size} transitions " +
                "in ${diag.shotScanMs}ms",
        )

        // Maps a SAMPLED frame index to the DECODED frame index the scan used,
        // so barrier queries speak the scan's coordinate system.
        val decodedFrameOf = decodedFrameMapper(shotScan.fps, fps)

        // --- 3. PASS 2: sampled frame loop — detect + gate-embed -------------
        try {
            frameExtractor.extractFrames(request).collect { frame ->
                currentCoroutineContext().ensureActive()
                val frameStart = System.currentTimeMillis()

                val observations = detectAndScore(frame, obsIdSeq)

                // Tracker gate embeddings use the EXPANDED crop (Option A), taken
                // from this frame's bitmap before it is recycled.
                if (gateEmbedder != null && observations.isNotEmpty()) {
                    val gateStart = System.currentTimeMillis()
                    val s = frame.geometry.scale
                    for (o in observations) {
                        currentCoroutineContext().ensureActive()
                        val inDecoded = o.face.copy(
                            boundingBox = com.example.ikyky.core.model.BoundingBox(
                                left = (o.face.boundingBox.left * s).toInt(),
                                top = (o.face.boundingBox.top * s).toInt(),
                                right = (o.face.boundingBox.right * s).toInt(),
                                bottom = (o.face.boundingBox.bottom * s).toInt(),
                            ),
                        )
                        val e = gateEmbedder.embed(frame.bitmap, inDecoded)
                        if (e != null) gateEmbeddings[o.id] = e
                    }
                    diag = diag.copy(
                        trackerGateEmbeddings = gateEmbeddings.size,
                        trackerGateEmbeddingMs = diag.trackerGateEmbeddingMs +
                            (System.currentTimeMillis() - gateStart),
                    )
                }

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

        // --- 4. shot-aware tracking -----------------------------------------
        currentCoroutineContext().ensureActive()
        onProgress(
            ProcessingProgress(
                ProcessingStage.TRACKING_APPEARANCES,
                ProgressModel.stageFraction(ProcessingStage.TRACKING_APPEARANCES),
                diagnostics = diag,
            )
        )
        val trackStart = System.currentTimeMillis()
        val frameDiagonal = hypot(
            metadata.displayWidth.toFloat(),
            metadata.displayHeight.toFloat(),
        )
        val tracklets: List<Tracklet> = tracker.track(
            observations = allObservations,
            frameDiagonal = frameDiagonal,
            // ABSOLUTE barrier — sampled indices are mapped into the scan's
            // decoded-frame space first.
            crossesTransition = { a, b ->
                shotScan.crossesTransition(decodedFrameOf(a), decodedFrameOf(b))
            },
            gateEmbeddings = gateEmbeddings,
        )
        diag = diag.copy(trackingMs = System.currentTimeMillis() - trackStart)

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

    /**
     * PASS 1 — decodes every frame of the video and runs the cheap shot scan.
     *
     * Decoding is driven through [VideoFrameExtractor] at the video's own frame
     * rate so the scan sees the real timeline. Failure is non-fatal: an empty
     * scan simply means no barriers, and the tracker degrades to geometry +
     * temporal gating rather than crashing the pipeline.
     */
    private suspend fun scanShots(uriString: String, metadata: VideoMetadata): ShotScan =
        try {
            val nativeFps = metadata.frameRate.takeIf { it > 0f }?.toDouble() ?: DEFAULT_SCAN_FPS
            val scanRequest = FrameSamplingRequest(
                uriString = uriString,
                metadata = metadata,
                fps = nativeFps.toFloat(),
                // The scan only ever looks at 64 px / 256 px thumbnails, so a
                // smaller decode is both sufficient and much cheaper than the
                // 1080 px detection decode.
                maxEdgePx = SHOT_SCAN_DECODE_EDGE_PX,
            )
            val planned = frameExtractor.plannedFrameCount(scanRequest)
            val session = shotScanner.newSession(planned, nativeFps)
            frameExtractor.extractFrames(scanRequest).collect { f ->
                currentCoroutineContext().ensureActive()
                try {
                    session.onFrame(f.bitmap)
                } finally {
                    f.bitmap.recycleQuietly()
                }
            }
            session.finish()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logger.w(TAG, "shot scan failed; continuing without barriers: ${t.message}")
            ShotScan.EMPTY
        }

    /**
     * Maps a SAMPLED frame index (8 FPS grid) to the DECODED frame index the
     * shot scan indexed by (native frame rate).
     *
     * Both grids start at t=0 and are uniform, so the mapping is the ratio of
     * their rates. Without it, barrier queries would compare an 8 FPS index
     * against a 25 FPS span and mis-place every transition.
     */
    private fun decodedFrameMapper(scanFps: Double, sampleFps: Float): (Int) -> Int {
        if (scanFps <= 0.0 || sampleFps <= 0f) return { it }
        val ratio = scanFps / sampleFps
        return { sampledIndex -> (sampledIndex * ratio).roundToInt() }
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

        /**
         * Fallback scan rate when the container reports no frame rate. 25 FPS
         * matches the sample clips; being slightly wrong only shifts the
         * frame-index mapping, it does not change which spans are transitions.
         */
        const val DEFAULT_SCAN_FPS = 25.0

        /**
         * Decode edge for the shot-scan pass. The scan derives everything from
         * 64 px and 256 px thumbnails, so decoding at 1080 px (as detection
         * does) would be pure waste.
         */
        const val SHOT_SCAN_DECODE_EDGE_PX = 320
    }
}
