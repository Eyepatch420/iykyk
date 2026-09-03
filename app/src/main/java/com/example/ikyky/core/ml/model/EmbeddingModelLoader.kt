package com.example.ikyky.core.ml.model

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads a TFLite model asset once and hands out a reusable [Interpreter].
 *
 * The interpreter is created lazily on first request and cached; the pipeline
 * must reuse a single instance across every face rather than reloading per
 * inference. Not thread-safe for concurrent [Interpreter.run] calls — callers
 * serialize inference (the embedder does).
 */
class EmbeddingModelLoader(
    private val context: Context,
    private val spec: ModelSpec = ModelSpec.MOBILE_FACE_NET,
    private val numThreads: Int = 4,
) {

    @Volatile
    private var interpreter: Interpreter? = null

    /** True if the model asset is present and readable. Cheap; does not load it. */
    fun modelAssetExists(): Boolean =
        runCatching {
            context.assets.openFd(spec.assetPath).use { it.length > 0 }
        }.getOrDefault(false)

    @Synchronized
    fun getOrLoad(): AppResult<Interpreter> {
        interpreter?.let { return AppResult.Success(it) }
        return try {
            val buffer = loadModelFile()
            val options = Interpreter.Options().apply { numThreads = this@EmbeddingModelLoader.numThreads }
            val created = Interpreter(buffer, options)
            interpreter = created
            AppResult.Success(created)
        } catch (t: Throwable) {
            AppResult.Failure(
                AppError.ModelUnavailable(
                    "Failed to load embedding model '${spec.assetPath}'", t
                )
            )
        }
    }

    @Synchronized
    fun close() {
        runCatching { interpreter?.close() }
        interpreter = null
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fd: AssetFileDescriptor = context.assets.openFd(spec.assetPath)
        FileInputStream(fd.fileDescriptor).use { input ->
            val channel: FileChannel = input.channel
            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength,
            )
        }
    }
}
