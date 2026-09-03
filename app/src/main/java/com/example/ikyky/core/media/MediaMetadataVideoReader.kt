package com.example.ikyky.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import kotlinx.coroutines.withContext

/**
 * [VideoMetadataReader] backed by [MediaMetadataRetriever].
 *
 * All decode work runs on [DispatcherProvider.io]. The retriever is always
 * released. Every failure mode is mapped to [AppError.InvalidVideo] with a
 * specific message.
 */
class MediaMetadataVideoReader(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : VideoMetadataReader {

    override suspend fun read(uriString: String): AppResult<VideoMetadata> =
        withContext(dispatchers.io) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                ?: return@withContext fail("Malformed video URI")

            val retriever = MediaMetadataRetriever()
            try {
                try {
                    retriever.setDataSource(context, uri)
                } catch (t: Throwable) {
                    return@withContext fail("Could not open video source", t)
                }

                val hasVideo =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                if (!hasVideo) return@withContext fail("Selected file has no video track")

                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val rawWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val rawHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                    ?.let { ((it % 360) + 360) % 360 } ?: 0

                if (rawWidth <= 0 || rawHeight <= 0) {
                    return@withContext fail("Video dimensions are missing or invalid ($rawWidth x $rawHeight)")
                }
                if (durationMs <= 0L) {
                    return@withContext fail("Video duration is zero or unknown")
                }

                AppResult.Success(
                    VideoMetadata(
                        uriString = uriString,
                        durationMs = durationMs,
                        rawWidth = rawWidth,
                        rawHeight = rawHeight,
                        rotationDegrees = rotation,
                    )
                )
            } catch (t: Throwable) {
                fail("Could not read video metadata", t)
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun fail(message: String, cause: Throwable? = null): AppResult<VideoMetadata> =
        AppResult.Failure(AppError.InvalidVideo(message, cause))
}
