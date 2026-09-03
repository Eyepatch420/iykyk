package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.people.domain.model.IdentityCluster
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge

/**
 * **Agglomerative hierarchical clustering** of appearance embeddings —
 * average-linkage cosine similarity, single distance threshold, with hard
 * **must-not-link** constraints.
 *
 *  - start: every appearance is its own cluster;
 *  - repeatedly merge the two clusters with the highest average-linkage cosine,
 *    provided that similarity ≥ `threshold` AND no member pair across the two
 *    clusters is must-not-linked;
 *  - stop when no admissible merge remains.
 *
 * A must-not-link edge **always** overrides similarity — two appearances joined
 * by one can never land in the same cluster, transitively either.
 *
 * Deterministic: appearances are processed in sorted-id order; ties in linkage
 * similarity are broken by the (sorted) id pair. No randomness, no `k`.
 *
 * Pure Kotlin. Scales fine for the tens-of-appearances this app produces
 * (O(n³) worst case, n ≲ 40).
 */
class AgglomerativeIdentityClusterer {

    data class Outcome(
        val clusters: List<IdentityCluster>,
        val mergesBlockedByMustNotLink: Int,
    )

    fun cluster(
        embeddings: List<AppearanceEmbedding>,
        threshold: Float,
        mustNotLink: List<MustNotLinkEdge>,
    ): Outcome {
        if (embeddings.isEmpty()) return Outcome(emptyList(), 0)

        val ordered = embeddings.sortedBy { it.appearanceId }
        val n = ordered.size

        // pairwise cosine, precomputed
        val sim = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val s = ordered[i].embedding.cosineSimilarity(ordered[j].embedding)
            sim[i][j] = s
            sim[j][i] = s
        }

        // must-not-link as an index set of unordered pairs
        val idIndex = ordered.mapIndexed { i, e -> e.appearanceId to i }.toMap()
        val mnl = HashSet<Long>()
        for (e in mustNotLink) {
            val a = idIndex[e.appearanceIdA] ?: continue
            val b = idIndex[e.appearanceIdB] ?: continue
            mnl += pairKey(a, b)
        }

        // clusters as lists of member indices; active set
        val members = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) members[i] = mutableListOf(i)
        var nextClusterId = n
        var blocked = 0

        while (true) {
            var bestA = -1
            var bestB = -1
            var bestSim = threshold  // must strictly reach the threshold
            var bestKey = Long.MAX_VALUE

            val active = members.keys.sorted()
            for (ai in active.indices) {
                for (bi in ai + 1 until active.size) {
                    val ca = active[ai]
                    val cb = active[bi]
                    val ma = members[ca]!!
                    val mb = members[cb]!!

                    if (violatesMustNotLink(ma, mb, mnl)) continue

                    val link = averageLinkage(ma, mb, sim)
                    val key = pairKey(ca, cb)
                    if (link > bestSim || (link == bestSim && key < bestKey)) {
                        bestSim = link
                        bestA = ca
                        bestB = cb
                        bestKey = key
                    }
                }
            }

            if (bestA < 0) break

            // merge B into a new cluster id (keeps determinism simple)
            val merged = ArrayList<Int>(members[bestA]!!.size + members[bestB]!!.size)
            merged += members[bestA]!!
            merged += members[bestB]!!
            merged.sort()
            members.remove(bestA)
            members.remove(bestB)
            members[nextClusterId++] = merged
        }

        // count how many across-threshold pairs were forbidden purely by mnl
        blocked = countBlockedMerges(members, sim, mnl, threshold)

        val clusters = members.entries
            .sortedBy { it.value.first() }
            .mapIndexed { label, entry ->
                val idxs = entry.value
                val appIds = idxs.map { ordered[it].appearanceId }.sorted()
                IdentityCluster(
                    label = label,
                    appearanceIds = appIds,
                    centroid = centroidOf(idxs.map { ordered[it] }),
                    cohesion = cohesionOf(idxs, sim),
                )
            }
        return Outcome(clusters, blocked)
    }

    private fun violatesMustNotLink(a: List<Int>, b: List<Int>, mnl: Set<Long>): Boolean {
        for (x in a) for (y in b) if (pairKey(x, y) in mnl) return true
        return false
    }

    private fun averageLinkage(a: List<Int>, b: List<Int>, sim: Array<FloatArray>): Float {
        var sum = 0f
        for (x in a) for (y in b) sum += sim[x][y]
        return sum / (a.size * b.size)
    }

    private fun countBlockedMerges(
        members: Map<Int, List<Int>>,
        sim: Array<FloatArray>,
        mnl: Set<Long>,
        threshold: Float,
    ): Int {
        val clusters = members.values.toList()
        var blocked = 0
        for (i in clusters.indices) for (j in i + 1 until clusters.size) {
            val a = clusters[i]; val b = clusters[j]
            if (averageLinkage(a, b, sim) >= threshold && violatesMustNotLink(a, b, mnl)) blocked++
        }
        return blocked
    }

    private fun centroidOf(members: List<AppearanceEmbedding>): AppearanceEmbedding {
        val base = members.minByOrNull { it.appearanceId }!!
        val centroid = FaceEmbedding.centroid(members.map { it.embedding })
        return base.copy(
            embedding = centroid,
            memberCount = members.sumOf { it.memberCount },
            rejectedOutliers = members.sumOf { it.rejectedOutliers },
            meanMemberSimilarity = members.map { it.meanMemberSimilarity }.average().toFloat(),
            bestQuality = members.maxOf { it.bestQuality },
        )
    }

    private fun cohesionOf(idxs: List<Int>, sim: Array<FloatArray>): Float {
        if (idxs.size < 2) return 1f
        var sum = 0f
        var count = 0
        for (i in idxs.indices) for (j in i + 1 until idxs.size) {
            sum += sim[idxs[i]][idxs[j]]
            count++
        }
        return if (count == 0) 1f else sum / count
    }

    private fun pairKey(a: Int, b: Int): Long {
        val lo = minOf(a, b).toLong()
        val hi = maxOf(a, b).toLong()
        return (lo shl 32) or hi
    }
}
