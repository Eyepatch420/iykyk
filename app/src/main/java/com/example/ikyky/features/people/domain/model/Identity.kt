package com.example.ikyky.features.people.domain.model

import com.example.ikyky.core.model.AppearanceEmbedding

/**
 * Phase-4 identity-layer models. Pure data, framework-free.
 *
 * Flow: corrected AppearanceCandidates → appearance embeddings →
 * [CalibrationResult] (unsupervised threshold) + [MustNotLinkEdge]s →
 * agglomerative clustering → [IdentityCluster] → [Person].
 */

/**
 * One record of an embedding-assisted split applied to a Phase-2/3 appearance.
 * Kept for the report; not consumed downstream.
 */
data class AppearanceSplitRecord(
    val originalAppearanceId: String,
    val trackletId: Long,
    /** ids of the appearance fragments this appearance was cut into (>= 2). */
    val resultAppearanceIds: List<String>,
    /** timestamp(s) the cut(s) fell on. */
    val cutTimestampsMs: List<Long>,
    /** within-half minus across-half mean cosine at the chosen cut (higher ⇒ stronger evidence). */
    val separation: Float,
)

/** Histogram of pairwise appearance-embedding cosine similarities. */
data class CosineHistogram(
    val binCount: Int,
    val minValue: Float,
    val maxValue: Float,
    /** count per bin, length == binCount, bin i covers [minValue + i*w, minValue + (i+1)*w). */
    val counts: IntArray,
    val totalPairs: Int,
) {
    val binWidth: Float get() = if (binCount == 0) 0f else (maxValue - minValue) / binCount
    fun binCenter(i: Int): Float = minValue + (i + 0.5f) * binWidth

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CosineHistogram) return false
        return binCount == other.binCount && minValue == other.minValue &&
            maxValue == other.maxValue && counts.contentEquals(other.counts) &&
            totalPairs == other.totalPairs
    }

    override fun hashCode(): Int = counts.contentHashCode() * 31 + binCount
}

/**
 * Output of the unsupervised similarity calibration.
 *
 * @property threshold        the cosine merge threshold to cluster with
 * @property fallbackUsed     true ⇒ the histogram had no meaningful gap; [threshold]
 *                            is the configured fallback and the result is low-confidence
 * @property confidence       0f..1f — valley depth relative to the smaller surrounding
 *                            peak (0 when [fallbackUsed])
 * @property lowPeakCosine    cosine at the inter-identity mode (null if fallback)
 * @property highPeakCosine   cosine at the intra-identity mode (null if fallback)
 * @property histogram        the distribution the decision was made from
 * @property sensitivity      person count at thresholds around [threshold]
 */
data class CalibrationResult(
    val threshold: Float,
    val fallbackUsed: Boolean,
    val confidence: Float,
    val lowPeakCosine: Float?,
    val highPeakCosine: Float?,
    val histogram: CosineHistogram,
    val sensitivity: List<SensitivityPoint>,
) {
    val lowConfidence: Boolean get() = fallbackUsed || confidence < 0.34f
}

/** One point of the calibration sensitivity sweep. */
data class SensitivityPoint(
    val threshold: Float,
    val personCount: Int,
    val mergesBlockedByMustNotLink: Int,
)

/**
 * A must-not-link constraint between two appearances, derived from
 * observation-level simultaneity (two different faces visible in the same
 * sampled frame). Always overrides embedding similarity during clustering.
 */
data class MustNotLinkEdge(
    val appearanceIdA: String,
    val appearanceIdB: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val iou: Float,
) {
    /** unordered key */
    val key: Pair<String, String>
        get() = if (appearanceIdA <= appearanceIdB) appearanceIdA to appearanceIdB
        else appearanceIdB to appearanceIdA
}

/**
 * A group of appearance embeddings the clusterer decided are one identity.
 * Intermediate between clustering and [Person]. Preserves every appearance id.
 */
data class IdentityCluster(
    val label: Int,
    val appearanceIds: List<String>,
    val centroid: AppearanceEmbedding,
    /** mean pairwise cosine among members (1f for singletons) — a cohesion score. */
    val cohesion: Float,
)

// ---------------------------------------------------------------------------
// Phase 4.5 — dense temporal-split refinement
// ---------------------------------------------------------------------------

/** Why an appearance was (or wasn't) selected for dense analysis. */
data class SuspicionReport(
    val appearanceId: String,
    val trackletId: Long,
    val durationMs: Long,
    val globalCompactness: Float,
    val maxNeighborDrop: Float,
    val suspicious: Boolean,
    val reasons: List<String>,
)

/** One candidate cut evaluated by the dense change-point analyzer. */
data class ChangePointCandidate(
    val cutTimestampMs: Long,
    val cutIndex: Int,
    val leftCompactness: Float,
    val rightCompactness: Float,
    val crossSimilarity: Float,
    /** min(leftCompactness, rightCompactness) − crossSimilarity — the "step" size. */
    val drop: Float,
    val leftSamples: Int,
    val rightSamples: Int,
    val accepted: Boolean,
    val rejectReason: String?,
)

/**
 * Result of dense analysis for one suspicious appearance:
 * the neighbour-cosine series (for the gradual-drift check) and every candidate
 * cut, with which were accepted.
 */
data class DenseAnalysisReport(
    val appearanceId: String,
    val trackletId: Long,
    val denseSampleCount: Int,
    val denseSampleTimestampsMs: List<Long>,
    val neighborCosines: List<Float>,
    val whipPanCutsMs: List<Long>,
    val candidates: List<ChangePointCandidate>,
    val acceptedCutsMs: List<Long>,
    val resultAppearanceIds: List<String>,
)

/** Side-by-side comparison of the naive (Phase-4) vs dense (Phase-4.5) splitter. */
data class SplitterComparison(
    val naiveAppearanceCount: Int,
    val naiveSplits: Int,
    val denseAppearanceCount: Int,
    val denseSplits: Int,
    /** appearance ids the dense path split that the naive path did not (and vice-versa). */
    val onlyDenseSplit: List<String>,
    val onlyNaiveSplit: List<String>,
    val naiveMs: Long,
    val denseMs: Long,
)

/** Diagnostics for the whole Phase-4 stage. */
data class IdentityDiagnostics(
    val inputAppearances: Int = 0,
    val appearancesAfterSplit: Int = 0,
    val splitsApplied: Int = 0,
    val splitRecords: List<AppearanceSplitRecord> = emptyList(),
    val appearanceEmbeddings: Int = 0,
    val calibration: CalibrationResult? = null,
    val mustNotLinkEdges: List<MustNotLinkEdge> = emptyList(),
    val mergesBlockedByMustNotLink: Int = 0,
    val personCount: Int = 0,
    val totalAppearancesGrouped: Int = 0,
    val stageMs: Long = 0,
    // --- Phase 4.5 ---
    val suspicionReports: List<SuspicionReport> = emptyList(),
    val denseAnalyses: List<DenseAnalysisReport> = emptyList(),
    val splitterComparison: SplitterComparison? = null,
    val normalEmbeddingCalls: Int = 0,
    val denseEmbeddingCalls: Int = 0,
    val denseAnalysisMs: Long = 0,
)
