package com.example.ikyky.core.model

import android.graphics.Bitmap

/**
 * A single sampled, upright frame of the input video.
 *
 * Memory note: [bitmap] is a large object. The extractor emits these one at a
 * time via a cold Flow; the consumer must process a frame and let it be
 * collected before the next is decoded. Hundreds must never be held at once.
 *
 * [geometry] records the downscale (and, in principle, rotation) applied when
 * decoding, so detector output can be mapped back to canonical full-resolution
 * upright coordinates.
 */
data class VideoFrame(
    val index: Int,
    val timestampMs: Long,
    /** Width of [bitmap] as handed to the detector (may be downscaled). */
    val width: Int,
    /** Height of [bitmap] as handed to the detector (may be downscaled). */
    val height: Int,
    val bitmap: Bitmap,
    val geometry: FrameGeometry,
)
