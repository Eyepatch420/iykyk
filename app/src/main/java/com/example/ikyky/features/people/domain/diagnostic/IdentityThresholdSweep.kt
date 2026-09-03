package com.example.ikyky.features.people.domain.diagnostic

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer

/**
 * **Phase 4.6 — DIAGNOSTIC ONLY.** Runs the *existing, unmodified*
 * [AgglomerativeIdentityClusterer] across a range of cosine merge thresholds on
 * one fixed set of appearance embeddings + must-not-link edges.
 *
 * Nothing here is wired into production. It exists to answer "how does the final
 * person count / cluster membership move as the threshold changes, and is there
 * a stable region?" — with evidence, before any threshold decision is made.
 *
 * Guarantees:
 *  - the ONLY thing that varies between rows is the threshold;
 *  - the clusterer, its average-linkage, its deterministic ordering and its
 *    must-not-link handling are used verbatim;
 *  - per-cluster similarity stats (mean / min pairwise, and the average-linkage
 *    similarity of the final two sub-groups that formed it) are computed here
 *    from the embeddings, without touching the clusterer.
 *
 * Pure Kotlin, deterministic.
 */
class IdentityThresholdSweep(
    private val clusterer: AgglomerativeIdentityClusterer = AgglomerativeIdentityClusterer(),
) {

    data class ClusterRow(
        val appearanceIds: List<String>,
        val size: Int,
        /** mean pairwise cosine among members (1f for singletons). */
        val meanPairwise: Float,
        /** minimum pairwise cosine among members (1f for singletons). */
        val minPairwise: Float,
        /**
         * Average-linkage cosine of the *best 2-way partition* of this cluster's
         * members — an estimate of the weakest merge that had to happen to form
         * it. For singletons this is 1f. Diagnostic proxy: the clusterer does not
         * record its merge history, so this is recomputed from the members.
         */
        val weakestInternalLinkage: Float,
    )

    data class ThresholdRow(
        val threshold: Float,
        val personCount: Int,
        val appearanceCount: Int,
        /** cluster sizes, descending — the "signature", e.g. 8,4,2,1,1,1. */
        val sizeSignature: List<Int>,
        val singletons: Int,
        val merges: Int,
        val meanClusterCompactness: Float,
        val minClusterCompactness: Float,
        val mustNotLinkViolations: Int,
        val mergesBlockedByMustNotLink: Int,
        val clusters: List<ClusterRow>,
    )

    data class Sweep(
        val sampleLabel: String,
        val appearanceEmbeddingCount: Int,
        val mustNotLinkEdgeCount: Int,
        val rows: List<ThresholdRow>,
    ) {
        /** lowest threshold whose personCount == [k] (null if none). */
        fun lowestThresholdForPeople(k: Int): Float? =
            rows.filter { it.personCount == k }.minByOrNull { it.threshold }?.threshold

        fun highestThresholdForPeople(k: Int): Float? =
            rows.filter { it.personCount == k }.maxByOrNull { it.threshold }?.threshold

        /** the longest run of consecutive rows all producing [k] people (thresholds). */
        fun stableRangeForPeople(k: Int): ClosedFloatingPointRange<Float>? {
            var bestStart = -1
            var bestLen = 0
            var curStart = -1
            var curLen = 0
            rows.forEachIndexed { i, r ->
                if (r.personCount == k) {
                    if (curLen == 0) curStart = i
                    curLen++
                    if (curLen > bestLen) { bestLen = curLen; bestStart = curStart }
                } else {
                    curLen = 0
                }
            }
            if (bestLen == 0) return null
            return rows[bestStart].threshold..rows[bestStart + bestLen - 1].threshold
        }

        /** thresholds where personCount changes vs the previous row. */
        fun transitions(): List<Triple<Float, Int, Int>> =
            rows.zipWithNext().filter { (a, b) -> a.personCount != b.personCount }
                .map { (a, b) -> Triple(b.threshold, a.personCount, b.personCount) }
    }

    val defaultThresholds: List<Float> = buildList {
        var t = 0.40f
        while (t <= 0.8001f) { add(round3(t)); t += 0.025f }
    }

    fun sweep(
        sampleLabel: String,
        appearanceEmbeddings: List<AppearanceEmbedding>,
        mustNotLink: List<MustNotLinkEdge>,
        thresholds: List<Float> = defaultThresholds,
    ): Sweep {
        val ordered = appearanceEmbeddings.sortedBy { it.appearanceId }
        val idIndex = ordered.mapIndexed { i, e -> e.appearanceId to i }.toMap()
        val n = ordered.size
        val sim = Array(n) { FloatArray(n) { 1f } }
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = ordered[i].embedding.cosineSimilarity(ordered[j].embedding)
            sim[i][j] = s; sim[j][i] = s
        }
        val mnlKeys: Set<Long> = buildSet {
            for (e in mustNotLink) {
                val a = idIndex[e.appearanceIdA] ?: continue
                val b = idIndex[e.appearanceIdB] ?: continue
                add(pairKey(a, b))
            }
        }

        val rows = thresholds.sorted().map { t ->
            val outcome = clusterer.cluster(ordered, t, mustNotLink)
            val clusterRows = outcome.clusters.map { c ->
                val idxs = c.appearanceIds.mapNotNull { idIndex[it] }
                ClusterRow(
                    appearanceIds = c.appearanceIds.sorted(),
                    size = c.appearanceIds.size,
                    meanPairwise = meanPairwise(idxs, sim),
                    minPairwise = minPairwise(idxs, sim),
                    weakestInternalLinkage = weakestPartitionLinkage(idxs, sim),
                )
            }.sortedByDescending { it.size }

            val sizes = clusterRows.map { it.size }
            ThresholdRow(
                threshold = t,
                personCount = outcome.clusters.size,
                appearanceCount = sizes.sum(),
                sizeSignature = sizes,
                singletons = sizes.count { it == 1 },
                merges = sizes.sumOf { it - 1 },
                meanClusterCompactness = clusterRows.map { it.meanPairwise }.averageOr1(),
                minClusterCompactness = clusterRows.minOfOrNull { it.meanPairwise } ?: 1f,
                mustNotLinkViolations = countMnlViolations(outcome.clusters.map { it.appearanceIds }, idIndex, mnlKeys),
                mergesBlockedByMustNotLink = outcome.mergesBlockedByMustNotLink,
                clusters = clusterRows,
            )
        }

        return Sweep(sampleLabel, n, mustNotLink.size, rows)
    }

    // --- similarity helpers (do not touch the clusterer) ----------------

    private fun meanPairwise(idxs: List<Int>, sim: Array<FloatArray>): Float {
        if (idxs.size < 2) return 1f
        var s = 0f; var c = 0
        for (i in idxs.indices) for (j in i + 1 until idxs.size) { s += sim[idxs[i]][idxs[j]]; c++ }
        return if (c == 0) 1f else s / c
    }

    private fun minPairwise(idxs: List<Int>, sim: Array<FloatArray>): Float {
        if (idxs.size < 2) return 1f
        var m = Float.MAX_VALUE
        for (i in idxs.indices) for (j in i + 1 until idxs.size) m = minOf(m, sim[idxs[i]][idxs[j]])
        return m
    }

    /**
     * Best (highest) average-linkage similarity over all 2-way splits of these
     * members — the strongest internal cut. A LOW value means the cluster is only
     * held together weakly somewhere (a candidate over-merge). Exact for ≤ ~14
     * members, sampled beyond that (clusters never get that big here).
     */
    private fun weakestPartitionLinkage(idxs: List<Int>, sim: Array<FloatArray>): Float {
        val k = idxs.size
        if (k < 2) return 1f
        if (k == 2) return sim[idxs[0]][idxs[1]]
        if (k > 14) return meanPairwise(idxs, sim) // guardrail; not reached in practice
        var best = -1f
        val total = 1 shl k
        for (mask in 1 until total - 1) {
            if (mask and 1 == 0) continue // canonical: element 0 always in group A
            val a = ArrayList<Int>(); val b = ArrayList<Int>()
            for (bit in 0 until k) if ((mask shr bit) and 1 == 1) a += idxs[bit] else b += idxs[bit]
            var s = 0f
            for (x in a) for (y in b) s += sim[x][y]
            val link = s / (a.size * b.size)
            if (link > best) best = link
        }
        return best
    }

    private fun countMnlViolations(
        clusters: List<List<String>>,
        idIndex: Map<String, Int>,
        mnlKeys: Set<Long>,
    ): Int {
        var v = 0
        for (cl in clusters) {
            val idxs = cl.mapNotNull { idIndex[it] }
            for (i in idxs.indices) for (j in i + 1 until idxs.size) {
                if (pairKey(idxs[i], idxs[j]) in mnlKeys) v++
            }
        }
        return v
    }

    private fun pairKey(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong(); val hi = maxOf(a, b).toLong()
        return (lo shl 32) or hi
    }

    private fun List<Float>.averageOr1(): Float = if (isEmpty()) 1f else (sum() / size)
    private fun round3(x: Float): Float = Math.round(x * 1000f) / 1000f
}
