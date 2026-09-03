package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.people.domain.model.ChangePointCandidate

/**
 * **Dense** temporal change-point detection for a single suspicious appearance
 * (Phase 4.5).
 *
 * Given a dense (8–15) time-ordered sample of embedded observations it finds
 * genuine appearance boundaries — where one clearly-visible person leaves and
 * another appears — while rejecting gradual pose/expression drift.
 *
 * For each interior cut index `k` it computes (brief step 6):
 *  - `leftCompactness`  = mean pairwise cosine within [0, k)
 *  - `rightCompactness` = mean pairwise cosine within [k, n)
 *  - `crossSimilarity`  = mean cosine between the two windows
 *  - `drop`             = min(leftCompactness, rightCompactness) − crossSimilarity
 *
 * A cut is **accepted** only when BOTH windows are internally far more coherent
 * than the cross-window similarity — `leftCompactness − cross ≥ DENSE_SPLIT_MIN_DROP`
 * AND `rightCompactness − cross ≥ DENSE_SPLIT_MIN_DROP` — and each side clears a
 * low absolute sanity floor (`DENSE_SPLIT_MIN_SIDE_COMPACTNESS`). A
 * slowly-declining neighbour-cosine series (gradual drift) fails this: the cross
 * similarity trails the two sides only slightly, so neither margin reaches the
 * threshold. A sharp identity step (e.g. sides 0.88 / 0.91, cross 0.43) passes
 * easily, and so does a boundary between a pure segment and a still-mixed one
 * (the mixed side stays above the sanity floor while the cross drops hard).
 *
 * **Quality-aware**: observations below `DENSE_SPLIT_LOW_QUALITY` contribute at
 * `DENSE_SPLIT_LOW_QUALITY_WEIGHT` to every mean (they are *down-weighted*, never
 * removed — the caller keeps them in the appearance's data).
 *
 * **Multiple boundaries**: [analyze] returns every accepted cut by recursing into
 * each accepted segment, bounded by [maxDepth].
 *
 * Pure Kotlin, deterministic.
 */
class DenseTemporalChangePointAnalyzer(
    private val minSideCompactness: Float = PipelineDefaults.DENSE_SPLIT_MIN_SIDE_COMPACTNESS,
    private val minDrop: Float = PipelineDefaults.DENSE_SPLIT_MIN_DROP,
    private val minSideSamples: Int = PipelineDefaults.DENSE_SPLIT_MIN_SIDE_SAMPLES,
    private val maxDepth: Int = PipelineDefaults.DENSE_SPLIT_MAX_DEPTH,
    private val lowQuality: Float = PipelineDefaults.DENSE_SPLIT_LOW_QUALITY,
    private val lowQualityWeight: Float = PipelineDefaults.DENSE_SPLIT_LOW_QUALITY_WEIGHT,
    /** A side this incoherent is a *different mixture*, not gradual drift — recursion will split it. */
    private val severelyIncoherent: Float = PipelineDefaults.DENSE_SPLIT_SEVERELY_INCOHERENT,
) {

    data class Analysis(
        val neighborCosines: List<Float>,
        /** every candidate evaluated at the top level (for the report). */
        val candidates: List<ChangePointCandidate>,
        /** accepted cut timestamps across all recursion levels, ascending. */
        val acceptedCutsMs: List<Long>,
    )

    fun analyze(denseSamples: List<EmbeddedFaceObservation>): Analysis {
        val s = denseSamples.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
        val neighbor = (0 until s.size - 1).map {
            s[it].embedding.cosineSimilarity(s[it + 1].embedding)
        }
        val topCandidates = evaluateCandidates(s)
        val accepted = sortedSetOf<Long>()
        recurse(s, depth = 0, accepted)
        return Analysis(neighbor, topCandidates, accepted.toList())
    }

    // --- recursion -----------------------------------------------------

    private fun recurse(s: List<EmbeddedFaceObservation>, depth: Int, accepted: MutableSet<Long>) {
        if (depth >= maxDepth) return
        if (s.size < 2 * minSideSamples) return
        val candidates = evaluateCandidates(s)

        var best = candidates.filter { it.accepted }.maxByOrNull { it.drop }

        // Rescue path: the whole window is severely incoherent (a blob of 3+
        // identities interleaved by tracker bridging) so no 2-way cut yields even
        // one coherent half. Take the cut with the biggest cross-similarity
        // drop that still separates *something* (crossSimilarity clearly below
        // the window's own compactness); recursion then isolates coherent
        // sub-segments. Never fires for gradual drift — that window's overall
        // compactness stays well above [severelyIncoherent].
        if (best == null && candidates.isNotEmpty()) {
            val overall = weightedMeanPairwiseCos(s)
            if (overall < severelyIncoherent) {
                best = candidates
                    .filter { maxOf(it.leftCompactness, it.rightCompactness) - it.crossSimilarity >= minDrop }
                    .maxByOrNull { maxOf(it.leftCompactness, it.rightCompactness) - it.crossSimilarity }
            }
        }

        val chosen = best ?: return
        accepted += chosen.cutTimestampMs
        recurse(s.subList(0, chosen.cutIndex), depth + 1, accepted)
        recurse(s.subList(chosen.cutIndex, s.size), depth + 1, accepted)
    }

    // --- candidate scoring ------------------------------------------

    private fun evaluateCandidates(s: List<EmbeddedFaceObservation>): List<ChangePointCandidate> {
        val n = s.size
        val out = ArrayList<ChangePointCandidate>()
        if (n < 2 * minSideSamples) return out
        for (k in minSideSamples..(n - minSideSamples)) {
            val left = s.subList(0, k)
            val right = s.subList(k, n)
            val lc = weightedMeanPairwiseCos(left)
            val rc = weightedMeanPairwiseCos(right)
            val cross = weightedMeanCrossCos(left, right)
            val strong = maxOf(lc, rc)          // the more-coherent side
            val weak = minOf(lc, rc)            // the other side
            val strongMargin = strong - cross   // step of the coherent side vs the cross
            val drop = strongMargin

            // The coherent side must be a real segment, well separated from the
            // cross similarity.
            val strongOk = strong >= minSideCompactness && strongMargin >= minDrop
            // The other side is admissible if it's ALSO a coherent segment (a
            // clean two-way split) OR it's clearly still a *different* mixture
            // (compactness far below the floor) — then recursion splits it
            // further. The in-between "both sides only moderately coherent" zone
            // is gradual pose/expression drift and is rejected.
            val weakOk = weak >= minSideCompactness || weak < severelyIncoherent
            val accepted = strongOk && weakOk
            val reject = when {
                accepted -> null
                !strongOk && strong < minSideCompactness ->
                    "no coherent segment on either side (max=${"%.2f".format(strong)} need ≥${minSideCompactness})"
                !strongOk ->
                    "step too small (coherent side margin ${"%.2f".format(strongMargin)} need ≥${minDrop}) — gradual drift"
                else ->
                    "the other side is only moderately coherent (${"%.2f".format(weak)}) — gradual drift, not a boundary"
            }
            out += ChangePointCandidate(
                cutTimestampMs = s[k].timestampMs,
                cutIndex = k,
                leftCompactness = lc,
                rightCompactness = rc,
                crossSimilarity = cross,
                drop = drop,
                leftSamples = left.size,
                rightSamples = right.size,
                accepted = accepted,
                rejectReason = reject,
            )
        }
        return out
    }

    private fun w(o: EmbeddedFaceObservation): Float =
        if (o.qualityScore < lowQuality) lowQualityWeight else 1f

    private fun weightedMeanPairwiseCos(g: List<EmbeddedFaceObservation>): Float {
        if (g.size < 2) return 1f
        var num = 0f
        var den = 0f
        for (i in g.indices) for (j in i + 1 until g.size) {
            val wij = w(g[i]) * w(g[j])
            num += wij * g[i].embedding.cosineSimilarity(g[j].embedding)
            den += wij
        }
        return if (den == 0f) 1f else num / den
    }

    private fun weightedMeanCrossCos(
        a: List<EmbeddedFaceObservation>,
        b: List<EmbeddedFaceObservation>,
    ): Float {
        var num = 0f
        var den = 0f
        for (x in a) for (y in b) {
            val wij = w(x) * w(y)
            num += wij * x.embedding.cosineSimilarity(y.embedding)
            den += wij
        }
        return if (den == 0f) 1f else num / den
    }

    /** Centroid of a group's embeddings (diagnostic helper). */
    fun centroid(g: List<EmbeddedFaceObservation>): FaceEmbedding =
        FaceEmbedding.centroid(g.map { it.embedding })
}
