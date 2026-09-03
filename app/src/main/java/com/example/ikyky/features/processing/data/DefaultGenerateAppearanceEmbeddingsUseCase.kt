package com.example.ikyky.features.processing.data

import android.graphics.Bitmap
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.VideoFrameExtractor
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.ml.embedding.FaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.preprocessing.FacePreprocessor
import com.example.ikyky.core.ml.preprocessing.RecognitionCrop
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.VideoFrame
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.model.EmbeddingDiagnostics
import com.example.ikyky.features.processing.domain.usecase.AppearanceEmbeddingAggregator
import com.example.ikyky.features.processing.domain.usecase.EmbeddingSampleSelector
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Real Phase-3 embedding orchestrator.
 *
 * Memory: one re-decoded [VideoFrame] and its derived crops are alive at a time;
 * everything is recycled before the next observation. Only 192-float vectors +
 * lightweight metadata accumulate. Runs on [DispatcherProvider.default];
 * frame decode uses the extractor's own `io`; inference is serialized inside
 * [FaceEmbedder].
 */
class DefaultGenerateAppearanceEmbeddingsUseCase(
    private val frameExtractor: VideoFrameExtractor,
    private val preprocessor: FacePreprocessor,
    private val embedder: FaceEmbedder,
    private val modelLoader: EmbeddingModelLoader,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val selector: EmbeddingSampleSelector = EmbeddingSampleSelector(),
    private val aggregator: AppearanceEmbeddingAggregator = AppearanceEmbeddingAggregator(),
    private val outputSize: Int = PipelineDefaults.EMBEDDING_INPUT_SIZE,
) : GenerateAppearanceEmbeddingsUseCase {

    override suspend fun invoke(
        uriString: String,
        metadata: VideoMetadata,
        appearances: List<AppearanceCandidate>,
        onProgress: (Float, String) -> Unit,
    ): AppResult<GenerateAppearanceEmbeddingsUseCase.Result> = withContext(dispatchers.default) {
        val stageStart = System.currentTimeMillis()

        // Load the interpreter once, up front, and measure it.
        val loadStart = System.currentTimeMillis()
        when (val r = modelLoader.getOrLoad()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return@withContext AppResult.Failure(r.error)
        }
        val modelLoadMs = System.currentTimeMillis() - loadStart

        var diag = EmbeddingDiagnostics(modelLoadMs = modelLoadMs)
        val perObs = ArrayList<EmbeddedFaceObservation>()
        val appearanceEmbeddings = ArrayList<AppearanceEmbedding>()

        appearances.forEachIndexed { i, appearance ->
            currentCoroutineContext().ensureActive()
            val picks: List<AppearanceObservationRef> = selector.select(appearance.observations)
            diag = diag.copy(
                appearancesProcessed = diag.appearancesProcessed + 1,
                observationsSelected = diag.observationsSelected + picks.size,
            )

            val members = ArrayList<EmbeddedFaceObservation>(picks.size)
            for (ref in picks) {
                currentCoroutineContext().ensureActive()
                val (embedded, d) = embedOne(uriString, metadata, appearance, ref, diag)
                diag = d
                if (embedded != null) members += embedded
            }

            if (members.isEmpty()) {
                diag = diag.copy(appearancesWithoutEmbedding = diag.appearancesWithoutEmbedding + 1)
                logger.w(TAG, "appearance ${appearance.id}: no usable embeddings from ${picks.size} picks")
            } else {
                perObs += members
                val agg = aggregator.aggregate(appearance.id, appearance.trackletId, members)
                if (agg != null) {
                    appearanceEmbeddings += agg
                    diag = diag.copy(
                        appearancesEmbedded = diag.appearancesEmbedded + 1,
                        outliersRejected = diag.outliersRejected + agg.rejectedOutliers,
                    )
                }
            }
            onProgress(
                (i + 1).toFloat() / appearances.size.coerceAtLeast(1),
                "appearance ${i + 1}/${appearances.size} · ${members.size} embeddings",
            )
        }

        diag = diag.copy(totalStageMs = System.currentTimeMillis() - stageStart)
        logger.i(TAG, "Phase 3 embeddings: $diag")
        AppResult.Success(
            GenerateAppearanceEmbeddingsUseCase.Result(
                appearanceEmbeddings = appearanceEmbeddings,
                perObservation = perObs,
                diagnostics = diag,
            )
        )
    }

    /** Re-decode → crop → align → preprocess → embed a single observation. */
    private suspend fun embedOne(
        uriString: String,
        metadata: VideoMetadata,
        appearance: AppearanceCandidate,
        ref: AppearanceObservationRef,
        diagIn: EmbeddingDiagnostics,
    ): Pair<EmbeddedFaceObservation?, EmbeddingDiagnostics> {
        var diag = diagIn

        // --- guard: recognition crop must be sane in canonical space ---
        val crop = RecognitionCrop.expandAndClamp(
            face = ref.canonicalBox,
            frameWidth = metadata.displayWidth,
            frameHeight = metadata.displayHeight,
        )
        if (!RecognitionCrop.isUsable(crop)) {
            logger.w(TAG, "skip ${ref.observationId}: recognition crop unusable $crop")
            return null to diag
        }

        // --- re-decode the source frame ---
        val t0 = System.currentTimeMillis()
        val frame: VideoFrame? = frameExtractor.decodeFrameAt(uriString, metadata, ref.timestampMs)
        val redecodeMs = System.currentTimeMillis() - t0
        if (frame == null) {
            logger.w(TAG, "skip ${ref.observationId}: could not re-decode @${ref.timestampMs}ms")
            return null to diag.copy(totalRedecodeMs = diag.totalRedecodeMs + redecodeMs)
        }
        diag = diag.copy(
            framesRedecoded = diag.framesRedecoded + 1,
            totalRedecodeMs = diag.totalRedecodeMs + redecodeMs,
        )

        try {
            // The re-decoded frame is downscaled the same way Phase 2 frames
            // were, but the box/landmarks on `ref` are CANONICAL. Map them into
            // this bitmap's pixel space via the frame geometry's scale.
            val s = frame.geometry.scale
            val faceInDecoded = DetectedFace(
                boundingBox = com.example.ikyky.core.model.BoundingBox(
                    left = (ref.canonicalBox.left * s).toInt(),
                    top = (ref.canonicalBox.top * s).toInt(),
                    right = (ref.canonicalBox.right * s).toInt(),
                    bottom = (ref.canonicalBox.bottom * s).toInt(),
                ),
                landmarks = ref.landmarks.map { it.copy(x = it.x * s, y = it.y * s) },
            )

            val preStart = System.currentTimeMillis()
            val pre = when (
                val r = preprocessor.preprocess(
                    frame = frame.bitmap,
                    face = faceInDecoded,
                    outputSize = outputSize,
                    includePresentationCrop = false,
                )
            ) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> {
                    logger.w(TAG, "skip ${ref.observationId}: preprocess ${r.error.message}")
                    return null to diag
                }
            }
            // preprocess = align + resize; count both under align/preprocess buckets
            val preElapsed = System.currentTimeMillis() - preStart
            diag = diag.copy(
                totalAlignMs = diag.totalAlignMs + preElapsed,
                totalPreprocessMs = diag.totalPreprocessMs + preElapsed,
                alignedByLandmarks = diag.alignedByLandmarks + if (pre.alignedByLandmarks) 1 else 0,
                alignedByBoxFallback = diag.alignedByBoxFallback + if (pre.alignedByLandmarks) 0 else 1,
            )

            val infStart = System.currentTimeMillis()
            val embedding = when (val r = embedder.embed(pre)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> {
                    logger.w(TAG, "embed failed ${ref.observationId}: ${r.error.message}")
                    pre.bitmap.recycleQuietly()
                    return null to diag.copy(
                        embeddingCalls = diag.embeddingCalls + 1,
                        embeddingFailures = diag.embeddingFailures + 1,
                        totalInferenceMs = diag.totalInferenceMs + (System.currentTimeMillis() - infStart),
                    )
                }
            }
            val infMs = System.currentTimeMillis() - infStart
            pre.bitmap.recycleQuietly()

            diag = diag.copy(
                embeddingCalls = diag.embeddingCalls + 1,
                totalInferenceMs = diag.totalInferenceMs + infMs,
            )

            return EmbeddedFaceObservation(
                appearanceId = appearance.id,
                trackletId = appearance.trackletId,
                frameIndex = ref.frameIndex,
                timestampMs = ref.timestampMs,
                observationId = ref.observationId,
                faceBox = ref.canonicalBox,
                qualityScore = ref.qualityScore,
                embedding = embedding,
                alignedByLandmarks = pre.alignedByLandmarks,
            ) to diag
        } finally {
            frame.bitmap.recycleQuietly()
        }
    }

    // -----------------------------------------------------------------------
    // Phase 4.5 — dense refinement path (one appearance, more samples)
    // -----------------------------------------------------------------------

    override suspend fun denseEmbed(
        uriString: String,
        metadata: VideoMetadata,
        appearance: AppearanceCandidate,
        targetSampleCount: Int,
    ): AppResult<List<EmbeddedFaceObservation>> = withContext(dispatchers.default) {
        when (val r = modelLoader.getOrLoad()) {
            is AppResult.Success -> Unit
            is AppResult.Failure -> return@withContext AppResult.Failure(r.error)
        }
        val picks = denseTemporalSample(appearance.observations, targetSampleCount)
        var diag = EmbeddingDiagnostics()
        val out = ArrayList<EmbeddedFaceObservation>(picks.size)
        for (ref in picks) {
            currentCoroutineContext().ensureActive()
            val (e, d) = embedOne(uriString, metadata, appearance, ref, diag)
            diag = d
            if (e != null) out += e
        }
        logger.i(
            TAG,
            "dense embed ${appearance.id}: ${picks.size} picks → ${out.size} embeddings " +
                "(${diag.totalInferenceMs}ms inference, ${diag.totalRedecodeMs}ms redecode)",
        )
        AppResult.Success(out)
    }

    /**
     * Pick ~[target] observations spread evenly across the appearance's time
     * span (one per equal time bucket, highest quality wins the bucket,
     * empty buckets back-filled by nearest-in-time). Low-quality observations
     * are eligible — the change-point analyzer down-weights them itself; we do
     * not want temporal *holes* around blurred stretches.
     */
    private fun denseTemporalSample(
        observations: List<AppearanceObservationRef>,
        target: Int,
    ): List<AppearanceObservationRef> {
        val obs = observations.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
        if (obs.size <= target) return obs
        val start = obs.first().timestampMs
        val end = obs.last().timestampMs
        val span = (end - start).coerceAtLeast(1)
        val buckets = arrayOfNulls<AppearanceObservationRef>(target)
        for (o in obs) {
            var b = (((o.timestampMs - start).toDouble() / span) * target).toInt()
            if (b >= target) b = target - 1
            val cur = buckets[b]
            if (cur == null || o.qualityScore > cur.qualityScore) buckets[b] = o
        }
        val picked = LinkedHashSet<AppearanceObservationRef>()
        for (bi in buckets.indices) {
            val hit = buckets[bi]
            if (hit != null) { picked += hit; continue }
            // back-fill: nearest unused observation by time to this bucket's centre
            val centre = start + ((bi + 0.5) / target * span).toLong()
            val nearest = obs.filter { it !in picked }.minByOrNull { kotlin.math.abs(it.timestampMs - centre) }
            if (nearest != null) picked += nearest
        }
        return picked.sortedBy { it.timestampMs }
    }

    private fun Bitmap.recycleQuietly() {
        if (!isRecycled) runCatching { recycle() }
    }

    private companion object {
        const val TAG = "GenerateEmbeddings"
    }
}
