package com.example.ikyky.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.model.PreprocessedFace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 3 real-model validation (steps 25 smoke + 26). Loads the actual
 * MobileFaceNet asset and runs a synthetic-but-real 112×112 face-like image
 * through the FULL embedder path (pixel pack → normalize → interpreter →
 * validate → L2). Asserts: dim 192, all finite, ‖v‖ ≈ 1, and that two different
 * inputs give different vectors (the model isn't a constant function).
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingModelSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun faceLike(seed: Int): Bitmap {
        val s = ModelSpec.MOBILE_FACE_NET.inputWidth
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(200, 180, 170))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // eyes
        p.color = Color.rgb(40, 30, 30)
        c.drawCircle(s * 0.35f, s * 0.4f + seed, s * 0.06f, p)
        c.drawCircle(s * 0.65f, s * 0.4f + seed, s * 0.06f, p)
        // nose + mouth
        p.color = Color.rgb(150, 120, 110)
        c.drawCircle(s * 0.5f, s * 0.58f, s * 0.05f, p)
        p.color = Color.rgb(120, 60, 60)
        c.drawRect(s * 0.35f, s * 0.72f, s * 0.65f, s * 0.78f + seed * 0.5f, p)
        return bmp
    }

    @Test
    fun realModel_fullPath_produces192dUnitVector() = runBlocking {
        val spec = ModelSpec.MOBILE_FACE_NET
        val loader = EmbeddingModelLoader(context, spec)
        assertTrue("model asset missing", loader.modelAssetExists())

        val embedder = LiteRtFaceEmbedder(loader, spec)
        try {
            val a = when (val r = embedder.embed(PreprocessedFace(faceLike(0), spec.inputWidth, alignedByLandmarks = true))) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> throw AssertionError("embed A failed: ${r.error.message}")
            }
            assertEquals(192, a.dimension)
            assertTrue("non-finite values in embedding", a.isFinite())
            var mag = 0f
            for (v in a.vector) mag += v * v
            assertEquals("‖embedding‖ must be ~1", 1f, sqrt(mag), 1e-3f)

            // different input → different vector (model is not degenerate)
            val b = when (val r = embedder.embed(PreprocessedFace(faceLike(9), spec.inputWidth))) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> throw AssertionError("embed B failed: ${r.error.message}")
            }
            val cos = a.cosineSimilarity(b)
            assertTrue("two different inputs gave an identical vector (cos=$cos)", abs(cos) < 0.999f)

            // same input twice → deterministic
            val a2 = (embedder.embed(PreprocessedFace(faceLike(0), spec.inputWidth)) as AppResult.Success).value
            assertTrue("inference not deterministic", a.cosineSimilarity(a2) > 0.999f)
        } finally {
            embedder.close()
        }
    }
}
