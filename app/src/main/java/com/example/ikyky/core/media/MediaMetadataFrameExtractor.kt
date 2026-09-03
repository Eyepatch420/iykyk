package com.example.ikyky.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.model.FrameGeometry
import com.example.ikyky.core.model.VideoFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [VideoFrameExtractor] using [MediaMetadataRetriever.getFrameAtTime].
 *
 * Design notes:
 *  - `getFrameAtTime` returns frames already rotated to display orientation, so
 *    emitted [VideoFrame]s are upright and their [FrameGeometry.rotationApplied]
 *    is 0.
 *  - Frames are uniformly downscaled so the longer edge is
 *    `request.maxEdgePx` (using `getScaledFrameAtTime` where available, else a
 *    post-decode `Bitmap.createScaledBitmap`). This bounds per-frame memory to
 *    roughly `maxEdge * maxEdge * 4` bytes.
 *  - The flow decodes lazily: exactly one frame is in flight at a time. The
 *    collector is expected to detect faces on it and drop the reference before
 *    requesting the next.
 *  - The retriever is opened once for the whole stream and released when the
 *    flow terminates (normally, on error, or on cancellation).
 *  - Individual timestamps that fail to decode are skipped, not fatal.
 */
class MediaMetadataFrameExtractor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : VideoFrameExtractor {

    override fun plannedFrameCount(request: FrameSamplingRequest): Int {
        val step = stepMs(request.fps)
        if (step <= 0L) return 0
        val duration = request.metadata.durationMs
        if (duration <= 0L) return 0
        // timestamps at 0, step, 2*step, ... < duration
        return ((duration - 1) / step).toInt() + 1
    }

    override fun extractFrames(request: FrameSamplingRequest): Flow<VideoFrame> = flow {
        val uri = runCatching { Uri.parse(request.uriString) }.getOrNull()
            ?: throw FrameExtractionException(
                AppError.FrameExtraction("Malformed video URI for frame extraction")
            )

        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (t: Throwable) {
                throw FrameExtractionException(
                    AppError.FrameExtraction("Could not open video for frame extraction", t)
                )
            }

            val step = stepMs(request.fps)
            if (step <= 0L) {
                throw FrameExtractionException(
                    AppError.FrameExtraction("Invalid sampling fps ${request.fps}")
                )
            }

            val uprightW = request.metadata.displayWidth
            val uprightH = request.metadata.displayHeight
            val scale = computeScale(uprightW, uprightH, request.maxEdgePx)
            val targetW = max(1, (uprightW * scale).roundToInt())
            val targetH = max(1, (uprightH * scale).roundToInt())

            val duration = request.metadata.durationMs
            val seekOption = request.seekOption.mmrConstant
            var index = 0
            var timeMs = 0L
            while (timeMs < duration) {
                currentCoroutineContext().ensureActive()

                val raw: Bitmap? = decodeFrame(retriever, timeMs * 1000L, targetW, targetH, seekOption)
                if (raw != null) {
                    val upright = ensureSize(raw, targetW, targetH)
                    val geometry = FrameGeometry(
                        decodedWidth = upright.width,
                        decodedHeight = upright.height,
                        uprightWidth = uprightW,
                        uprightHeight = uprightH,
                        scale = if (uprightW > 0) upright.width.toFloat() / uprightW else 1f,
                        rotationApplied = 0,
                    )
                    emit(
                        VideoFrame(
                            index = index,
                            timestampMs = timeMs,
                            width = upright.width,
                            height = upright.height,
                            bitmap = upright,
                            geometry = geometry,
                        )
                    )
                }
                index++
                timeMs += step
            }
        } finally {
            runCatching { retriever.release() }
        }
    }.flowOn(dispatchers.io)

    override suspend fun decodeFrameAt(
        uriString: String,
        metadata: VideoMetadata,
        timestampMs: Long,
        maxEdgePx: Int,
        seekOption: SeekOption,
    ): VideoFrame? = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(context, uri)
            } catch (t: Throwable) {
                return@withContext null
            }
            val uprightW = metadata.displayWidth
            val uprightH = metadata.displayHeight
            val scale = computeScale(uprightW, uprightH, maxEdgePx)
            val targetW = max(1, (uprightW * scale).roundToInt())
            val targetH = max(1, (uprightH * scale).roundToInt())
            val clamped = timestampMs.coerceIn(0L, (metadata.durationMs - 1).coerceAtLeast(0L))

            val raw = decodeFrame(retriever, clamped * 1000L, targetW, targetH, seekOption.mmrConstant)
                ?: return@withContext null
            val upright = ensureSize(raw, targetW, targetH)
            VideoFrame(
                index = 0,
                timestampMs = clamped,
                width = upright.width,
                height = upright.height,
                bitmap = upright,
                geometry = FrameGeometry(
                    decodedWidth = upright.width,
                    decodedHeight = upright.height,
                    uprightWidth = uprightW,
                    uprightHeight = uprightH,
                    scale = if (uprightW > 0) upright.width.toFloat() / uprightW else 1f,
                    rotationApplied = 0,
                ),
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        targetW: Int,
        targetH: Int,
        seekOption: Int,
    ): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, seekOption, targetW, targetH)
                ?: retriever.getFrameAtTime(timeUs, seekOption)
        } else {
            retriever.getFrameAtTime(timeUs, seekOption)
        }
    } catch (t: Throwable) {
        null
    }

    private fun ensureSize(bitmap: Bitmap, w: Int, h: Int): Bitmap {
        if (bitmap.width == w && bitmap.height == h) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun computeScale(w: Int, h: Int, maxEdgePx: Int): Float {
        if (maxEdgePx <= 0) return 1f
        val longer = max(w, h)
        if (longer <= maxEdgePx) return 1f
        return maxEdgePx.toFloat() / longer
    }

    private fun stepMs(fps: Float): Long =
        if (fps <= 0f) -1L else (1000f / fps).toLong().coerceAtLeast(1L)

    /** Carries an [AppError] out of the cold flow so the pipeline can classify it. */
    class FrameExtractionException(val appError: AppError) : Exception(appError.message)
}
