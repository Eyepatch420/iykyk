package com.example.ikyky.identity

import com.example.ikyky.features.people.domain.diagnostic.IdentityThresholdSweep
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4.6 — diagnostic threshold sweep. Pure JVM. */
class IdentityThresholdSweepTest {

    // three tight identity groups, well separated
    private fun threeGroups() =
        (0..2).map { TestEmbeddings.appEmb("a$it", 0.05, seed = it) } +
        (0..2).map { TestEmbeddings.appEmb("b$it", 1.0, seed = it + 10) } +
        (0..2).map { TestEmbeddings.appEmb("c$it", 2.0, seed = it + 20) }

    // step 13.1 — sweep contains all requested thresholds
    @Test
    fun sweep_containsAllRequestedThresholds() {
        val sw = IdentityThresholdSweep()
        val custom = listOf(0.40f, 0.50f, 0.60f, 0.70f, 0.80f)
        val out = sw.sweep("t", threeGroups(), emptyList(), custom)
        assertEquals(custom.sorted(), out.rows.map { it.threshold })
        // the built-in default list covers the brief's requested points
        assertTrue(sw.defaultThresholds.containsAll(listOf(0.40f, 0.425f, 0.50f, 0.625f, 0.80f)))
    }

    // step 13.2 — deterministic
    @Test
    fun sweep_isDeterministic() {
        val emb = threeGroups()
        val a = IdentityThresholdSweep().sweep("t", emb, emptyList())
        val b = IdentityThresholdSweep().sweep("t", emb.reversed(), emptyList())
        assertEquals(
            a.rows.map { it.threshold to it.clusters.map { c -> c.appearanceIds } },
            b.rows.map { it.threshold to it.clusters.map { c -> c.appearanceIds } },
        )
    }

    // step 13.3 — a lower threshold can never produce MORE clusters than a higher one
    // (agglomerative is monotone: lowering the bar only ever merges more)
    @Test
    fun lowerThreshold_neverIncreasesClusterCount() {
        val out = IdentityThresholdSweep().sweep("t", threeGroups(), emptyList())
        val counts = out.rows.map { it.personCount }
        // sorted by ascending threshold ⇒ personCount must be non-decreasing
        for (i in 1 until counts.size) {
            assertTrue(
                "threshold ${out.rows[i].threshold} gave ${counts[i]} people, " +
                    "threshold ${out.rows[i - 1].threshold} gave ${counts[i - 1]} — non-monotone!",
                counts[i] >= counts[i - 1],
            )
        }
    }

    // step 13.4 — must-not-link enforced at every threshold
    @Test
    fun mustNotLink_enforcedAcrossAllThresholds() {
        // a0 and b0 would merge at a low threshold — forbid it
        val emb = threeGroups()
        val mnl = listOf(MustNotLinkEdge("a0", "b0", 1, 1000, 0.05f))
        val out = IdentityThresholdSweep().sweep("t", emb, mnl)
        out.rows.forEach { r ->
            assertEquals("MNL violated at threshold ${r.threshold}", 0, r.mustNotLinkViolations)
            val a0 = r.clusters.first { "a0" in it.appearanceIds }
            val b0 = r.clusters.first { "b0" in it.appearanceIds }
            assertTrue("a0 and b0 merged at ${r.threshold}", a0 !== b0 && a0.appearanceIds != b0.appearanceIds)
        }
    }

    // step 13.5 — cluster membership reported correctly (partition of the input)
    @Test
    fun clusterMembership_isAPartitionOfTheInput() {
        val emb = threeGroups()
        val ids = emb.map { it.appearanceId }.sorted()
        IdentityThresholdSweep().sweep("t", emb, emptyList()).rows.forEach { r ->
            val flat = r.clusters.flatMap { it.appearanceIds }.sorted()
            assertEquals("threshold ${r.threshold}: not a partition", ids, flat)
            assertEquals(r.appearanceCount, ids.size)
            assertEquals(r.sizeSignature.sum(), ids.size)
        }
    }

    @Test
    fun stableRange_andTransitions_areReported() {
        val out = IdentityThresholdSweep().sweep("t", threeGroups(), emptyList())
        // at a high enough threshold all 9 are singletons; at a low enough one they collapse
        assertTrue(out.rows.first().personCount <= out.rows.last().personCount)
        val range3 = out.stableRangeForPeople(3)
        assertTrue("expected a stable 3-people region somewhere", range3 != null)
        // transitions list is consistent with the rows
        out.transitions().forEach { (thr, from, to) ->
            assertTrue(from != to)
            assertTrue(out.rows.any { it.threshold == thr && it.personCount == to })
        }
    }

    @Test
    fun perClusterSimilarityStats_areSane() {
        val out = IdentityThresholdSweep().sweep("t", threeGroups(), emptyList())
        val lowThresholdRow = out.rows.first()
        lowThresholdRow.clusters.forEach { c ->
            assertTrue(c.minPairwise <= c.meanPairwise + 1e-4f)
            assertTrue(c.meanPairwise in -1.001f..1.001f)
            if (c.size >= 2) assertTrue(c.weakestInternalLinkage in -1.001f..1.001f)
        }
    }
}
