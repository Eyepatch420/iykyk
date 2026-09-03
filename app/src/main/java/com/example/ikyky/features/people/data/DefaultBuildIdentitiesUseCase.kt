package com.example.ikyky.features.people.data

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.AppearanceSplitRecord
import com.example.ikyky.features.people.domain.model.IdentityCluster
import com.example.ikyky.features.people.domain.model.IdentityDiagnostics
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer
import com.example.ikyky.features.people.domain.usecase.BuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.IntraAppearanceSplitter
import com.example.ikyky.features.people.domain.usecase.MustNotLinkBuilder
import com.example.ikyky.features.people.domain.usecase.SimilarityCalibrator
import com.example.ikyky.features.people.domain.usecase.TemporalSplitRefiner
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.usecase.AppearanceEmbeddingAggregator
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Real Phase-4 (+ 4.5) orchestrator. Pure CPU work on
 * [DispatcherProvider.default]; every stage is a deterministic component.
 * The dense refinement path only runs when a [GenerateAppearanceEmbeddingsUseCase]
 * is supplied AND an appearance is flagged suspicious.
 */
class DefaultBuildIdentitiesUseCase(
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val splitter: IntraAppearanceSplitter = IntraAppearanceSplitter(),
    private val refiner: TemporalSplitRefiner = TemporalSplitRefiner(naiveSplitter = IntraAppearanceSplitter()),
    private val aggregator: AppearanceEmbeddingAggregator = AppearanceEmbeddingAggregator(),
    private val calibrator: SimilarityCalibrator = SimilarityCalibrator(),
    private val mustNotLinkBuilder: MustNotLinkBuilder = MustNotLinkBuilder(),
    private val clusterer: AgglomerativeIdentityClusterer = AgglomerativeIdentityClusterer(),
) : BuildIdentitiesUseCase {

    override suspend fun invoke(
        appearances: List<AppearanceCandidate>,
        embeddedObservations: List<EmbeddedFaceObservation>,
        uriString: String?,
        metadata: VideoMetadata?,
        denseEmbedder: GenerateAppearanceEmbeddingsUseCase?,
    ): AppResult<BuildIdentitiesUseCase.Result> = withContext(dispatchers.default) {
        val t0 = System.currentTimeMillis()

        if (appearances.isEmpty()) {
            return@withContext AppResult.Success(
                BuildIdentitiesUseCase.Result(
                    people = emptyList(),
                    correctedAppearances = emptyList(),
                    diagnostics = IdentityDiagnostics(stageMs = System.currentTimeMillis() - t0),
                )
            )
        }

        val embByAppearance: Map<String, List<EmbeddedFaceObservation>> =
            embeddedObservations.groupBy { it.appearanceId }

        // --- 1. temporal split refinement --------------------------------
        currentCoroutineContext().ensureActive()
        val corrected: List<AppearanceCandidate>
        val splitRecords: List<AppearanceSplitRecord>
        var refinerResult: TemporalSplitRefiner.Result? = null

        if (denseEmbedder != null && uriString != null && metadata != null) {
            val r = refiner.refine(uriString, metadata, appearances, embByAppearance, denseEmbedder)
            corrected = r.appearances
            splitRecords = r.splitRecords
            refinerResult = r
        } else {
            // Phase-4-only path (pure-Kotlin tests): naive splitter alone.
            val split = splitter.split(appearances, embByAppearance)
            corrected = split.appearances
            splitRecords = split.records
        }

        // re-key embedded observations onto the corrected fragments by timestamp+obsId
        val embByCorrected = reassignEmbeddings(corrected, embeddedObservations)

        // --- 2. per-(corrected)-appearance embeddings --------------------
        //
        // A dense fragment may hold none of the parent's ≤5 Phase-3 embeddings
        // (they landed in a sibling). Fall back to the parent's embeddings
        // restricted to this fragment's span so no fragment is silently dropped.
        currentCoroutineContext().ensureActive()
        val appearanceEmbeddings: List<AppearanceEmbedding> = corrected.mapNotNull { ap ->
            var members = embByCorrected[ap.id].orEmpty()
            if (members.isEmpty()) {
                val parentId = ap.id.substringBeforeLast("_s")
                members = embByAppearance[parentId].orEmpty()
                    .filter { it.timestampMs in ap.startTimestampMs..ap.endTimestampMs }
            }
            if (members.isEmpty()) {
                logger.w(TAG, "corrected appearance ${ap.id} has no embeddings — excluded from clustering")
                null
            } else {
                aggregator.aggregate(ap.id, ap.trackletId, members)
            }
        }

        // --- 3. must-not-link constraints (observation-level) -----------
        currentCoroutineContext().ensureActive()
        val mnl = mustNotLinkBuilder.build(corrected)

        // --- 4. unsupervised calibration + sensitivity sweep -----------
        currentCoroutineContext().ensureActive()
        val calibration = calibrator.calibrate(appearanceEmbeddings) { t ->
            val o = clusterer.cluster(appearanceEmbeddings, t, mnl)
            o.clusters.size to o.mergesBlockedByMustNotLink
        }

        // --- 5. cluster at the calibrated threshold --------------------
        currentCoroutineContext().ensureActive()
        val outcome = clusterer.cluster(appearanceEmbeddings, calibration.threshold, mnl)

        // appearances with no embedding still exist as instances — attach each as
        // its own singleton Person so nothing is lost.
        val embeddedIds = appearanceEmbeddings.map { it.appearanceId }.toSet()
        val orphanAppearances = corrected.filter { it.id !in embeddedIds }

        val people = buildPeople(outcome.clusters, orphanAppearances)

        val diagnostics = IdentityDiagnostics(
            inputAppearances = appearances.size,
            appearancesAfterSplit = corrected.size,
            splitsApplied = splitRecords.size,
            splitRecords = splitRecords,
            appearanceEmbeddings = appearanceEmbeddings.size,
            calibration = calibration,
            mustNotLinkEdges = mnl,
            mergesBlockedByMustNotLink = outcome.mergesBlockedByMustNotLink,
            personCount = people.size,
            totalAppearancesGrouped = people.sumOf { it.appearanceCount },
            stageMs = System.currentTimeMillis() - t0,
            suspicionReports = refinerResult?.suspicionReports.orEmpty(),
            denseAnalyses = refinerResult?.denseAnalyses.orEmpty(),
            splitterComparison = refinerResult?.comparison,
            normalEmbeddingCalls = embeddedObservations.size,
            denseEmbeddingCalls = refinerResult?.denseEmbeddingCalls ?: 0,
            denseAnalysisMs = refinerResult?.denseAnalysisMs ?: 0,
        )
        logger.i(
            TAG,
            "Phase 4/4.5: ${appearances.size} appearances → split +${splitRecords.size} → " +
                "${corrected.size} → threshold ${"%.3f".format(calibration.threshold)}" +
                (if (calibration.lowConfidence) " (LOW CONFIDENCE${if (calibration.fallbackUsed) ", fallback" else ""})" else "") +
                " → ${people.size} people, ${diagnostics.totalAppearancesGrouped} appearances grouped, " +
                "${mnl.size} must-not-link edges, ${outcome.mergesBlockedByMustNotLink} merges blocked" +
                (refinerResult?.let { ", ${it.denseEmbeddingCalls} dense embeddings (${it.denseAnalysisMs}ms)" } ?: ""),
        )

        AppResult.Success(
            BuildIdentitiesUseCase.Result(
                people = people,
                correctedAppearances = corrected,
                diagnostics = diagnostics,
                appearanceEmbeddings = appearanceEmbeddings,
                mustNotLinkEdges = mnl,
            )
        )
    }

    /** Re-attach embedded observations to whichever corrected fragment now owns their timestamp. */
    private fun reassignEmbeddings(
        corrected: List<AppearanceCandidate>,
        embedded: List<EmbeddedFaceObservation>,
    ): Map<String, List<EmbeddedFaceObservation>> {
        val obsOwner = HashMap<String, String>() // observationId -> corrected appearance id
        for (ap in corrected) for (ref in ap.observations) obsOwner[ref.observationId] = ap.id
        val byOwner = HashMap<String, MutableList<EmbeddedFaceObservation>>()
        for (e in embedded) {
            val owner = obsOwner[e.observationId] ?: continue
            byOwner.getOrPut(owner) { mutableListOf() }.add(e)
        }
        return byOwner
    }

    private fun buildPeople(
        clusters: List<IdentityCluster>,
        orphanAppearances: List<AppearanceCandidate>,
    ): List<Person> {
        val fromClusters = clusters.map { c ->
            Person(
                id = "person_${c.label}",
                label = "Person ${c.label + 1}",
                appearanceIds = c.appearanceIds, // every instance preserved
                representativeFrame = null,       // representative-frame selection is a later phase
            )
        }
        val fromOrphans = orphanAppearances.mapIndexed { i, ap ->
            Person(
                id = "person_orphan_${i}",
                label = "Person ${fromClusters.size + i + 1}",
                appearanceIds = listOf(ap.id),
                representativeFrame = null,
            )
        }
        return (fromClusters + fromOrphans)
            .mapIndexed { i, p -> p.copy(id = "person_$i", label = "Person ${i + 1}") }
    }

    private companion object {
        const val TAG = "BuildIdentities"
    }
}
