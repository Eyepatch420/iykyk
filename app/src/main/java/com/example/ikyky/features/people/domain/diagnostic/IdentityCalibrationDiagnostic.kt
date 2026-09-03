package com.example.ikyky.features.people.domain.diagnostic

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge

/**
 * **Phase 4.6 — DIAGNOSTIC ONLY orchestrator.** Bundles the threshold sweep and
 * the pairwise-cosine statistics for one sample, and (separately) the pooled
 * cosine distribution across samples.
 *
 * Nothing here feeds production. It is called from an instrumentation test that
 * runs the real Phase 2 → 3 → 4-split pipeline and hands over the *corrected*
 * appearance embeddings + the *production* must-not-link edges.
 *
 * Pure Kotlin, deterministic.
 */
class IdentityCalibrationDiagnostic(
    private val sweep: IdentityThresholdSweep = IdentityThresholdSweep(),
    private val stats: PairwiseCosineStats = PairwiseCosineStats(),
) {

    data class SampleReport(
        val label: String,
        val sweep: IdentityThresholdSweep.Sweep,
        val stats: PairwiseCosineStats.Stats,
    )

    data class PooledReport(
        val label: String,
        val stats: PairwiseCosineStats.Stats,
        /**
         * A pooled threshold is only ever a *calibration estimate*. It is NEVER
         * used to cluster across videos — this field is documentation.
         */
        val crossVideoClusteringPerformed: Boolean = false,
    )

    fun analyzeSample(
        label: String,
        appearanceEmbeddings: List<AppearanceEmbedding>,
        mustNotLink: List<MustNotLinkEdge>,
        thresholds: List<Float> = sweep.defaultThresholds,
    ): SampleReport {
        val mnlPairs = mustNotLink.map { it.key }.toSet()
        return SampleReport(
            label = label,
            sweep = sweep.sweep(label, appearanceEmbeddings, mustNotLink, thresholds),
            stats = stats.compute(label, appearanceEmbeddings, mnlPairs),
        )
    }

    /**
     * @param perSample one appearance-embedding list per video. Cosines are
     *        pooled **within each sample only**; there are no cross-sample pairs.
     */
    fun analyzePooled(perSample: List<Pair<String, List<AppearanceEmbedding>>>): PooledReport {
        val label = "pooled[" + perSample.joinToString("+") { it.first } + "]"
        return PooledReport(
            label = label,
            stats = stats.computePooled(label, perSample.map { it.second }),
            crossVideoClusteringPerformed = false,
        )
    }
}
