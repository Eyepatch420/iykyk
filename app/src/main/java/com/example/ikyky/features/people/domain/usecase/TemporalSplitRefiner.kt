package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.people.domain.model.AppearanceSplitRecord
import com.example.ikyky.features.people.domain.model.DenseAnalysisReport
import com.example.ikyky.features.people.domain.model.SplitterComparison
import com.example.ikyky.features.people.domain.model.SuspicionReport

/**
 * Phase-4.5 temporal-split refinement.
 *
 * For each Phase-2 appearance:
 *  - the cheap Phase-4 [IntraAppearanceSplitter] always runs (baseline);
 *  - if [SuspiciousAppearanceSelector] flags it, the **dense** path runs:
 *    dense-embed 8–15 temporally-spread observations → [WhipPanGapAnalyzer] +
 *    [DenseTemporalChangePointAnalyzer] → accepted boundaries →
 *    [applyBoundaries] materialises the fragments.
 *  - the dense result wins for a suspicious appearance ONLY if it produced at
 *    least as many fragments as the naive path (dense never *under-splits*); a
 *    dense result with zero cuts falls back to the naive result. This keeps the
 *    conservative "don't replace on false splits" guarantee.
 *
 * Emits the full evidence (suspicion reports, dense analyses, naive-vs-dense
 * comparison) for the report.
 *
 * The dense embedder is only touched for suspicious appearances — ordinary ones
 * cost nothing extra.
 */
class TemporalSplitRefiner(
    private val naiveSplitter: IntraAppearanceSplitter = IntraAppearanceSplitter(),
    private val suspicionSelector: SuspiciousAppearanceSelector = SuspiciousAppearanceSelector(),
    private val changePointAnalyzer: DenseTemporalChangePointAnalyzer = DenseTemporalChangePointAnalyzer(),
    private val whipPanAnalyzer: WhipPanGapAnalyzer = WhipPanGapAnalyzer(),
    private val minDenseSamples: Int = PipelineDefaults.DENSE_ANALYSIS_MIN_SAMPLES,
    private val maxDenseSamples: Int = PipelineDefaults.DENSE_ANALYSIS_MAX_SAMPLES,
    private val secondsPerSample: Float = PipelineDefaults.DENSE_ANALYSIS_SECONDS_PER_SAMPLE,
) {

    data class Result(
        val appearances: List<AppearanceCandidate>,
        val splitRecords: List<AppearanceSplitRecord>,
        val suspicionReports: List<SuspicionReport>,
        val denseAnalyses: List<DenseAnalysisReport>,
        val comparison: SplitterComparison,
        val denseEmbeddingCalls: Int,
        val denseAnalysisMs: Long,
    )

    /** How many dense samples for an appearance of this duration. */
    fun denseSampleTarget(durationMs: Long): Int {
        val bySeconds = (durationMs / 1000f / secondsPerSample).toInt()
        return bySeconds.coerceIn(minDenseSamples, maxDenseSamples)
    }

    suspend fun refine(
        uriString: String,
        metadata: VideoMetadata,
        appearances: List<AppearanceCandidate>,
        embeddingsByAppearance: Map<String, List<EmbeddedFaceObservation>>,
        denseEmbedder: GenerateAppearanceEmbeddingsUseCase,
    ): Result {
        // --- baseline: naive splitter on everything ---
        val naiveStart = System.currentTimeMillis()
        val naive = naiveSplitter.split(appearances, embeddingsByAppearance)
        val naiveMs = System.currentTimeMillis() - naiveStart
        val naiveSplitOriginals = naive.records.map { it.originalAppearanceId }.toSet()

        // --- suspicion ---
        val suspicion = suspicionSelector.evaluate(appearances, embeddingsByAppearance)
        val suspiciousIds = suspicion.filter { it.suspicious }.map { it.appearanceId }.toSet()

        val denseStart = System.currentTimeMillis()
        var denseEmbeddingCalls = 0
        val outAppearances = ArrayList<AppearanceCandidate>()
        val outRecords = ArrayList<AppearanceSplitRecord>()
        val denseReports = ArrayList<DenseAnalysisReport>()
        val denseSplitOriginals = HashSet<String>()

        for (appearance in appearances) {
            val naiveFragments = naive.appearances.filter { frag ->
                frag.id == appearance.id || frag.id.startsWith("${appearance.id}_s")
            }

            if (appearance.id !in suspiciousIds) {
                outAppearances += naiveFragments
                naive.records.firstOrNull { it.originalAppearanceId == appearance.id }?.let { outRecords += it }
                continue
            }

            // --- dense path ---
            val target = denseSampleTarget(appearance.endTimestampMs - appearance.startTimestampMs)
            val dense = when (val r = denseEmbedder.denseEmbed(uriString, metadata, appearance, target)) {
                is com.example.ikyky.core.common.result.AppResult.Success -> r.value
                is com.example.ikyky.core.common.result.AppResult.Failure -> emptyList()
            }
            denseEmbeddingCalls += dense.size

            if (dense.size < 2 * PipelineDefaults.DENSE_SPLIT_MIN_SIDE_SAMPLES) {
                // not enough dense evidence — keep naive
                outAppearances += naiveFragments
                naive.records.firstOrNull { it.originalAppearanceId == appearance.id }?.let { outRecords += it }
                denseReports += DenseAnalysisReport(
                    appearanceId = appearance.id, trackletId = appearance.trackletId,
                    denseSampleCount = dense.size, denseSampleTimestampsMs = dense.map { it.timestampMs },
                    neighborCosines = emptyList(), whipPanCutsMs = emptyList(),
                    candidates = emptyList(), acceptedCutsMs = emptyList(),
                    resultAppearanceIds = naiveFragments.map { it.id },
                )
                continue
            }

            val denseSorted = dense.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
            val whipCuts = whipPanAnalyzer.findWhipPanCuts(appearance.observations).map { it.afterTimestampMs }
            val analysis = changePointAnalyzer.analyze(denseSorted)
            val allCutsMs = (analysis.acceptedCutsMs + whipCuts).toSortedSet().toList()

            val fragments = if (allCutsMs.isEmpty()) {
                naiveFragments // dense found nothing → fall back to naive (never under-split)
            } else {
                val f = applyBoundaries(appearance, allCutsMs)
                // never under-split relative to naive
                if (f.size >= naiveFragments.size) f else naiveFragments
            }

            outAppearances += fragments
            if (fragments.size > 1) {
                denseSplitOriginals += appearance.id
                outRecords += AppearanceSplitRecord(
                    originalAppearanceId = appearance.id,
                    trackletId = appearance.trackletId,
                    resultAppearanceIds = fragments.map { it.id },
                    cutTimestampsMs = allCutsMs,
                    separation = analysis.candidates.filter { it.accepted }.maxOfOrNull { it.drop } ?: 0f,
                )
            }
            denseReports += DenseAnalysisReport(
                appearanceId = appearance.id, trackletId = appearance.trackletId,
                denseSampleCount = denseSorted.size,
                denseSampleTimestampsMs = denseSorted.map { it.timestampMs },
                neighborCosines = analysis.neighborCosines,
                whipPanCutsMs = whipCuts,
                candidates = analysis.candidates,
                acceptedCutsMs = analysis.acceptedCutsMs,
                resultAppearanceIds = fragments.map { it.id },
            )
        }
        val denseAnalysisMs = System.currentTimeMillis() - denseStart

        val comparison = SplitterComparison(
            naiveAppearanceCount = naive.appearances.size,
            naiveSplits = naive.records.size,
            denseAppearanceCount = outAppearances.size,
            denseSplits = outRecords.size,
            onlyDenseSplit = (denseSplitOriginals - naiveSplitOriginals).sorted(),
            onlyNaiveSplit = (naiveSplitOriginals - denseSplitOriginals).sorted(),
            naiveMs = naiveMs,
            denseMs = denseAnalysisMs,
        )

        return Result(
            appearances = outAppearances,
            splitRecords = outRecords,
            suspicionReports = suspicion,
            denseAnalyses = denseReports,
            comparison = comparison,
            denseEmbeddingCalls = denseEmbeddingCalls,
            denseAnalysisMs = denseAnalysisMs,
        )
    }

    /**
     * Partition [appearance]'s observations at [cutsMs] (each cut is the start of
     * the later fragment) and materialise contiguous [AppearanceCandidate]
     * fragments. Every observation is preserved.
     */
    fun applyBoundaries(appearance: AppearanceCandidate, cutsMs: List<Long>): List<AppearanceCandidate> {
        val obs = appearance.observations.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
        if (cutsMs.isEmpty() || obs.isEmpty()) return listOf(appearance)
        val bounds = cutsMs.sorted()
        val groups = ArrayList<MutableList<AppearanceObservationRef>>()
        groups += mutableListOf<AppearanceObservationRef>()
        var bi = 0
        for (o in obs) {
            while (bi < bounds.size && o.timestampMs >= bounds[bi]) {
                groups += mutableListOf<AppearanceObservationRef>()
                bi++
            }
            groups.last() += o
        }
        val nonEmpty = groups.filter { it.isNotEmpty() }
        if (nonEmpty.size <= 1) return listOf(appearance)
        return nonEmpty.mapIndexed { i, g -> materialize(appearance, g, i) }
    }

    private fun materialize(
        source: AppearanceCandidate,
        refs: List<AppearanceObservationRef>,
        idx: Int,
    ): AppearanceCandidate {
        val fragId = if (idx == 0) source.id else "${source.id}_s$idx"
        val best = refs.maxByOrNull { it.qualityScore } ?: refs.first()
        return source.copy(
            id = fragId,
            startTimestampMs = refs.first().timestampMs,
            endTimestampMs = refs.last().timestampMs,
            firstFrameIndex = refs.first().frameIndex,
            lastFrameIndex = refs.last().frameIndex,
            observationCount = refs.size,
            meanQuality = refs.map { it.qualityScore }.average().toFloat(),
            bestQuality = best.qualityScore,
            bestFrameIndex = best.frameIndex,
            bestFrameTimestampMs = best.timestampMs,
            bestFrameBox = best.canonicalBox,
            observations = refs,
        )
    }
}
