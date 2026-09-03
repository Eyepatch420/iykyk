package com.example.ikyky.core.media

import android.media.MediaMetadataRetriever
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.VideoFrame
import kotlinx.coroutines.flow.Flow

/**
 * How the decoder chooses which frame to return for a requested timestamp.
 *
 *  - [CLOSEST_SYNC] snaps to the nearest keyframe — fast, but on clips with
 *    sparse sync frames many requested timestamps collapse onto the SAME
 *    keyframe (frozen-looking samples) and long stretches return nothing.
 *  - [CLOSEST] returns the frame nearest the requested time, decoding forward
 *    from the previous keyframe. Slower, but the samples actually track the
 *    timeline — required for correct appearance segmentation.
 */
enum class SeekOption(val mmrConstant: Int) {
    CLOSEST_SYNC(MediaMetadataRetriever.OPTION_CLOSEST_SYNC),
    CLOSEST(MediaMetadataRetriever.OPTION_CLOSEST),
}

/**
 * Request describing how to sample a video.
 */
data class FrameSamplingRequest(
    val uriString: String,
    val metadata: VideoMetadata,
    val fps: Float = PipelineDefaults.FRAME_SAMPLE_FPS,
    /** Longer edge to downscale decoded frames to (0 = no downscale). */
    val maxEdgePx: Int = PipelineDefaults.FRAME_DECODE_MAX_EDGE_PX,
    val seekOption: SeekOption = SeekOption.valueOf(PipelineDefaults.FRAME_SEEK_OPTION_NAME),
)

/**
 * Streams sampled, upright [VideoFrame]s out of a video, one at a time.
 *
 * A cold [Flow] is deliberate: the collector processes and releases each frame
 * before the next is decoded, so peak memory stays bounded regardless of video
 * length. Emissions are ordered by timestamp. The flow completes when the last
 * timestamp has been emitted and fails with
 * [com.example.ikyky.core.common.error.AppError] wrapped in an exception only if
 * decoding cannot start at all; individual un-decodable timestamps are skipped.
 */
interface VideoFrameExtractor {
    fun extractFrames(request: FrameSamplingRequest): Flow<VideoFrame>

    /** Number of timestamps [extractFrames] will attempt for this request. */
    fun plannedFrameCount(request: FrameSamplingRequest): Int

    /**
     * Decodes a **single** upright frame at (or nearest, per [seekOption])
     * [timestampMs], downscaled the same way [extractFrames] downscales
     * ([maxEdgePx]). Opens and releases its own retriever. Returns null if that
     * timestamp cannot be decoded.
     *
     * Used by later stages (embedding, collage) to re-fetch specific frames
     * chosen from the Phase-2 observations, without re-running the whole stream.
     * The caller owns the returned [VideoFrame.bitmap] and must recycle it.
     */
    suspend fun decodeFrameAt(
        uriString: String,
        metadata: VideoMetadata,
        timestampMs: Long,
        maxEdgePx: Int = PipelineDefaults.FRAME_DECODE_MAX_EDGE_PX,
        seekOption: SeekOption = SeekOption.valueOf(PipelineDefaults.FRAME_SEEK_OPTION_NAME),
    ): VideoFrame?
}
