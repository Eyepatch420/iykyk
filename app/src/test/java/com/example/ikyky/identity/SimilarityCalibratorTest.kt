package com.example.ikyky.identity

import com.example.ikyky.features.people.domain.usecase.SimilarityCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityCalibratorTest {

    /** Build appearances forming K tight identity groups, well separated. */
    private fun groups(k: Int, perGroup: Int) = buildList {
        var seed = 0
        for (g in 0 until k) {
            val base = g * (Math.PI / (k + 1)) + 0.05
            repeat(perGroup) {
                add(TestEmbeddings.appEmb("g${g}_$it", base, jitter = 0.015f, seed = seed++))
            }
        }
    }

    @Test
    fun bimodalDistribution_picksValley_notFallback() {
        val embs = groups(k = 3, perGroup = 4) // strong intra vs inter separation
        val res = SimilarityCalibrator().calibrate(embs)
        assertFalse("should not fall back on a clean bimodal histogram", res.fallbackUsed)
        assertTrue("threshold between the modes", res.threshold in 0.2f..0.95f)
        assertTrue("valley below the high peak", (res.highPeakCosine ?: 1f) > res.threshold)
        assertTrue("valley above / near the low peak", (res.lowPeakCosine ?: -1f) <= res.threshold + 1e-3f)
        assertTrue("confident", res.confidence > 0f && !res.lowConfidence)
    }

    @Test
    fun noStructure_singleBlob_fallsBackLowConfidence() {
        // all appearances nearly identical direction → one mode only
        val embs = (0 until 10).map { TestEmbeddings.appEmb("a$it", 0.1, jitter = 0.005f, seed = it) }
        val res = SimilarityCalibrator(fallbackThreshold = 0.62f).calibrate(embs)
        assertTrue(res.fallbackUsed)
        assertTrue(res.lowConfidence)
        assertEquals(0.62f, res.threshold, 1e-6f)
        assertEquals(0f, res.confidence, 1e-6f)
    }

    @Test
    fun tooFewAppearances_fallsBack() {
        val res = SimilarityCalibrator().calibrate(listOf(TestEmbeddings.appEmb("a", 0.0)))
        assertTrue(res.fallbackUsed)
        assertEquals(0, res.histogram.totalPairs)
    }

    @Test
    fun thresholdIsClampedToConfiguredRange() {
        val embs = groups(k = 2, perGroup = 5)
        val res = SimilarityCalibrator(clampMin = 0.55f, clampMax = 0.60f).calibrate(embs)
        assertTrue(res.threshold in 0.55f..0.60f)
    }

    @Test
    fun sensitivitySweep_isReportedAroundThreshold_andMonotoneNonDecreasing() {
        val embs = groups(k = 3, perGroup = 4)
        var lastPeople = -1
        val res = SimilarityCalibrator().calibrate(embs) { t ->
            // fake clusterer: higher threshold ⇒ more (or equal) clusters
            val people = (t * 10).toInt().coerceAtLeast(1)
            people to 0
        }
        assertTrue(res.sensitivity.size >= 3)
        assertTrue(res.sensitivity.any { kotlin.math.abs(it.threshold - res.threshold) < 1e-3f })
        // thresholds ascending
        val ts = res.sensitivity.map { it.threshold }
        assertEquals(ts.sorted(), ts)
        // our fake person count is non-decreasing in threshold
        for (p in res.sensitivity) {
            assertTrue(p.personCount >= lastPeople)
            lastPeople = p.personCount
        }
    }

    @Test
    fun deterministic() {
        val embs = groups(k = 3, perGroup = 4)
        val a = SimilarityCalibrator().calibrate(embs)
        val b = SimilarityCalibrator().calibrate(embs)
        assertEquals(a.threshold, b.threshold, 1e-6f)
        assertEquals(a.fallbackUsed, b.fallbackUsed)
        assertTrue(a.histogram.counts.contentEquals(b.histogram.counts))
    }
}
