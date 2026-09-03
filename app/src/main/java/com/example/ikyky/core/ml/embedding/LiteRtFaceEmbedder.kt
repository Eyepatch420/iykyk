package com.example.ikyky.core.ml.embedding

import android.graphics.Bitmap
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.core.model.PreprocessedFace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LiteRT (TensorFlow Lite) implementation of [FaceEmbedder] for MobileFaceNet.
 *
 * Reuses one interpreter (via [EmbeddingModelLoader]) for every face. Inference
 * is serialized with a [Mutex] because a single TFLite interpreter is not
 * re-entrant. Pixel normalization to [-1, 1] lives here, next to the model.
 */
class LiteRtFaceEmbedder(
    private val loader: EmbeddingModelLoader,
    private val spec: ModelSpec = ModelSpec.MOBILE_FACE_NET,
) : FaceEmbedder {

    override val dimension: Int get() = spec.outputDimension

    private val inferenceLock = Mutex()

    // Reused across calls to avoid per-face allocation.
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(spec.inputElementCount * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
    private val outputBuffer: Array<FloatArray> = arrayOf(FloatArray(spec.outputDimension))

    override suspend fun embed(face: PreprocessedFace): AppResult<FaceEmbedding> {
        val interpreter = when (val r = loader.getOrLoad()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return r
        }
        return inferenceLock.withLock {
            try {
                writeInput(face.bitmap)
                outputBuffer[0].fill(0f)
                interpreter.run(inputBuffer, outputBuffer)
                validate(outputBuffer[0].copyOf())
            } catch (t: Throwable) {
                AppResult.Failure(AppError.MlInference("Face embedding inference failed", t))
            }
        }
    }

    /** Output validation (Phase 3 step 10): shape, finiteness, non-zero norm, then L2. */
    private fun validate(raw: FloatArray): AppResult<FaceEmbedding> {
        if (raw.size != spec.outputDimension) {
            return AppResult.Failure(
                AppError.MlInference(
                    "Embedding dimension ${raw.size}, expected ${spec.outputDimension}"
                )
            )
        }
        if (raw.any { !it.isFinite() }) {
            return AppResult.Failure(
                AppError.MlInference("Embedding produced NaN / infinite values")
            )
        }
        var norm = 0.0
        for (v in raw) norm += v.toDouble() * v
        if (norm <= 0.0 || !norm.isFinite()) {
            return AppResult.Failure(
                AppError.MlInference("Embedding has zero / invalid magnitude")
            )
        }
        val embedding = FaceEmbedding.l2Normalized(raw)
        return if (!embedding.isFinite()) {
            AppResult.Failure(AppError.MlInference("Normalized embedding is non-finite"))
        } else {
            AppResult.Success(embedding)
        }
    }

    override fun close() = loader.close()

    private fun writeInput(bitmap: Bitmap) {
        require(bitmap.width == spec.inputWidth && bitmap.height == spec.inputHeight) {
            "Preprocessed face must be ${spec.inputWidth}x${spec.inputHeight}, was ${bitmap.width}x${bitmap.height}"
        }
        inputBuffer.rewind()
        val pixels = IntArray(spec.inputWidth * spec.inputHeight)
        bitmap.getPixels(pixels, 0, spec.inputWidth, 0, 0, spec.inputWidth, spec.inputHeight)
        // NHWC, channel order R,G,B, normalized per the model spec — same layout
        // as InputTensorPacker.pack, written straight into the reused buffer.
        for (pixel in pixels) {
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)
            inputBuffer.putFloat(InputTensorPacker.normalize(r, spec.normalization))
            inputBuffer.putFloat(InputTensorPacker.normalize(g, spec.normalization))
            inputBuffer.putFloat(InputTensorPacker.normalize(b, spec.normalization))
        }
        inputBuffer.rewind()
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4
    }
}
