package com.example.ikyky

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framework-free sanity checks for the Phase 1 core primitives. No device needed.
 */
class CoreLogicTest {

    @Test
    fun l2Normalized_hasUnitLength_andSelfSimilarityIsOne() {
        val e = FaceEmbedding.l2Normalized(floatArrayOf(3f, 4f, 0f, 0f))
        val len = kotlin.math.sqrt(e.vector.sumOf { (it * it).toDouble() }).toFloat()
        assertEquals(1f, len, 1e-4f)
        assertEquals(1f, e.cosineSimilarity(e), 1e-4f)
    }

    @Test
    fun boundingBox_iou_isCorrectForHalfOverlap() {
        val a = BoundingBox(0, 0, 10, 10)
        val b = BoundingBox(5, 0, 15, 10)
        // intersection 50, union 150
        assertEquals(50f / 150f, a.iou(b), 1e-4f)
    }

    @Test
    fun gridLayoutEngine_producesEnoughSlots_forVariousCounts() {
        val engine = GridLayoutEngine()
        for (n in intArrayOf(1, 2, 3, 5, 8, 12, 20, 30)) {
            val result = engine.buildTemplate(
                LayoutConfiguration(personCount = n, canvasWidth = 1080, canvasHeight = 1920)
            )
            assertTrue(result is AppResult.Success)
            val template = (result as AppResult.Success).value
            assertTrue("Need >= $n slots, got ${template.capacity}", template.capacity >= n)
            template.slots.forEach {
                assertTrue(it.x in 0f..1f && it.y in 0f..1f)
                assertTrue(it.width > 0f && it.height > 0f)
            }
        }
    }
}
