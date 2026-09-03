package com.example.ikyky.embedding

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.usecase.AppearanceEmbeddingAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Phase 3 steps 25.10, 25.11 — appearance embedding aggregation + outlier handling. */
class AppearanceEmbeddingAggregatorTest {

    private fun member(vec: FloatArray, q: Float = 0.8f, id: String = "o") = EmbeddedFaceObservation(
        appearanceId = "app_1",
        trackletId = 1L,
        frameIndex = 0,
        timestampMs = 0,
        observationId = id,
        faceBox = BoundingBox(0, 0, 100, 100),
        qualityScore = q,
        embedding = FaceEmbedding.l2Normalized(vec),
        alignedByLandmarks = true,
    )

    @Test
    fun mean_ofConsistentMembers_isNormalizedAndClose() {
        val members = listOf(
            member(floatArrayOf(1f, 0.05f, 0f)),
            member(floatArrayOf(0.98f, 0.10f, 0.02f)),
            member(floatArrayOf(0.99f, 0f, -0.03f)),
        )
        val agg = AppearanceEmbeddingAggregator().aggregate("app_1", 1L, members)!!
        assertEquals(192.coerceAtMost(3), agg.dimension) // 3-d here
        var mag = 0f
        for (v in agg.embedding.vector) mag += v * v
        assertEquals(1f, sqrt(mag), 1e-5f)
        assertEquals(3, agg.memberCount)
        assertEquals(0, agg.rejectedOutliers)
        assertTrue("tight cluster ⇒ high self-similarity", agg.meanMemberSimilarity > 0.98f)
    }

    @Test
    fun singleOutlier_isDropped_notAllowedToDominate() {
        val good = List(4) { member(floatArrayOf(1f, 0.02f * it, 0f), id = "g$it") }
        val outlier = member(floatArrayOf(-1f, 0f, 0.1f), id = "bad") // opposite direction
        val agg = AppearanceEmbeddingAggregator().aggregate("app_1", 1L, good + outlier)!!
        assertEquals("outlier should be rejected", 1, agg.rejectedOutliers)
        assertEquals(4, agg.memberCount)
        // centroid should point with the good cluster (x ~ +1)
        assertTrue("centroid pulled to good cluster", agg.embedding.vector[0] > 0.9f)
    }

    @Test
    fun tooFewInliers_keepsAllRatherThanCollapsing() {
        // 2 members pointing opposite ways: neither is "the" inlier
        val members = listOf(
            member(floatArrayOf(1f, 0f, 0f), id = "a"),
            member(floatArrayOf(-1f, 0f, 0f), id = "b"),
        )
        val agg = AppearanceEmbeddingAggregator(minInliers = 2).aggregate("app_1", 1L, members)!!
        // can't drop to <2 → keep both
        assertEquals(2, agg.memberCount)
        assertEquals(0, agg.rejectedOutliers)
    }

    @Test
    fun singleMember_isReturnedAsIs() {
        val agg = AppearanceEmbeddingAggregator().aggregate(
            "app_1", 1L, listOf(member(floatArrayOf(0f, 1f, 0f))),
        )!!
        assertEquals(1, agg.memberCount)
        assertEquals(1f, agg.embedding.vector[1], 1e-6f)
    }

    @Test
    fun emptyMembers_returnsNull() {
        assertNull(AppearanceEmbeddingAggregator().aggregate("app_1", 1L, emptyList()))
    }
}
