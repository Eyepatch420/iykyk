package com.example.ikyky.features.people.domain.diagnostic

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.CosineHistogram
import com.example.ikyky.features.people.domain.usecase.SimilarityCalibrator

/**
 * **Phase 4.6 — DIAGNOSTIC ONLY.** Descriptive statistics + the production
 * histogram/valley for a set of pairwise appearance-embedding cosines.
 *
 * The histogram + valley come straight from [SimilarityCalibrator.analyzeCosineArray]
 * (the real calibration logic) — this class only adds min/max/mean/median/
 * percentiles and the structural-evidence overlay (must-not-link pairs vs the
 * rest).
 *
 * Pure Kotlin, deterministic.
 */
class PairwiseCosineStats(
    private val calibrator: SimilarityCalibrator = SimilarityCalibrator(),
) {

    data class Stats(
        val label: String,
        val embeddingCount: Int,
        val pairCount: Int,
        val min: Float,
        val max: Float,
        val mean: Float,
        val median: Float,
        val p10: Float,
        val p25: Float,
        val p75: Float,
        val p90: Float,
        val histogram: CosineHistogram,
        val valleyCosine: Float?,
        val valleyDepth: Float?,
        val lowPeakCosine: Float?,
        val highPeakCosine: Float?,
        val fallbackUsed: Boolean,
        val proposedThreshold: Float,
        /**
         * Structural-evidence overlay (Phase 4.6 step 8). NOT ground truth:
         *  - mustNotLink*: cosines of pairs the observation-level rule forbids
         *    from being the same person — a *different-identity* sample;
         *  - other*: every remaining pair (unknown; mix of same + different).
         * Reported so a candidate threshold can be checked against known
         * different-identity evidence without inventing same-identity labels.
         */
        val mustNotLinkPairCount: Int,
        val mustNotLinkMeanCosine: Float?,
        val mustNotLinkMaxCosine: Float?,
        val otherMeanCosine: Float?,
    )

    /**
     * @param mustNotLinkPairs unordered appearance-id pairs that cannot be the
     *        same person (from the production MustNotLinkBuilder).
     */
    fun compute(
        label: String,
        embeddings: List<AppearanceEmbedding>,
        mustNotLinkPairs: Set<Pair<String, String>> = emptySet(),
    ): Stats {
        val ordered = embeddings.sortedBy { it.appearanceId }
        val cos = calibrator.pairwiseCosines(ordered)
        return statsFor(label, ordered.size, cos, mustNotLinkPairs, ordered)
    }

    /**
     * Pooled statistics: cosines are computed **within each sample only** and
     * concatenated. There are NEVER cross-sample pairs, so this can only ever be
     * used to estimate the similarity *distribution* — it can never merge an
     * appearance from one video with one from another.
     */
    fun computePooled(
        label: String,
        perSample: List<List<AppearanceEmbedding>>,
    ): Stats {
        var totalEmb = 0
        val chunks = ArrayList<FloatArray>()
        for (sample in perSample) {
            val ordered = sample.sortedBy { it.appearanceId }
            totalEmb += ordered.size
            chunks += calibrator.pairwiseCosines(ordered) // WITHIN-sample only
        }
        val pooled = FloatArray(chunks.sumOf { it.size })
        var o = 0
        for (chunk in chunks) { chunk.copyInto(pooled, o); o += chunk.size }
        return statsFor(label, totalEmb, pooled, emptySet(), emptyList())
    }

    private fun statsFor(
        label: String,
        embeddingCount: Int,
        cos: FloatArray,
        mustNotLinkPairs: Set<Pair<String, String>>,
        ordered: List<AppearanceEmbedding>,
    ): Stats {
        val analysis = calibrator.analyzeCosineArray(cos)
        val sorted = cos.sortedArray()

        fun pct(p: Double): Float {
            if (sorted.isEmpty()) return Float.NaN
            val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        // structural overlay (only when we have the id list, i.e. single-sample)
        var mnlCount = 0
        var mnlSum = 0f
        var mnlMax = -1f
        var otherSum = 0f
        var otherCount = 0
        if (ordered.isNotEmpty() && mustNotLinkPairs.isNotEmpty()) {
            for (i in ordered.indices) for (j in i + 1 until ordered.size) {
                val key = unordered(ordered[i].appearanceId, ordered[j].appearanceId)
                val c = ordered[i].embedding.cosineSimilarity(ordered[j].embedding)
                if (key in mustNotLinkPairs) {
                    mnlCount++; mnlSum += c; mnlMax = maxOf(mnlMax, c)
                } else {
                    otherSum += c; otherCount++
                }
            }
        }

        return Stats(
            label = label,
            embeddingCount = embeddingCount,
            pairCount = cos.size,
            min = sorted.firstOrNull() ?: Float.NaN,
            max = sorted.lastOrNull() ?: Float.NaN,
            mean = if (cos.isEmpty()) Float.NaN else cos.sum() / cos.size,
            median = pct(0.50),
            p10 = pct(0.10), p25 = pct(0.25), p75 = pct(0.75), p90 = pct(0.90),
            histogram = analysis.histogram,
            valleyCosine = analysis.valleyCosine,
            valleyDepth = analysis.valleyDepth,
            lowPeakCosine = analysis.lowPeakCosine,
            highPeakCosine = analysis.highPeakCosine,
            fallbackUsed = analysis.fallbackUsed,
            proposedThreshold = analysis.proposedThreshold,
            mustNotLinkPairCount = mnlCount,
            mustNotLinkMeanCosine = if (mnlCount == 0) null else mnlSum / mnlCount,
            mustNotLinkMaxCosine = if (mnlCount == 0) null else mnlMax,
            otherMeanCosine = if (otherCount == 0) null else otherSum / otherCount,
        )
    }

    private fun unordered(a: String, b: String) = if (a <= b) a to b else b to a
}
