package com.example.ikyky.core.media

import com.example.ikyky.core.common.result.AppResult

/**
 * Framework-free description of the selected video. `MediaMetadataRetriever`
 * types never cross this boundary; the URI stays a String.
 */
data class VideoMetadata(
    val uriString: String,
    val durationMs: Long,
    /** Raw stored pixel size (before applying [rotationDegrees]). */
    val rawWidth: Int,
    val rawHeight: Int,
    /** 0 / 90 / 180 / 270 — how much the frame must be rotated to display upright. */
    val rotationDegrees: Int,
) {
    /** Width once the video is displayed upright. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 90) rawHeight else rawWidth

    /** Height once the video is displayed upright. */
    val displayHeight: Int get() = if (rotationDegrees % 180 == 90) rawWidth else rawHeight

    val isPortrait: Boolean get() = displayHeight >= displayWidth

    val hasUsableDimensions: Boolean get() = rawWidth > 0 && rawHeight > 0
    val hasUsableDuration: Boolean get() = durationMs > 0
}

/**
 * Reads basic metadata and validates that a URI points to a usable video.
 * Returns an explicit [com.example.ikyky.core.common.error.AppError.InvalidVideo]
 * for malformed URI / no video track / missing dimensions / zero duration /
 * decode failure.
 */
interface VideoMetadataReader {
    suspend fun read(uriString: String): AppResult<VideoMetadata>
}
