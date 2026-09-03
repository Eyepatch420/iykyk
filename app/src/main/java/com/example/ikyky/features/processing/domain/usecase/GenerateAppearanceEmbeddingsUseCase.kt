package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.model.EmbeddingDiagnostics

/**
 * Phase-3 stage: turn Phase-2 [AppearanceCandidate]s into per-observation
 * embeddings ([EmbeddedFaceObservation]) and one aggregated
 * [AppearanceEmbedding] per appearance.
 *
 *   appearance → select observations (quality + temporal spread)
 *             → re-decode each chosen frame
 *             → recognition crop → align → 112×112 preprocess
 *             → MobileFaceNet → 192-d L2-normalized vector
 *             → robust mean → AppearanceEmbedding
 *
 * Produces **appearances + embeddings**, never `Person`s — global identity
 * clustering is Phase 4.
 */
interface GenerateAppearanceEmbeddingsUseCase {

    data class Result(
        val appearanceEmbeddings: List<AppearanceEmbedding>,
        val perObservation: List<EmbeddedFaceObservation>,
        val diagnostics: EmbeddingDiagnostics,
    )

    suspend operator fun invoke(
        uriString: String,
        metadata: VideoMetadata,
        appearances: List<AppearanceCandidate>,
        onProgress: (fraction: Float, detail: String) -> Unit = { _, _ -> },
    ): AppResult<Result>

    /**
     * **Dense refinement path** (Phase 4.5) — embed up to [targetSampleCount]
     * temporally-distributed observations of ONE appearance, for change-point
     * analysis. Does NOT affect the normal per-appearance embedding budget.
     * Returns the per-observation embeddings only (no aggregation).
     */
    suspend fun denseEmbed(
        uriString: String,
        metadata: VideoMetadata,
        appearance: AppearanceCandidate,
        targetSampleCount: Int,
    ): AppResult<List<EmbeddedFaceObservation>>
}
