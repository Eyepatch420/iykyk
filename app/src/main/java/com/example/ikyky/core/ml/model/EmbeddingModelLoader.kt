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

    /**
     * The mmap'd model file backing [interpreter]. Held so [close] can drop the
     * reference and let the GC unmap it — `Interpreter.close()` does NOT unmap a
     * buffer the caller supplied, so without this every fresh loader leaks a
     * ~5 MB mapping (Phase 6.1: this compounded with a leaked AssetFileDescriptor
     * to grow allocated native memory ~45 MB per pipeline run).
     */
    private var modelBuffer: MappedByteBuffer? = null

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
            modelBuffer = buffer
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
        // Drop the reference to the ~5 MB mmap so the GC/Cleaner can unmap it.
        // TFLite's Interpreter.close() does not touch a buffer we supplied.
        modelBuffer = null
    }

    /**
     * mmaps the model asset. The [AssetFileDescriptor] MUST be closed — it owns
     * an open fd plus native bookkeeping. Earlier this method returned from
     * inside a `FileInputStream(fd.fileDescriptor).use { }` block, which never
     * closed the AFD itself (only a stream wrapping the same fd), leaking one
     * descriptor per load.
     */
    private fun loadModelFile(): MappedByteBuffer =
        context.assets.openFd(spec.assetPath).use { afd ->
            // createInputStream() gives a stream that owns ITS OWN fd for this
            // slice, so closing it here does not disturb `afd`, which `use {}`
            // then closes exactly once.
            (afd.createInputStream() as FileInputStream).use { input ->
                input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.declaredLength,
                )
            }
        }
}
