package com.example.ikyky.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.ml.MlSmokeTest
import com.example.ikyky.core.ml.model.ModelSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 infrastructure smoke test.
 *
 * Proves the ML stack initializes on-device — it does NOT exercise the app
 * pipeline. Run with:
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MlInfrastructureSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun ml_components_initialize_and_run() = runBlocking {
        val report = MlSmokeTest(context).run()

        assertTrue("Model asset missing: ${report.notes}", report.modelAssetExists)
        assertTrue("Model failed to load: ${report.notes}", report.modelLoaded)
        assertTrue("Runtime not initialized: ${report.notes}", report.runtimeInitialized)
        assertTrue("Dummy inference did not run: ${report.notes}", report.dummyInferenceRan)
        assertEquals(
            "Unexpected embedding dimension",
            ModelSpec.MOBILE_FACE_NET.outputDimension,
            report.embeddingDimension,
        )
        assertTrue("Embedding values not finite", report.embeddingFinite)
        assertTrue("ML Kit detector failed to init: ${report.notes}", report.mlKitDetectorInitialized)
        assertTrue("Overall smoke test failed: ${report.notes}", report.allPassed)
    }
}
