package com.example.ikyky.collage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.features.collage.data.render.CanvasCollageRenderer
import com.example.ikyky.features.collage.domain.engine.AsymmetricLayoutEngine
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.engine.HeroLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MasonryLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MixedSizeLayoutEngine
import com.example.ikyky.features.collage.domain.engine.OverlapLayoutEngine
import com.example.ikyky.features.collage.domain.engine.ScrapbookLayoutEngine
import com.example.ikyky.features.collage.domain.engine.StaggeredLayoutEngine
import com.example.ikyky.features.collage.domain.model.CropMode
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.render.CollageTile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 — renders every representative template style at the brief's
 * required counts (3, 5, 10, 20, 30) end-to-end through the real
 * `android.graphics.Canvas` path, and checks the properties that can only be
 * verified against actual pixels: determinism, aspect-ratio preservation
 * (no stretching), and that overlapping/rotated slots don't crash the
 * renderer.
 */
@RunWith(AndroidJUnit4::class)
class CanvasCollageRendererTest {

    private fun renderer() = CanvasCollageRenderer(StandardDispatcherProvider())

    /** A solid-color bitmap standing in for a decoded representative crop. */
    private fun solidBitmap(w: Int, h: Int, color: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun tilesFor(template: LayoutTemplate): List<CollageTile> =
        template.slots.mapIndexed { i, _ ->
            // deliberately non-square source bitmaps to exercise aspect handling
            CollageTile("person_$i", solidBitmap(300, 450, Color.rgb(i * 17 % 255, 40, 200)), i)
        }

    @Test
    fun rendersEveryStyleAtEveryRequiredCount_withoutCrashing() = runBlocking {
        val engines = listOf(
            GridLayoutEngine(), HeroLayoutEngine(), AsymmetricLayoutEngine(),
            StaggeredLayoutEngine(), MasonryLayoutEngine(), OverlapLayoutEngine(),
            ScrapbookLayoutEngine(), MixedSizeLayoutEngine(),
        )
        val counts = listOf(3, 5, 10, 20, 30)
        val r = renderer()

        for (engine in engines) {
            for (n in counts) {
                if (n !in engine.supportedPeopleRange) continue
                val template = (
                    engine.buildTemplate(LayoutConfiguration(n, 1080, 1350)) as AppResult.Success
                    ).value
                val tiles = tilesFor(template)
                val result = r.render(template, tiles, 1080, 1350)
                assertTrue(
                    "${engine::class.simpleName} failed to render $n people: " +
                        (result as? AppResult.Failure)?.error?.message,
                    result is AppResult.Success,
                )
                val bitmap = (result as AppResult.Success).value
                assertEquals(1080, bitmap.width)
                assertEquals(1350, bitmap.height)
                bitmap.recycle()
                for (t in tiles) t.bitmap.recycle()
            }
        }
    }

    @Test
    fun deterministicOutput_sameTemplateSameTilesSameSize_pixelIdentical() = runBlocking {
        val engine = GridLayoutEngine()
        val template = (engine.buildTemplate(LayoutConfiguration(5, 400, 500)) as AppResult.Success).value
        val tiles = tilesFor(template)
        val r = renderer()

        val a = (r.render(template, tiles, 400, 500) as AppResult.Success).value
        val b = (r.render(template, tiles, 400, 500) as AppResult.Success).value

        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var mismatches = 0
        for (x in 0 until a.width step 10) {
            for (y in 0 until a.height step 10) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) mismatches++
            }
        }
        assertEquals("rendering must be pixel-deterministic", 0, mismatches)

        a.recycle(); b.recycle()
        for (t in tiles) t.bitmap.recycle()
    }

    @Test
    fun centerCrop_fillsTheSlot_noLetterboxing() = runBlocking {
        // A single slot covering the whole canvas, CENTER_CROP, filled with a
        // solid-color bitmap of a different aspect ratio than the canvas. Every
        // sampled pixel must be that color -- if the fill left any letterbox
        // background showing through, some samples would be the background color.
        val slot = LayoutSlot(x = 0f, y = 0f, width = 1f, height = 1f, cropMode = CropMode.CENTER_CROP)
        val template = LayoutTemplate(id = "t", style = LayoutStyle.GRID, slots = listOf(slot), backgroundColor = 0xFF000000)
        val tile = CollageTile("p0", solidBitmap(200, 800, Color.WHITE), 0)

        val result = renderer().render(template, listOf(tile), 500, 500)
        val bitmap = (result as AppResult.Success).value
        for (x in intArrayOf(10, 250, 490)) {
            for (y in intArrayOf(10, 250, 490)) {
                assertEquals("pixel ($x,$y) was not filled -- CENTER_CROP left a gap", Color.WHITE, bitmap.getPixel(x, y))
            }
        }
        bitmap.recycle(); tile.bitmap.recycle()
    }

    @Test
    fun fit_preservesAspectRatio_leavesLetterboxRatherThanStretching() = runBlocking {
        // A wide (2:1) source into a square slot with FIT: the image must be
        // scaled down uniformly, so corners of the destination remain the
        // background color (letterboxed), proving no anisotropic stretch filled
        // the whole square.
        val slot = LayoutSlot(x = 0f, y = 0f, width = 1f, height = 1f, cropMode = CropMode.FIT)
        val template = LayoutTemplate(id = "t", style = LayoutStyle.GRID, slots = listOf(slot), backgroundColor = 0xFF000000)
        val tile = CollageTile("p0", solidBitmap(800, 200, Color.WHITE), 0)

        val result = renderer().render(template, listOf(tile), 400, 400)
        val bitmap = (result as AppResult.Success).value
        // corners must be background (letterboxed); center must be the image
        assertEquals(Color.BLACK, bitmap.getPixel(5, 5))
        assertEquals(Color.BLACK, bitmap.getPixel(395, 395))
        assertEquals(Color.WHITE, bitmap.getPixel(200, 200))
        bitmap.recycle(); tile.bitmap.recycle()
    }

    @Test
    fun rotatedSlot_doesNotCrash_andStaysWithinOutputBounds() = runBlocking {
        val slot = LayoutSlot(
            x = 0.25f, y = 0.25f, width = 0.5f, height = 0.5f,
            rotationDegrees = 30f, allowOverlap = true,
        )
        val template = LayoutTemplate(id = "t", style = LayoutStyle.SCRAPBOOK, slots = listOf(slot))
        val tile = CollageTile("p0", solidBitmap(100, 100, Color.RED), 0)

        val result = renderer().render(template, listOf(tile), 300, 300)
        assertTrue(result is AppResult.Success)
        (result as AppResult.Success).value.recycle()
        tile.bitmap.recycle()
    }

    @Test
    fun zIndexOrdering_higherZPaintsOnTopOfLowerZ() = runBlocking {
        // Two fully-overlapping slots, different colors, different zIndex.
        // The final pixel must be the HIGHER-z color, proving the renderer's
        // paint order (not incidental list order) determines the result.
        val back = LayoutSlot(x = 0.1f, y = 0.1f, width = 0.8f, height = 0.8f, zIndex = 0, allowOverlap = true)
        val front = LayoutSlot(x = 0.1f, y = 0.1f, width = 0.8f, height = 0.8f, zIndex = 1, allowOverlap = true)
        // list order deliberately reversed vs zIndex order
        val template = LayoutTemplate(id = "t", style = LayoutStyle.OVERLAP, slots = listOf(front, back))
        val tiles = listOf(
            CollageTile("front", solidBitmap(50, 50, Color.GREEN), 0),
            CollageTile("back", solidBitmap(50, 50, Color.BLUE), 1),
        )
        val result = renderer().render(template, tiles, 200, 200)
        val bitmap = (result as AppResult.Success).value
        assertEquals("higher zIndex (front, list-index 0) must paint last/on top", Color.GREEN, bitmap.getPixel(100, 100))
        bitmap.recycle()
        for (t in tiles) t.bitmap.recycle()
    }

    @Test
    fun missingTileForASlot_isSkippedRatherThanCrashing() = runBlocking {
        val engine = GridLayoutEngine()
        val template = (engine.buildTemplate(LayoutConfiguration(4, 400, 400)) as AppResult.Success).value
        // only supply tiles for 2 of the 4 slots
        val tiles = listOf(
            CollageTile("p0", solidBitmap(100, 100, Color.RED), 0),
            CollageTile("p1", solidBitmap(100, 100, Color.BLUE), 1),
        )
        val result = renderer().render(template, tiles, 400, 400)
        assertTrue(result is AppResult.Success)
        (result as AppResult.Success).value.recycle()
        for (t in tiles) t.bitmap.recycle()
    }
}
