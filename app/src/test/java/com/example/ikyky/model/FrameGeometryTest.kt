package com.example.ikyky.model

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FrameGeometry
import com.example.ikyky.core.model.Landmark
import com.example.ikyky.core.model.LandmarkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGeometryTest {

    @Test
    fun noTransform_returnsBoxUnchanged_clampedToBounds() {
        val g = FrameGeometry.noTransform(1080, 1920)
        val b = BoundingBox(100, 200, 300, 500)
        assertEquals(b, g.toCanonical(b))
    }

    @Test
    fun downscaledFrame_boxMapsBackToFullRes() {
        // upright 1080x1920, decoded at 720 longer edge → scale 0.375
        val g = FrameGeometry(
            decodedWidth = 405,
            decodedHeight = 720,
            uprightWidth = 1080,
            uprightHeight = 1920,
            scale = 720f / 1920f,
        )
        val decodedBox = BoundingBox(100, 200, 200, 320) // in 405x720 space
        val canonical = g.toCanonical(decodedBox)
        // inverse scale ~2.667
        assertTrue("left ${canonical.left}", kotlin.math.abs(canonical.left - 266) <= 2)
        assertTrue("top ${canonical.top}", kotlin.math.abs(canonical.top - 533) <= 2)
        assertTrue("right ${canonical.right}", kotlin.math.abs(canonical.right - 533) <= 2)
        assertTrue("bottom ${canonical.bottom}", kotlin.math.abs(canonical.bottom - 853) <= 2)
        assertTrue(canonical.right <= 1080 && canonical.bottom <= 1920)
    }

    @Test
    fun landmark_mapsBackWithSameScale() {
        val g = FrameGeometry(405, 720, 1080, 1920, 720f / 1920f)
        val lm = Landmark(LandmarkType.LEFT_EYE, 150f, 150f)
        val c = g.toCanonical(lm)
        assertEquals(400f, c.x, 1f)
        assertEquals(400f, c.y, 1f)
    }

    @Test
    fun canonicalBox_isClampedIntoFrame() {
        val g = FrameGeometry(1080, 1920, 1080, 1920, 1f)
        val outOfBounds = BoundingBox(-50, -30, 1200, 2000)
        val c = g.toCanonical(outOfBounds)
        assertEquals(0, c.left)
        assertEquals(0, c.top)
        assertEquals(1080, c.right)
        assertEquals(1920, c.bottom)
    }
}
