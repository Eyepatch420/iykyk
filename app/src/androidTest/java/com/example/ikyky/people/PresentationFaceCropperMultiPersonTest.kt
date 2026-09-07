package com.example.ikyky.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.storage.impl.CacheRepresentativeImageStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 8.1, Steps 4/11 — the multi-face regression test that exists
 * specifically to prevent the old full-frame presentation-crop behavior from
 * returning. Builds a synthetic source frame containing several
 * distinctly-colored "faces" at known positions and asserts each person's own
 * [PresentationFaceCropper] output contains ONLY that person's marker color and
 * none of the others'.
 */
@RunWith(AndroidJUnit4::class)
class PresentationFaceCropperMultiPersonTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val frameW = 1080
    private val frameH = 1920

    /** Distinct solid-color squares standing in for faces; colors never blend. */
    private fun markerColor(index: Int): Int =
        when (index) {
            0 -> Color.RED
            1 -> Color.GREEN
            else -> Color.BLUE
        }

    private fun buildSyntheticFrame(boxes: List<BoundingBox>): Bitmap {
        val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        boxes.forEachIndexed { i, box ->
            val paint = Paint().apply { color = markerColor(i) }
            canvas.drawRect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), paint)
        }
        return bmp
    }

    private fun colorsPresent(bitmap: Bitmap): Set<Int> {
        val colors = HashSet<Int>()
        val stepX = maxOf(1, bitmap.width / 60)
        val stepY = maxOf(1, bitmap.height / 60)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                colors += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return colors
    }

    // Person A left, Person B right -- verify crop(A) contains A's color but not
    // B's, and vice versa. This is the direct regression test for the
    // "collage tile contains two people" bug.
    @Test
    fun twoPeopleSideBySide_eachCropContainsOnlyItsOwnPerson() {
        val boxA = BoundingBox(100, 700, 350, 1050)
        val boxB = BoundingBox(600, 700, 850, 1050)
        val frame = buildSyntheticFrame(listOf(boxA, boxB))

        val cropA = PresentationFaceCropper.crop(frame, boxA)!!
        val cropB = PresentationFaceCropper.crop(frame, boxB)!!

        val colorsA = colorsPresent(cropA)
        val colorsB = colorsPresent(cropB)

        assertTrue("crop(A) must contain A's marker color", markerColor(0) in colorsA)
        assertTrue("crop(A) must NOT contain B's marker color", markerColor(1) !in colorsA)
        assertTrue("crop(B) must contain B's marker color", markerColor(1) in colorsB)
        assertTrue("crop(B) must NOT contain A's marker color", markerColor(0) !in colorsB)

        cropA.recycle(); cropB.recycle(); frame.recycle()
    }

    @Test
    fun threePeopleInOneFrame_eachObservationProducesItsOwnCleanCrop() {
        val boxA = BoundingBox(30, 700, 260, 1050)
        val boxB = BoundingBox(430, 700, 660, 1050)
        val boxC = BoundingBox(830, 700, 1060, 1050)
        val frame = buildSyntheticFrame(listOf(boxA, boxB, boxC))

        val crops = listOf(boxA, boxB, boxC).mapIndexed { i, box ->
            i to PresentationFaceCropper.crop(frame, box)!!
        }

        for ((i, crop) in crops) {
            val present = colorsPresent(crop)
            assertTrue("crop($i) must contain its own marker color", markerColor(i) in present)
            for (other in 0..2) {
                if (other != i) {
                    assertTrue("crop($i) must NOT contain marker color of person $other", markerColor(other) !in present)
                }
            }
        }

        crops.forEach { it.second.recycle() }
        frame.recycle()
    }

    @Test
    fun invalidBoundingBox_returnsNull_callerMustHandleFallbackExplicitly() {
        val frame = buildSyntheticFrame(emptyList())
        val degenerate = BoundingBox(500, 500, 500, 500)
        val result = PresentationFaceCropper.crop(frame, degenerate)
        assertNull("a degenerate box must not silently produce a crop", result)
        frame.recycle()
    }

    @Test
    fun edgeOfFramePerson_clampsSafelyAndStillProducesACrop() {
        val nearEdge = BoundingBox(frameW - 200, 800, frameW - 10, 1100)
        val frame = buildSyntheticFrame(listOf(nearEdge))
        val crop = PresentationFaceCropper.crop(frame, nearEdge)
        assertTrue("edge-of-frame person must still produce a usable crop", crop != null)
        crop!!.recycle()
        frame.recycle()
    }

    @Test
    fun crop_preservesAspectRatio_neverStretchesToSquare() {
        val tallBox = BoundingBox(400, 300, 550, 900) // 150 x 600, tall and narrow
        val frame = buildSyntheticFrame(listOf(tallBox))
        val crop = PresentationFaceCropper.crop(frame, tallBox)!!
        assertTrue("a tall source box must not be forced square", crop.height > crop.width)
        crop.recycle()
        frame.recycle()
    }

    // Storage/reload + bitmap lifecycle: persist a crop, reload it from disk,
    // and confirm the reloaded bitmap matches without ever retaining the
    // original past its use.
    @Test
    fun crop_survivesStorageRoundTrip() = runBlocking {
        val storage = CacheRepresentativeImageStorage(context, StandardDispatcherProvider())
        val sessionId = "presentation_crop_roundtrip"
        try {
            val boxA = BoundingBox(100, 700, 350, 1050)
            val frame = buildSyntheticFrame(listOf(boxA))
            val crop = PresentationFaceCropper.crop(frame, boxA)!!
            val expectedW = crop.width
            val expectedH = crop.height

            val saved = storage.save(sessionId, "person_a", crop)
            crop.recycle()
            frame.recycle()

            assertTrue(saved is AppResult.Success)
            val uri = (saved as AppResult.Success).value
            val file = File(uri.path!!)
            assertTrue("persisted crop file must exist", file.exists())
            assertTrue("persisted crop file must be non-empty", file.length() > 0)

            val reloaded = BitmapFactory.decodeFile(file.path)
            assertEquals(expectedW, reloaded.width)
            assertEquals(expectedH, reloaded.height)
            reloaded.recycle()
        } finally {
            storage.clear(sessionId)
        }
    }
}
