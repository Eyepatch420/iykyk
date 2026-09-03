package com.example.ikyky.core.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.model.PreprocessedFace

/**
 * Infrastructure-level checks that prove the ML stack can initialize on this
 * device. This is NOT the app pipeline — it only answers "can we load and run
 * the ML components?". Exercised by the instrumented test
 * `MlInfrastructureSmokeTest` and callable from a debug screen if desired.
 */
class MlSmokeTest(private val context: Context) {

    data class Report(
        val modelAssetExists: Boolean,
        val modelLoaded: Boolean,
        val runtimeInitialized: Boolean,
        val dummyInferenceRan: Boolean,
        val embeddingDimension: Int,
        val embeddingFinite: Boolean,
        val mlKitDetectorInitialized: Boolean,
        val notes: List<String>,
    ) {
        val allPassed: Boolean
            get() = modelAssetExists && modelLoaded && runtimeInitialized &&
                dummyInferenceRan && embeddingFinite && mlKitDetectorInitialized
    }

    suspend fun run(): Report {
        val notes = mutableListOf<String>()
        val spec = ModelSpec.MOBILE_FACE_NET
        val loader = EmbeddingModelLoader(context, spec)

        val assetExists = loader.modelAssetExists()
        if (!assetExists) notes += "Model asset '${spec.assetPath}' not found."

        var loaded = false
        var runtimeOk = false
        when (val r = loader.getOrLoad()) {
            is AppResult.Success -> { loaded = true; runtimeOk = true }
            is AppResult.Failure -> notes += "Model load failed: ${r.error.message}"
        }

        var inferenceRan = false
        var finite = false
        var dim = spec.outputDimension
        if (loaded) {
            val embedder = LiteRtFaceEmbedder(loader, spec)
            val dummy = Bitmap.createBitmap(spec.inputWidth, spec.inputHeight, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.rgb(128, 128, 128)) }
            when (val e = embedder.embed(PreprocessedFace(dummy, spec.inputWidth))) {
                is AppResult.Success -> {
                    inferenceRan = true
                    dim = e.value.dimension
                    finite = e.value.isFinite()
                    if (dim != spec.outputDimension) {
                        notes += "Unexpected embedding dim: got $dim, expected ${spec.outputDimension}."
                    }
                }
                is AppResult.Failure -> notes += "Dummy inference failed: ${e.error.message}"
            }
            embedder.close()
        }

        var detectorOk = false
        try {
            val detector = MlKitFaceDetector()
            detector.close()
            detectorOk = true
        } catch (t: Throwable) {
            notes += "ML Kit detector init failed: ${t.message}"
        }

        return Report(
            modelAssetExists = assetExists,
            modelLoaded = loaded,
            runtimeInitialized = runtimeOk,
            dummyInferenceRan = inferenceRan,
            embeddingDimension = dim,
            embeddingFinite = finite,
            mlKitDetectorInitialized = detectorOk,
            notes = notes,
        )
    }
}
