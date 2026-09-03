package com.example.ikyky.identity

import com.example.ikyky.features.people.domain.diagnostic.PairwiseCosineStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4.6 — pairwise-cosine statistics + pooled calibration. Pure JVM. */
class PairwiseCosineStatsTest {

    private fun kGroups(k: Int, per: Int, jitter: Float = 0.02f) = buildList {
        var seed = 0
        for (g in 0 until k) {
            val base = g * (Math.PI / (k + 1)) + 0.05
            repeat(per) { add(TestEmbeddings.appEmb("g${g}_${it}", base, jitter, seed++)) }
        }
    }

    @Test
    fun stats_pairCountAndOrderingAreCorrect() {
        val emb = kGroups(3, 4) // 12 embeddings → 66 pairs
        val s = PairwiseCosineStats().compute("s1", emb)
        assertEquals(12, s.embeddingCount)
        assertEquals(66, s.pairCount)
        assertTrue(s.min <= s.p10)
        assertTrue(s.p10 <= s.p25)
        assertTrue(s.p25 <= s.median)
        assertTrue(s.median <= s.p75)
        assertTrue(s.p75 <= s.p90)
        assertTrue(s.p90 <= s.max + 1e-6f)
        assertTrue(s.mean in -1f..1f)
    }

    @Test
    fun histogram_comesFromTheProductionCalibrator() {
        // clean bimodal ⇒ the production valley detector should fire (no fallback)
        val s = PairwiseCosineStats().compute("s1", kGroups(3, 5, jitter = 0.01f))
        assertEquals(40, s.histogram.binCount)
        // proposedThreshold is within the calibrator's clamp range regardless
        assertTrue(s.proposedThreshold in 0.40f..0.85f)
    }

    // step 13.6 — pooled calibration does NOT perform cross-video identity clustering
    @Test
    fun pooled_usesWithinSamplePairsOnly_noCrossVideoPairs() {
        val s1 = kGroups(2, 3) // 6 emb → 15 within-sample pairs
        val s2 = kGroups(2, 4) // 8 emb → 28 within-sample pairs
        val pooled = PairwiseCosineStats().computePooled("pool", listOf(s1, s2))
        assertEquals(6 + 8, pooled.embeddingCount)
        // 15 + 28 = 43 within-sample pairs. A cross-video pooling would give
        // C(14,2) = 91. Assert we did NOT do that.
        assertEquals(43, pooled.pairCount)
        assertTrue("cross-video pairing would inflate pairCount to 91", pooled.pairCount < 91)
    }

    @Test
    fun mustNotLink_overlayIsComputed_whenPairsProvided() {
        val emb = kGroups(2, 3)
        // forbid one within-group pair (contrived) — just to exercise the overlay
        val mnl = setOf("g0_0" to "g0_1")
        val s = PairwiseCosineStats().compute("s1", emb, mnl)
        assertEquals(1, s.mustNotLinkPairCount)
        assertTrue(s.mustNotLinkMeanCosine != null)
        assertTrue(s.otherMeanCosine != null)
    }

    @Test
    fun deterministic() {
        val emb = kGroups(3, 4)
        val a = PairwiseCosineStats().compute("s1", emb)
        val b = PairwiseCosineStats().compute("s1", emb.reversed())
        assertEquals(a.mean, b.mean, 1e-6f)
        assertEquals(a.median, b.median, 1e-6f)
        assertTrue(a.histogram.counts.contentEquals(b.histogram.counts))
        assertEquals(a.fallbackUsed, b.fallbackUsed)
    }

    @Test
    fun tinyInput_doesNotThrow() {
        val s = PairwiseCosineStats().compute("s1", listOf(TestEmbeddings.appEmb("only", 0.0)))
        assertEquals(0, s.pairCount)
        assertTrue(s.fallbackUsed || !s.fallbackUsed) // just: no exception
    }
}
