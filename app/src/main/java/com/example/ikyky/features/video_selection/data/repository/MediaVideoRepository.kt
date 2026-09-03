package com.example.ikyky.features.video_selection.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.features.video_selection.domain.model.SelectedVideo
import com.example.ikyky.features.video_selection.domain.repository.VideoRepository
import kotlinx.coroutines.withContext

/**
 * Reads basic video metadata with [MediaMetadataRetriever] to validate a picked
 * URI. This is genuinely needed to hand a real [SelectedVideo] to Phase 2, and
 * it's cheap, so it's implemented now rather than stubbed.
 */
class MediaVideoRepository constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : VideoRepository {

    override suspend fun validateAndDescribe(uriString: String): AppResult<SelectedVideo> =
        withContext(dispatchers.io) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                ?: return@withContext AppResult.Failure(
                    AppError.InvalidVideo("Malformed video URI")
                )
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val hasVideo = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                if (!hasVideo) {
                    return@withContext AppResult.Failure(
                        AppError.InvalidVideo("Selected file has no video track")
                    )
                }
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

                AppResult.Success(
                    SelectedVideo(
                        uriString = uriString,
                        durationMs = duration,
                        width = width,
                        height = height,
                        rotationDegrees = rotation,
                    )
                )
            } catch (t: Throwable) {
                AppResult.Failure(AppError.InvalidVideo("Could not read video metadata", t))
            } finally {
                runCatching { retriever.release() }
            }
        }
}
