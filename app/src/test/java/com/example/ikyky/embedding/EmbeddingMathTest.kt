package com.example.ikyky.embedding

import com.example.ikyky.core.model.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure-JVM tests for embedding vector math: pixel normalization formula,
 * L2 normalization, zero-norm handling, cosine similarity (Phase 3 steps
 * 25.1, 25.4–6).
 */
class EmbeddingMathTest {

    // 1. pixel normalization — the exact model formula (pixel - 127.5) / 127.5
    @Test
    fun pixelNormalization_mapsByteRangeToMinusOneToOne() {
        fun norm(c: Int) = (c - 127.5f) / 127.5f
        assertEquals(-1f, norm(0), 1e-6f)
        assertEquals(1f, norm(255), 1e-6f)
        assertEquals(0f, norm(128) - (0.5f / 127.5f), 1e-6f) // 128 → just above 0
        // whole range stays within [-1, 1]
        for (c in 0..255) {
            val v = norm(c)
            assertTrue("c=$c -> $v", v >= -1f && v <= 1f)
        }
    }

    // 4. L2 normalization — output magnitude == 1
    @Test
    fun l2Normalized_hasUnitMagnitude() {
        val raw = floatArrayOf(3f, 4f, 0f, 0f) // norm 5
        val e = FaceEmbedding.l2Normalized(raw)
        assertEquals(0.6f, e.vector[0], 1e-6f)
        assertEquals(0.8f, e.vector[1], 1e-6f)
        var mag = 0f
        for (v in e.vector) mag += v * v
        assertEquals(1f, sqrt(mag), 1e-5f)
    }

    // 5. zero-norm handling — no NaN, no divide-by-zero
    @Test
    fun l2Normalized_zeroVector_isReturnedNotNaN() {
        val e = FaceEmbedding.l2Normalized(FloatArray(8))
        assertTrue(e.vector.all { it == 0f })
        assertTrue(e.isFinite())
    }

    // 6. cosine similarity — identical, opposite, orthogonal
    @Test
    fun cosineSimilarity_knownAngles() {
        val a = FaceEmbedding.l2Normalized(floatArrayOf(1f, 0f, 0f))
        val same = FaceEmbedding.l2Normalized(floatArrayOf(2f, 0f, 0f))
        val opp = FaceEmbedding.l2Normalized(floatArrayOf(-1f, 0f, 0f))
        val orth = FaceEmbedding.l2Normalized(floatArrayOf(0f, 1f, 0f))

        assertEquals(1f, a.cosineSimilarity(same), 1e-6f)
        assertEquals(-1f, a.cosineSimilarity(opp), 1e-6f)
        assertEquals(0f, a.cosineSimilarity(orth), 1e-6f)

        // companion form normalizes defensively and agrees
        assertEquals(1f, FaceEmbedding.cosineSimilarity(a, same), 1e-6f)
        assertEquals(0f, FaceEmbedding.cosineSimilarity(FaceEmbedding(FloatArray(3)), a), 1e-6f)
    }

    @Test
    fun cosineSimilarity_isSymmetric_andBounded() {
        val a = FaceEmbedding.l2Normalized(floatArrayOf(0.2f, -0.5f, 0.83f, 0.1f))
        val b = FaceEmbedding.l2Normalized(floatArrayOf(-0.3f, 0.4f, 0.1f, 0.86f))
        val ab = a.cosineSimilarity(b)
        val ba = b.cosineSimilarity(a)
        assertEquals(ab, ba, 1e-6f)
        assertTrue(abs(ab) <= 1f + 1e-6f)
    }

    @Test
    fun centroid_ofNormalizedVectors_isNormalized() {
        val vs = listOf(
            FaceEmbedding.l2Normalized(floatArrayOf(1f, 0.1f, 0f)),
            FaceEmbedding.l2Normalized(floatArrayOf(0.9f, 0.2f, 0.05f)),
            FaceEmbedding.l2Normalized(floatArrayOf(0.95f, 0f, -0.05f)),
        )
        val c = FaceEmbedding.centroid(vs)
        var mag = 0f
        for (v in c.vector) mag += v * v
        assertEquals(1f, sqrt(mag), 1e-5f)
    }
}
