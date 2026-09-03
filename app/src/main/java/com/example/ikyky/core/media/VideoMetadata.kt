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
    /**
     * Capture frame rate, or 0f when the container does not report one.
     *
     * Used by the Phase 6 shot scan, which must decode at the video's NATIVE
     * rate: a whip-pan in these clips lasts ~7 frames at 25 FPS, so a scan on the
     * 8 FPS sampling grid would step straight over it. Also converts sampled
     * frame indices into the scan's decoded-frame space for barrier queries.
     */
    val frameRate: Float = 0f,
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
