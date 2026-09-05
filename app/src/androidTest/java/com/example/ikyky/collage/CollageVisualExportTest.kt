package com.example.ikyky.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.features.collage.data.render.CanvasCollageRenderer
import com.example.ikyky.features.collage.domain.engine.AsymmetricLayoutEngine
import com.example.ikyky.features.collage.domain.engine.CollageTemplateRegistry
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.engine.HeroLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MasonryLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MixedSizeLayoutEngine
import com.example.ikyky.features.collage.domain.engine.OverlapLayoutEngine
import com.example.ikyky.features.collage.domain.engine.ScrapbookLayoutEngine
import com.example.ikyky.features.collage.domain.engine.StaggeredLayoutEngine
import com.example.ikyky.features.collage.domain.render.CollageTile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * NOT a correctness test — a visual-export harness for Phase 8's required
 * manual inspection (brief: "generate sample collages for 3/5/10/15/20/25/30
 * people ... inspect them visually"). Produces one PNG per (people count,
 * available style), pulled off-device via `additionalTestOutputDir` for
 * review. Uses synthetic face-like placeholder crops (a colored circle on a
 * neutral background) rather than the real ML pipeline so all 7 counts export
 * in seconds rather than ~10 minutes each.
 */
@RunWith(AndroidJUnit4::class)
class CollageVisualExportTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun outDir(): File {
        val fromRunner = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.let { File(it) }
        val dir = File(fromRunner ?: File(context.cacheDir, "collage_export"), "collage_export")
        dir.mkdirs()
        return dir
    }

    /** A distinct, face-like placeholder: colored background + a lighter circle. */
    private fun placeholderFace(index: Int, w: Int = 300, h: Int = 400): Bitmap {
        val hue = (index * 47) % 360
        val bg = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.85f))
        val fg = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.35f, 0.98f))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        canvas.drawCircle(w / 2f, h * 0.4f, w * 0.32f, paint) // "face"
        canvas.drawRoundRect(
            w * 0.2f, h * 0.62f, w * 0.8f, h * 0.95f, 20f, 20f, paint,
        ) // "shoulders"
        return bmp
    }

    @Test
    fun exportSampleCollagesForVisualInspection() = runBlocking {
        val registry = CollageTemplateRegistry(
            listOf(
                GridLayoutEngine(), HeroLayoutEngine(), AsymmetricLayoutEngine(),
                StaggeredLayoutEngine(), MasonryLayoutEngine(), OverlapLayoutEngine(),
                ScrapbookLayoutEngine(), MixedSizeLayoutEngine(),
            ),
        )
        val renderer = CanvasCollageRenderer(StandardDispatcherProvider())
        val counts = listOf(3, 5, 10, 15, 20, 25, 30)
        val outputW = 1080
        val outputH = 1350
        val dir = outDir()
        var exported = 0

        for (n in counts) {
            val templates = registry.templatesFor(n, outputW, outputH)
            assertTrue("no templates for $n people", templates.isNotEmpty())

            for (template in templates) {
                val tiles = template.slots.mapIndexed { i, _ ->
                    CollageTile("person_$i", placeholderFace(i), i)
                }
                val result = renderer.render(template, tiles, outputW, outputH)
                assertTrue("$n/${template.style} failed: ${(result as? AppResult.Failure)?.error?.message}", result is AppResult.Success)
                val bitmap = (result as AppResult.Success).value

                val file = File(dir, "n%02d_%s_%s.png".format(n, template.style.name.lowercase(), template.id))
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                exported++

                bitmap.recycle()
                for (t in tiles) t.bitmap.recycle()
            }
        }
        println("COLLAGE_EXPORT: wrote $exported collages to ${dir.absolutePath}")
        assertTrue(exported > 0)
    }
}
