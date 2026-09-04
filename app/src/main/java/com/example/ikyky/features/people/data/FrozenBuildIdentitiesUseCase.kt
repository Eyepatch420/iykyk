package com.example.ikyky.features.people.data

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.IdentityDiagnostics
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer
import com.example.ikyky.features.people.domain.usecase.BuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.MustNotLinkBuilder
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase
import kotlinx.coroutines.withContext

/**
 * **The FROZEN Phase 5I / Phase 6 identity-clustering path.**
 *
 * ```
 *   AppearanceCandidates + AppearanceEmbedding[]   (already arcface_5pt + mean,
 *                                                    from GenerateAppearanceEmbeddingsUseCase)
 *       -> MustNotLinkBuilder                       (observation-level IoU, same frame only)
 *       -> AgglomerativeIdentityClusterer            (average-linkage cosine)
 *       -> threshold = PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD (0.475)
 *       -> hard MNL override, deterministic ordering
 *       -> Person[]
 * ```
 *
 * This is deliberately a THIN use case: every algorithmic decision already
 * lives in [MustNotLinkBuilder] and [AgglomerativeIdentityClusterer], and the
 * 0.475 threshold has exactly one source of truth —
 * [PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD] — which
 * `FrozenPipelineConfigTest` also reads. There is no second copy of that
 * number anywhere in this class.
 *
 * ## What this deliberately does NOT do (Phase 5I froze these away)
 *
 * - **No intra-appearance split.** [IntraAppearanceSplitter] /
 *   [com.example.ikyky.features.people.domain.usecase.TemporalSplitRefiner]
 *   were Phase 4/4.5 research paths; the frozen pipeline's shot-aware tracker
 *   already produces clean appearance boundaries (absolute whip-pan barriers +
 *   the embedding gate), so a second split pass is not part of Option A.
 * - **No dense re-embedding.** The appearance embeddings this use case
 *   clusters are exactly the ones [GenerateAppearanceEmbeddingsUseCase]
 *   already computed — mean aggregation of arcface_5pt observations. Nothing
 *   here re-decodes frames or calls the embedder again.
 * - **No unsupervised threshold calibration.** [SimilarityCalibrator] tried to
 *   auto-derive a threshold from the pairwise-cosine histogram and fell back
 *   to 0.62 on low confidence — that fallback is exactly the bug this class
 *   exists to remove from the production path. The frozen threshold is fixed,
 *   not calibrated per video.
 *
 * Those classes remain in the source tree for the diagnostic screens
 * ([com.example.ikyky.features.people.domain.diagnostic]) and their existing
 * tests, which explicitly explore what calibration or dense splitting *would*
 * do — they are research/diagnostic tools now, not part of this path.
 */
class FrozenBuildIdentitiesUseCase(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val mustNotLinkBuilder: MustNotLinkBuilder = MustNotLinkBuilder(),
    private val clusterer: AgglomerativeIdentityClusterer = AgglomerativeIdentityClusterer(),
    private val threshold: Float = PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD,
) : BuildIdentitiesUseCase {

    override suspend fun invoke(
        appearances: List<AppearanceCandidate>,
        embeddedObservations: List<EmbeddedFaceObservation>,
        uriString: String?,
        metadata: VideoMetadata?,
        denseEmbedder: GenerateAppearanceEmbeddingsUseCase?,
    ): AppResult<BuildIdentitiesUseCase.Result> = withContext(dispatchers.default) {
        runCatchingResult {
            val t0 = System.currentTimeMillis()

            if (appearances.isEmpty()) {
                return@runCatchingResult BuildIdentitiesUseCase.Result(
                    people = emptyList(),
                    correctedAppearances = emptyList(),
                    diagnostics = IdentityDiagnostics(stageMs = System.currentTimeMillis() - t0),
                )
            }

            // The caller (PeopleViewModel) already ran GenerateAppearanceEmbeddingsUseCase
            // and holds its per-appearance AppearanceEmbedding[]; embeddedObservations here
            // is the per-OBSERVATION list, which this frozen path aggregates itself via the
            // same aggregator contract the embedding stage used, so the clusterer's input is
            // byte-for-byte what Phase 6's embedding stage produced — no re-aggregation logic
            // is duplicated here.
            val byAppearance = embeddedObservations.groupBy { it.appearanceId }
            val appearanceEmbeddings: List<AppearanceEmbedding> = appearances.mapNotNull { ap ->
                val members = byAppearance[ap.id]
                if (members.isNullOrEmpty()) {
                    logger.w(TAG, "appearance ${ap.id} has no embeddings — excluded from clustering")
                    null
                } else {
                    AGGREGATOR.aggregate(ap.id, ap.trackletId, members)
                }
            }

            val mnl = mustNotLinkBuilder.build(appearances)
            val outcome = clusterer.cluster(appearanceEmbeddings, threshold, mnl)

            val embeddedIds = appearanceEmbeddings.map { it.appearanceId }.toSet()
            val orphanAppearances = appearances.filter { it.id !in embeddedIds }
            val people = buildPeople(outcome.clusters, orphanAppearances)

            val diagnostics = IdentityDiagnostics(
                inputAppearances = appearances.size,
                appearancesAfterSplit = appearances.size,   // no split in the frozen path
                splitsApplied = 0,
                splitRecords = emptyList(),
                appearanceEmbeddings = appearanceEmbeddings.size,
                calibration = null,                          // no calibration — threshold is frozen
                mustNotLinkEdges = mnl,
                mergesBlockedByMustNotLink = outcome.mergesBlockedByMustNotLink,
                personCount = people.size,
                totalAppearancesGrouped = people.sumOf { it.appearanceCount },
                stageMs = System.currentTimeMillis() - t0,
            )

            logger.i(
                TAG,
                "Frozen identity clustering: ${appearances.size} appearances -> " +
                    "${appearanceEmbeddings.size} embedded -> threshold=$threshold -> " +
                    "${people.size} people, ${diagnostics.totalAppearancesGrouped} appearances grouped, " +
                    "${mnl.size} must-not-link edges, ${outcome.mergesBlockedByMustNotLink} merges blocked " +
                    "(${diagnostics.stageMs}ms) [clusterer=AgglomerativeIdentityClusterer]",
            )

            BuildIdentitiesUseCase.Result(
                people = people,
                correctedAppearances = appearances,
                diagnostics = diagnostics,
                appearanceEmbeddings = appearanceEmbeddings,
                mustNotLinkEdges = mnl,
            )
        }
    }

    private fun buildPeople(
        clusters: List<com.example.ikyky.features.people.domain.model.IdentityCluster>,
        orphanAppearances: List<AppearanceCandidate>,
    ): List<Person> {
        val fromClusters = clusters.map { c ->
            Person(
                id = "person_${c.label}",
                label = "Person ${c.label + 1}",
                appearanceIds = c.appearanceIds,
                representativeFrame = null,
            )
        }
        val fromOrphans = orphanAppearances.mapIndexed { i, ap ->
            Person(
                id = "person_orphan_$i",
                label = "Person ${fromClusters.size + i + 1}",
                appearanceIds = listOf(ap.id),
                representativeFrame = null,
            )
        }
        return (fromClusters + fromOrphans)
            .mapIndexed { i, p -> p.copy(id = "person_$i", label = "Person ${i + 1}") }
    }

    private companion object {
        const val TAG = "FrozenBuildIdentities"

        /**
         * Mean aggregation — the same contract
         * [com.example.ikyky.features.processing.domain.usecase.AppearanceEmbeddingAggregator]
         * uses in [com.example.ikyky.features.processing.domain.usecase.AppearanceEmbeddingAggregator.Mode.MEAN],
         * kept as a private instance here so this file has no dependency on the
         * legacy split/dense-refinement wiring.
         */
        val AGGREGATOR = com.example.ikyky.features.processing.domain.usecase.AppearanceEmbeddingAggregator()
    }
}
