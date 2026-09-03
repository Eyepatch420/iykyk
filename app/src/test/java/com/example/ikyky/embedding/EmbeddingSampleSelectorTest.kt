package com.example.ikyky.embedding

import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.features.processing.domain.usecase.EmbeddingSampleSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 steps 25.12, 15, 16 — quality + temporal-diversity sample selection. */
class EmbeddingSampleSelectorTest {

    private fun ref(tsMs: Long, q: Float, id: String = "o$tsMs") = AppearanceObservationRef(
        observationId = id,
        trackletId = 1L,
        frameIndex = (tsMs / 250).toInt(),
        timestampMs = tsMs,
        canonicalBox = BoundingBox(0, 0, 100, 100),
        landmarks = emptyList(),
        qualityScore = q,
        usable = q >= 0.35f,
    )

    @Test
    fun fewerThanMax_returnsAll() {
        val obs = listOf(ref(0, 0.9f), ref(250, 0.8f), ref(500, 0.7f))
        val sel = EmbeddingSampleSelector(maxSamples = 5).select(obs)
        assertEquals(3, sel.size)
    }

    @Test
    fun picksAreTemporallySpread_notAdjacent() {
        // 21 observations over 5 s, all good quality
        val obs = (0..20).map { ref(it * 250L, 0.8f) }
        val sel = EmbeddingSampleSelector(maxSamples = 5).select(obs)
        assertEquals(5, sel.size)
        // sorted by time, and spanning most of the appearance
        val times = sel.map { it.timestampMs }
        assertEquals(times.sorted(), times)
        assertTrue("should reach into the last third", times.last() >= 3500)
        assertTrue("should start in the first third", times.first() <= 1500)
        // consecutive gaps should be well above the 250ms frame step
        val gaps = times.zipWithNext { a, b -> b - a }
        assertTrue("gaps too small: $gaps", gaps.all { it >= 750 })
    }

    @Test
    fun prefersHigherQualityWithinEachTimeBucket() {
        // two clusters in time; within each, one sharp + several blurry
        val obs = listOf(
            ref(0, 0.95f), ref(100, 0.4f), ref(200, 0.4f),
            ref(4800, 0.9f), ref(4900, 0.4f), ref(5000, 0.4f),
        ) + (1000L..4000L step 500).map { ref(it, 0.5f) }
        val sel = EmbeddingSampleSelector(maxSamples = 3).select(obs)
        assertTrue("expected the 0.95 sharp early frame", sel.any { it.timestampMs == 0L })
        assertTrue("expected the 0.9 sharp late frame", sel.any { it.timestampMs == 4800L })
    }

    @Test
    fun allLowQuality_stillReturnsSomething() {
        val obs = (0..30).map { ref(it * 250L, 0.10f) } // everything below the gate
        val sel = EmbeddingSampleSelector(maxSamples = 5, minSamples = 1).select(obs)
        assertTrue("a legitimate appearance must not vanish", sel.isNotEmpty())
        assertTrue(sel.size <= 5)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(EmbeddingSampleSelector().select(emptyList()).isEmpty())
    }
}
