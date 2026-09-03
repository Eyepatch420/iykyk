package com.example.ikyky.identity

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.features.people.domain.usecase.MustNotLinkBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MustNotLinkBuilderTest {

    private fun box(cx: Int, cy: Int, s: Int = 200) =
        BoundingBox(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)

    @Test
    fun twoDifferentFacesInSameFrame_produceAnEdge() {
        val a = TestEmbeddings.candidate(
            "a", 1L,
            (0..4).map { TestEmbeddings.ref("a$it", it * 250L, box(300, 400)) },
        )
        val b = TestEmbeddings.candidate(
            "b", 2L,
            (0..4).map { TestEmbeddings.ref("b$it", it * 250L, box(800, 400)) }, // far apart, low IoU
        )
        val edges = MustNotLinkBuilder().build(listOf(a, b))
        assertEquals(1, edges.size)
        assertEquals("a" to "b", edges.single().key)
        assertTrue(edges.single().iou <= 0.30f)
    }

    @Test
    fun sameFaceDetectedTwice_highIou_isNotAnEdge() {
        val a = TestEmbeddings.candidate(
            "a", 1L, (0..4).map { TestEmbeddings.ref("a$it", it * 250L, box(500, 500)) },
        )
        val b = TestEmbeddings.candidate(
            "b", 2L, (0..4).map { TestEmbeddings.ref("b$it", it * 250L, box(508, 505)) }, // heavy overlap
        )
        val edges = MustNotLinkBuilder().build(listOf(a, b))
        assertTrue("high-IoU concurrent boxes are the same face, not a constraint", edges.isEmpty())
    }

    @Test
    fun nonOverlappingInTime_noEdge_evenIfDifferentFaces() {
        val a = TestEmbeddings.candidate(
            "a", 1L, (0..4).map { TestEmbeddings.ref("a$it", it * 250L, box(300, 400)) },
        )
        val b = TestEmbeddings.candidate(
            "b", 2L, (20..24).map { TestEmbeddings.ref("b$it", it * 250L, box(800, 400)) },
        )
        assertTrue(MustNotLinkBuilder().build(listOf(a, b)).isEmpty())
    }

    @Test
    fun overlappingTimeRangesButNoConcurrentObservation_noEdge() {
        // a at frames 0,2,4 ; b at frames 1,3,5 — ranges overlap, no shared frame index
        val a = TestEmbeddings.candidate(
            "a", 1L, listOf(0, 2, 4).map { TestEmbeddings.ref("a$it", it * 250L, box(300, 400)) },
        )
        val b = TestEmbeddings.candidate(
            "b", 2L, listOf(1, 3, 5).map { TestEmbeddings.ref("b$it", it * 250L, box(800, 400)) },
        )
        assertTrue(
            "must-not-link needs observation-level simultaneity, not just range overlap",
            MustNotLinkBuilder(frameTolerance = 0).build(listOf(a, b)).isEmpty(),
        )
    }

    @Test
    fun deterministic_sortedByIds() {
        val a = TestEmbeddings.candidate("z", 1L, (0..4).map { TestEmbeddings.ref("z$it", it * 250L, box(300, 400)) })
        val b = TestEmbeddings.candidate("m", 2L, (0..4).map { TestEmbeddings.ref("m$it", it * 250L, box(800, 400)) })
        val c = TestEmbeddings.candidate("a", 3L, (0..4).map { TestEmbeddings.ref("c$it", it * 250L, box(1300, 400)) })
        val edges = MustNotLinkBuilder().build(listOf(a, b, c))
        assertEquals(listOf("a" to "m", "a" to "z", "m" to "z"), edges.map { it.key })
    }
}
