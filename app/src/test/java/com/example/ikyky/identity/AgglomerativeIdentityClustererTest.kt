package com.example.ikyky.identity

import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgglomerativeIdentityClustererTest {

    private val clusterer = AgglomerativeIdentityClusterer()

    @Test
    fun threeTightGroups_becomeThreeClusters() {
        val embs =
            (0..2).map { TestEmbeddings.appEmb("a$it", 0.05, seed = it) } +
            (0..2).map { TestEmbeddings.appEmb("b$it", 1.0, seed = it + 10) } +
            (0..2).map { TestEmbeddings.appEmb("c$it", 2.0, seed = it + 20) }
        val out = clusterer.cluster(embs, threshold = 0.80f, mustNotLink = emptyList())
        assertEquals(3, out.clusters.size)
        // every appearance is grouped exactly once
        val grouped = out.clusters.flatMap { it.appearanceIds }.sorted()
        assertEquals(embs.map { it.appearanceId }.sorted(), grouped)
    }

    @Test
    fun highThreshold_keepsEverythingSeparate() {
        val embs = (0..4).map { TestEmbeddings.appEmb("a$it", 0.05, jitter = 0.05f, seed = it) }
        val out = clusterer.cluster(embs, threshold = 0.999f, mustNotLink = emptyList())
        assertEquals(5, out.clusters.size)
    }

    @Test
    fun lowThreshold_mergesEverything() {
        val embs =
            (0..2).map { TestEmbeddings.appEmb("a$it", 0.05, seed = it) } +
            (0..2).map { TestEmbeddings.appEmb("b$it", 0.8, seed = it + 10) }
        val out = clusterer.cluster(embs, threshold = -1f, mustNotLink = emptyList())
        assertEquals(1, out.clusters.size)
        assertEquals(6, out.clusters.single().appearanceIds.size)
    }

    @Test
    fun mustNotLink_overridesHighSimilarity() {
        // two near-identical embeddings that WOULD merge …
        val embs = listOf(
            TestEmbeddings.appEmb("a", 0.10, jitter = 0.001f, seed = 1),
            TestEmbeddings.appEmb("b", 0.10, jitter = 0.001f, seed = 2),
        )
        val plain = clusterer.cluster(embs, threshold = 0.5f, mustNotLink = emptyList())
        assertEquals("baseline: they merge", 1, plain.clusters.size)

        val mnl = listOf(MustNotLinkEdge("a", "b", frameIndex = 4, timestampMs = 1000, iou = 0.05f))
        val constrained = clusterer.cluster(embs, threshold = 0.5f, mustNotLink = mnl)
        assertEquals("constraint forces them apart", 2, constrained.clusters.size)
        assertEquals(1, constrained.mergesBlockedByMustNotLink)
    }

    @Test
    fun mustNotLink_isTransitive_viaAverageLinkage() {
        // a≈b≈c all similar, but a—c must-not-link. b may join at most one side.
        val embs = listOf(
            TestEmbeddings.appEmb("a", 0.10, jitter = 0.001f, seed = 1),
            TestEmbeddings.appEmb("b", 0.11, jitter = 0.001f, seed = 2),
            TestEmbeddings.appEmb("c", 0.10, jitter = 0.001f, seed = 3),
        )
        val mnl = listOf(MustNotLinkEdge("a", "c", 4, 1000, 0.05f))
        val out = clusterer.cluster(embs, threshold = 0.5f, mustNotLink = mnl)
        // a and c must be in different clusters; b lands with one of them
        val ca = out.clusters.first { "a" in it.appearanceIds }
        val cc = out.clusters.first { "c" in it.appearanceIds }
        assertTrue(ca.label != cc.label)
        assertTrue("b joined exactly one side", out.clusters.count { "b" in it.appearanceIds } == 1)
        assertEquals(2, out.clusters.size)
    }

    @Test
    fun preservesEveryAppearanceInstance_evenWhenMerged() {
        val embs = (0..5).map { TestEmbeddings.appEmb("dup$it", 0.1, jitter = 0.001f, seed = it) }
        val out = clusterer.cluster(embs, threshold = 0.5f, mustNotLink = emptyList())
        assertEquals(1, out.clusters.size)
        assertEquals(6, out.clusters.single().appearanceIds.size)
        assertEquals(embs.map { it.appearanceId }.sorted(), out.clusters.single().appearanceIds.sorted())
    }

    @Test
    fun deterministic_orderIndependent() {
        val embs =
            (0..2).map { TestEmbeddings.appEmb("a$it", 0.05, seed = it) } +
            (0..2).map { TestEmbeddings.appEmb("b$it", 1.0, seed = it + 10) }
        val a = clusterer.cluster(embs, 0.8f, emptyList())
        val b = clusterer.cluster(embs.reversed(), 0.8f, emptyList())
        assertEquals(
            a.clusters.map { it.appearanceIds.sorted() }.sortedBy { it.first() },
            b.clusters.map { it.appearanceIds.sorted() }.sortedBy { it.first() },
        )
    }

    @Test
    fun empty_returnsNoClusters() {
        val out = clusterer.cluster(emptyList(), 0.6f, emptyList())
        assertTrue(out.clusters.isEmpty())
    }
}
