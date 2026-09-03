package com.example.ikyky.core.model

/**
 * Describes how a decoded/detection bitmap relates to the *upright* video frame,
 * so bounding boxes produced by ML Kit (in decoded-bitmap pixels) can be mapped
 * back to a single canonical coordinate space that every later stage (crop,
 * align, collage) agrees on.
 *
 * Contract used by the Phase 2 pipeline:
 *  - `MediaMetadataRetriever.getFrameAtTime` already returns frames rotated to
 *    their display orientation, so the decoded bitmap is upright and
 *    `rotationApplied` is 0. The field exists for completeness / future
 *    decoders that hand back un-rotated frames.
 *  - The decoded bitmap may be uniformly downscaled by [scale] (<= 1.0) from the
 *    upright frame for detection speed.
 *
 * "Canonical space" = the upright frame at its full resolution
 * ([uprightWidth] x [uprightHeight]).
 */
data class FrameGeometry(
    /** Size of the bitmap that was actually fed to the detector. */
    val decodedWidth: Int,
    val decodedHeight: Int,
    /** Size of the upright full-resolution frame (canonical space). */
    val uprightWidth: Int,
    val uprightHeight: Int,
    /** Uniform scale from upright space to decoded space (decoded = upright * scale). */
    val scale: Float,
    /** Degrees the decoder rotated the raw frame by to make it upright (0/90/180/270). */
    val rotationApplied: Int = 0,
) {
    /** Map a box in decoded-bitmap pixels to canonical (upright, full-res) pixels. */
    fun toCanonical(box: BoundingBox): BoundingBox {
        if (scale == 1f) return box.clampedTo(uprightWidth, uprightHeight)
        val inv = 1f / scale
        return BoundingBox(
            left = (box.left * inv).toInt(),
            top = (box.top * inv).toInt(),
            right = (box.right * inv).toInt(),
            bottom = (box.bottom * inv).toInt(),
        ).clampedTo(uprightWidth, uprightHeight)
    }

    /** Map a landmark in decoded-bitmap pixels to canonical pixels. */
    fun toCanonical(landmark: Landmark): Landmark {
        if (scale == 1f) return landmark
        val inv = 1f / scale
        return landmark.copy(x = landmark.x * inv, y = landmark.y * inv)
    }

    companion object {
        fun noTransform(width: Int, height: Int) =
            FrameGeometry(width, height, width, height, 1f, 0)
    }
}

private fun BoundingBox.clampedTo(w: Int, h: Int): BoundingBox =
    BoundingBox(
        left = left.coerceIn(0, w),
        top = top.coerceIn(0, h),
        right = right.coerceIn(0, w),
        bottom = bottom.coerceIn(0, h),
    )
