package com.example.ikyky.identity

import com.example.ikyky.features.people.domain.usecase.IntraAppearanceSplitter
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntraAppearanceSplitterTest {

    private val thetaA = 0.0
    private val thetaB = 1.2 // ~69° apart → clearly different identity

    private fun embSeq(
        appearanceId: String,
        thetas: List<Double>,
        startMs: Long = 0L,
        stepMs: Long = 250L,
    ): List<EmbeddedFaceObservation> = thetas.mapIndexed { i, th ->
        TestEmbeddings.embedded(
            appearanceId = appearanceId,
            obsId = "o$i",
            tsMs = startMs + i * stepMs,
            embedding = TestEmbeddings.vec(th, jitter = 0.01f, seed = i),
        )
    }

    @Test
    fun coherentAppearance_isNotSplit() {
        val emb = embSeq("a", List(8) { thetaA })
        val refs = emb.map { TestEmbeddings.ref(it.observationId, it.timestampMs) }
        val ap = TestEmbeddings.candidate("a", 1L, refs)

        val res = IntraAppearanceSplitter().split(listOf(ap), mapOf("a" to emb))
        assertEquals(1, res.appearances.size)
        assertTrue(res.records.isEmpty())
    }

    @Test
    fun twoIdentityHalves_areSplitAtTheBoundary() {
        // first 5 frames identity A, next 5 identity B
        val thetas = List(5) { thetaA } + List(5) { thetaB }
        val emb = embSeq("a", thetas)
        val refs = emb.map { TestEmbeddings.ref(it.observationId, it.timestampMs) }
        val ap = TestEmbeddings.candidate("a", 7L, refs)

        val res = IntraAppearanceSplitter().split(listOf(ap), mapOf("a" to emb))
        assertEquals("expected a split into 2", 2, res.appearances.size)
        assertEquals(1, res.records.size)
        val rec = res.records.single()
        assertEquals("a", rec.originalAppearanceId)
        assertEquals(2, rec.resultAppearanceIds.size)
        assertTrue("cut should be near t=1250ms", rec.cutTimestampsMs.single() in 1000L..1500L)
        assertTrue("separation should clear the margin", rec.separation >= 0.20f)

        // fragments keep contiguous, non-overlapping time spans covering the original
        val frags = res.appearances.sortedBy { it.startTimestampMs }
        assertEquals(0L, frags.first().startTimestampMs)
        assertEquals(refs.last().timestampMs, frags.last().endTimestampMs)
        assertTrue(frags[0].endTimestampMs < frags[1].startTimestampMs)
        // every observation is preserved across fragments
        val total = frags.sumOf { it.observationCount }
        assertEquals(refs.size, total)
    }

    @Test
    fun smallDrift_belowMargin_isNotSplit() {
        // 10° swing back and forth — pose variation, not identity
        val thetas = (0 until 10).map { if (it % 2 == 0) 0.0 else 0.17 }
        val emb = embSeq("a", thetas)
        val refs = emb.map { TestEmbeddings.ref(it.observationId, it.timestampMs) }
        val ap = TestEmbeddings.candidate("a", 1L, refs)

        val res = IntraAppearanceSplitter(minMargin = 0.20f).split(listOf(ap), mapOf("a" to emb))
        assertEquals(1, res.appearances.size)
    }

    @Test
    fun tooFewEmbeddings_isNeverSplit() {
        val emb = embSeq("a", listOf(thetaA, thetaB, thetaA)) // 3 < 2*minSide
        val refs = emb.map { TestEmbeddings.ref(it.observationId, it.timestampMs) }
        val ap = TestEmbeddings.candidate("a", 1L, refs)
        val res = IntraAppearanceSplitter(minSideEmbeddings = 2).split(listOf(ap), mapOf("a" to emb))
        assertEquals(1, res.appearances.size)
    }

    @Test
    fun deterministic_sameInputSameCuts() {
        val thetas = List(6) { thetaA } + List(6) { thetaB }
        val emb = embSeq("a", thetas)
        val refs = emb.map { TestEmbeddings.ref(it.observationId, it.timestampMs) }
        val ap = TestEmbeddings.candidate("a", 1L, refs)
        val r1 = IntraAppearanceSplitter().split(listOf(ap), mapOf("a" to emb))
        val r2 = IntraAppearanceSplitter().split(listOf(ap), mapOf("a" to emb))
        assertEquals(r1.appearances.map { it.id }, r2.appearances.map { it.id })
        assertEquals(r1.records.map { it.cutTimestampsMs }, r2.records.map { it.cutTimestampsMs })
    }
}
