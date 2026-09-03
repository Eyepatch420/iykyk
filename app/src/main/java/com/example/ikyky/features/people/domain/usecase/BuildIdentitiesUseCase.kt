package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.IdentityDiagnostics
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase

/**
 * Phase-4 (+ 4.5) identity layer. Split refinement runs BEFORE clustering:
 *
 *   AppearanceCandidates
 *     → temporal split refinement (cheap naive splitter always; dense
 *       change-point analysis for SUSPICIOUS appearances only)
 *     → corrected AppearanceCandidates
 *     → appearance embeddings (re-aggregated per fragment)
 *     → observation-level must-not-link constraints
 *     → unsupervised similarity calibration (gap statistic + sensitivity sweep)
 *     → agglomerative average-linkage cosine clustering (must-not-link overrides)
 *     → Person[]  (every appearance instance preserved)
 *
 * Produces `Person`s only. Deterministic. Never uses an expected person count as
 * a target.
 */
interface BuildIdentitiesUseCase {

    data class Result(
        val people: List<Person>,
        /** corrected appearances after temporal split refinement. */
        val correctedAppearances: List<AppearanceCandidate>,
        val diagnostics: IdentityDiagnostics,
        /**
         * The exact per-corrected-appearance embeddings that were fed to the
         * clusterer. Exposed for the Phase-4.6 threshold-sweep diagnostic so it
         * can re-cluster this *identical* input at other thresholds without
         * re-deriving it. Not consumed by production.
         */
        val appearanceEmbeddings: List<AppearanceEmbedding> = emptyList(),
        /** The exact must-not-link edges the clusterer used. Diagnostic surface. */
        val mustNotLinkEdges: List<MustNotLinkEdge> = emptyList(),
    )

    /**
     * @param uriString / [metadata] / [denseEmbedder] enable the Phase-4.5 dense
     *        refinement path. Pass `null` [denseEmbedder] to run Phase-4 only
     *        (naive splitter, used by the pure-Kotlin tests).
     */
    suspend operator fun invoke(
        appearances: List<AppearanceCandidate>,
        embeddedObservations: List<EmbeddedFaceObservation>,
        uriString: String? = null,
        metadata: VideoMetadata? = null,
        denseEmbedder: GenerateAppearanceEmbeddingsUseCase? = null,
    ): AppResult<Result>
}
