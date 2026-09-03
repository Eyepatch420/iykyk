package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.CalibrationResult
import com.example.ikyky.features.people.domain.model.CosineHistogram
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import com.example.ikyky.features.people.domain.model.SensitivityPoint

/**
 * **Unsupervised** similarity calibration via the pairwise-cosine gap statistic
 * (Phase 4 "similarity calibration" step).
 *
 * Idea: the distribution of all pairwise appearance-embedding cosines is
 * expected to be bimodal — a low mode (different people) and a high mode (same
 * person, appearing more than once). The merge threshold belongs in the valley
 * between them. We:
 *  1. histogram the pairwise cosines;
 *  2. find the two tallest well-separated peaks;
 *  3. take the lowest-density bin between them → threshold = that bin's centre;
 *  4. confidence = how deep the valley is vs the smaller peak;
 *  5. if the valley isn't meaningfully deep (or there's only one mode / too few
 *     appearances) → fall back to the configured
 *     [PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK] and mark the
 *     result low-confidence.
 *
 * The threshold is a *derived starting point*, not ground truth — the caller is
 * expected to report the distribution and the sensitivity sweep. Sample 1's
 * "5 people" is validation evidence, never a training target: this class never
 * sees a person count.
 *
 * Pure Kotlin, deterministic.
 */
class SimilarityCalibrator(
    private val bins: Int = PipelineDefaults.CALIBRATION_HISTOGRAM_BINS,
    private val minValleyDepthFraction: Float = PipelineDefaults.CALIBRATION_MIN_VALLEY_DEPTH_FRACTION,
    private val fallbackThreshold: Float = PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD_FALLBACK,
    private val clampMin: Float = PipelineDefaults.CALIBRATION_THRESHOLD_MIN,
    private val clampMax: Float = PipelineDefaults.CALIBRATION_THRESHOLD_MAX,
) {

    /**
     * @param clusterAt runs clustering for a given threshold and returns
     *        (personCount, mergesBlockedByMustNotLink) — used only for the
     *        sensitivity sweep, never to *choose* the threshold.
     */
    fun calibrate(
        embeddings: List<AppearanceEmbedding>,
        clusterAt: (Float) -> Pair<Int, Int> = { _ -> 0 to 0 },
    ): CalibrationResult {
        val cosines = pairwiseCosines(embeddings)
        val histogram = histogram(cosines)

        val picked = pickValley(histogram)
        val threshold: Float
        val fallbackUsed: Boolean
        val confidence: Float
        val lowPeak: Float?
        val highPeak: Float?

        if (picked == null) {
            threshold = fallbackThreshold.coerceIn(clampMin, clampMax)
            fallbackUsed = true
            confidence = 0f
            lowPeak = null
            highPeak = null
        } else {
            threshold = picked.valleyCosine.coerceIn(clampMin, clampMax)
            fallbackUsed = false
            confidence = picked.confidence
            lowPeak = picked.lowPeakCosine
            highPeak = picked.highPeakCosine
        }

        val sweep = sensitivitySweep(threshold, clusterAt)

        return CalibrationResult(
            threshold = threshold,
            fallbackUsed = fallbackUsed,
            confidence = confidence,
            lowPeakCosine = lowPeak,
            highPeakCosine = highPeak,
            histogram = histogram,
            sensitivity = sweep,
        )
    }

    // --- diagnostic surface (additive; does NOT change calibrate()) ------

    /**
     * Diagnostic-only: build the histogram + run the exact same valley detector
     * on an arbitrary cosine array (e.g. a pooled multi-sample distribution).
     * Reuses [histogram] and [pickValley] verbatim so the Phase-4.6 experiment
     * measures the *production* calibration logic, not a copy of it.
     */
    fun analyzeCosineArray(cosines: FloatArray): CosineAnalysis {
        val h = histogram(cosines)
        val v = pickValley(h)
        return CosineAnalysis(
            histogram = h,
            valleyCosine = v?.valleyCosine,
            valleyDepth = v?.confidence,
            lowPeakCosine = v?.lowPeakCosine,
            highPeakCosine = v?.highPeakCosine,
            fallbackUsed = v == null,
            proposedThreshold = v?.valleyCosine?.coerceIn(clampMin, clampMax)
                ?: fallbackThreshold.coerceIn(clampMin, clampMax),
        )
    }

    data class CosineAnalysis(
        val histogram: CosineHistogram,
        val valleyCosine: Float?,
        val valleyDepth: Float?,
        val lowPeakCosine: Float?,
        val highPeakCosine: Float?,
        val fallbackUsed: Boolean,
        val proposedThreshold: Float,
    )

    // --- pairwise cosines -------------------------------------------------

    fun pairwiseCosines(embeddings: List<AppearanceEmbedding>): FloatArray {
        val n = embeddings.size
        if (n < 2) return FloatArray(0)
        val out = FloatArray(n * (n - 1) / 2)
        var o = 0
        for (i in 0 until n) for (j in i + 1 until n) {
            out[o++] = embeddings[i].embedding.cosineSimilarity(embeddings[j].embedding)
        }
        return out
    }

    // --- histogram ------------------------------------------------------

    private fun histogram(cosines: FloatArray): CosineHistogram {
        // fixed, interpretable range: cosine of two unit vectors is in [-1, 1],
        // but identity comparisons live in ~[-0.3, 1]; use [-1, 1] so the bins
        // are stable across runs.
        val lo = -1f
        val hi = 1f
        val counts = IntArray(bins)
        val w = (hi - lo) / bins
        for (c in cosines) {
            var b = ((c - lo) / w).toInt()
            if (b < 0) b = 0
            if (b >= bins) b = bins - 1
            counts[b]++
        }
        return CosineHistogram(bins, lo, hi, counts, cosines.size)
    }

    // --- valley detection --------------------------------------------

    private data class Valley(
        val valleyCosine: Float,
        val confidence: Float,
        val lowPeakCosine: Float,
        val highPeakCosine: Float,
    )

    private fun pickValley(h: CosineHistogram): Valley? {
        if (h.totalPairs < 3) return null
        // sparse pair sets are spiky — smooth harder so noise isn't read as
        // structure. (Robustness, not a per-sample tune.)
        var c = smooth(h.counts)
        if (h.totalPairs < 300) c = smooth(c)

        // A bimodal identity distribution has an inter-identity mode in the LOW
        // cosine range and an intra-identity mode in the HIGH range. Model that
        // directly: the tallest bin below a neutral split and the tallest bin
        // above it, then the deepest bin between them.
        val neutral = ((0f - h.minValue) / (h.maxValue - h.minValue) * h.binCount).toInt()
            .coerceIn(1, h.binCount - 2)
        val loEnd = neutral.coerceAtMost(h.binCount - 1)
        val hiStart = neutral

        var loPeak = 0
        for (i in 0..loEnd) if (c[i] > c[loPeak]) loPeak = i
        var hiPeak = hiStart
        for (i in hiStart until h.binCount) if (c[i] > c[hiPeak]) hiPeak = i

        if (c[loPeak] <= 0 || c[hiPeak] <= 0 || hiPeak - loPeak < 3) return null

        var valleyIdx = loPeak + 1
        for (i in loPeak + 1 until hiPeak) if (c[i] < c[valleyIdx]) valleyIdx = i

        // Valley must be a real dip below BOTH surrounding modes.
        val smallerPeakH = minOf(c[loPeak], c[hiPeak]).toFloat()
        if (smallerPeakH <= 0f) return null
        val depthFraction = (smallerPeakH - c[valleyIdx].toFloat()) / smallerPeakH
        if (depthFraction < minValleyDepthFraction) return null

        return Valley(
            valleyCosine = h.binCenter(valleyIdx),
            confidence = depthFraction.coerceIn(0f, 1f),
            lowPeakCosine = h.binCenter(loPeak),
            highPeakCosine = h.binCenter(hiPeak),
        )
    }

    /** 3-tap moving average, edge-preserving. */
    private fun smooth(counts: IntArray): IntArray {
        if (counts.size < 3) return counts.copyOf()
        val out = IntArray(counts.size)
        for (i in counts.indices) {
            val a = counts[maxOf(0, i - 1)]
            val b = counts[i]
            val d = counts[minOf(counts.size - 1, i + 1)]
            out[i] = (a + b + d) / 3
        }
        return out
    }

    // --- sensitivity sweep ----------------------------------------------

    private fun sensitivitySweep(
        center: Float,
        clusterAt: (Float) -> Pair<Int, Int>,
    ): List<SensitivityPoint> {
        val offsets = floatArrayOf(-0.10f, -0.05f, -0.02f, 0f, 0.02f, 0.05f, 0.10f)
        return offsets
            .map { (center + it).coerceIn(0f, 1f) }
            .distinct()
            .map { t ->
                val (people, blocked) = clusterAt(t)
                SensitivityPoint(threshold = t, personCount = people, mergesBlockedByMustNotLink = blocked)
            }
    }

    companion object {
        /** Convenience for callers building the must-not-link report. */
        fun keyOf(e: MustNotLinkEdge): Pair<String, String> = e.key
    }
}
