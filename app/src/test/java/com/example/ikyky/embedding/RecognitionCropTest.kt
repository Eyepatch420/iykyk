package com.example.ikyky.embedding

import com.example.ikyky.core.ml.preprocessing.RecognitionCrop
import com.example.ikyky.core.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the recognition-crop geometry (Phase 3 steps 6, 25.7–9). */
class RecognitionCropTest {

    private val frameW = 1080
    private val frameH = 1920

    // 7. crop expansion — margins actually grow the box
    @Test
    fun expand_addsMarginOnEverySide() {
        val face = BoundingBox(400, 700, 600, 1000) // 200 x 300, centred
        val c = RecognitionCrop.expandAndClamp(face, frameW, frameH, marginH = 0.25f, marginV = 0.25f)
        // dx = 200*0.25 = 50, dy = 300*0.25 = 75
        assertEquals(350, c.left)
        assertEquals(625, c.top)
        assertEquals(650, c.right)
        assertEquals(1075, c.bottom)
        assertTrue(RecognitionCrop.isUsable(c))
    }

    // 8. crop clamping — near every edge the rect stays inside the frame
    @Test
    fun clamp_keepsRectInsideFrame_nearEachEdge() {
        val topLeft = RecognitionCrop.expandAndClamp(BoundingBox(0, 0, 120, 120), frameW, frameH)
        assertTrue(topLeft.left >= 0 && topLeft.top >= 0)
        val bottomRight = RecognitionCrop.expandAndClamp(
            BoundingBox(frameW - 120, frameH - 120, frameW, frameH), frameW, frameH,
        )
        assertTrue(bottomRight.right <= frameW && bottomRight.bottom <= frameH)
        // still a usable rectangle in both corners
        assertTrue(RecognitionCrop.isUsable(topLeft))
        assertTrue(RecognitionCrop.isUsable(bottomRight))
    }

    // 9. invalid crop handling — degenerate / off-frame inputs are rejected, not crashed
    @Test
    fun invalidInputs_areRejected_notThrown() {
        // zero-area box
        val zero = RecognitionCrop.expandAndClamp(BoundingBox(500, 500, 500, 500), frameW, frameH)
        assertFalse(RecognitionCrop.isUsable(zero))

        // box entirely outside the frame
        val outside = RecognitionCrop.expandAndClamp(
            BoundingBox(5000, 5000, 5200, 5200), frameW, frameH,
        )
        assertFalse(RecognitionCrop.isUsable(outside))

        // inverted coordinates (l>r, t>b) — normalised, still handled
        val inverted = RecognitionCrop.expandAndClamp(
            BoundingBox(600, 1000, 400, 700), frameW, frameH, marginH = 0.1f, marginV = 0.1f,
        )
        assertTrue(inverted.right >= inverted.left && inverted.bottom >= inverted.top)

        // tiny sliver against the right edge → unusable
        val sliver = RecognitionCrop.expandAndClamp(
            BoundingBox(frameW - 4, 900, frameW, 1200), frameW, frameH, marginH = 0f, marginV = 0f,
        )
        assertFalse(RecognitionCrop.isUsable(sliver))
    }

    @Test
    fun isUsable_respectsMinShortEdge() {
        val c = BoundingBox(0, 0, 100, 20)
        assertFalse(RecognitionCrop.isUsable(c, minShortEdgePx = 24))
        assertTrue(RecognitionCrop.isUsable(c, minShortEdgePx = 16))
    }
}
