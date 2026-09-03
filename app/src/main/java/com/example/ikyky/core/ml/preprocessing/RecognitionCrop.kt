package com.example.ikyky.core.ml.preprocessing

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.BoundingBox

/**
 * Pure geometry for the **recognition crop** — the focused, margin-expanded box
 * around a detected face that is fed (after alignment + resize) to the embedding
 * model. No Android types, so it is unit-testable on the JVM.
 *
 * This is deliberately separate from the *presentation* crop used later for the
 * collage, which is far more generous. "Do not crop tightly to the detected face
 * bounding box" — the margins here add context; clamping keeps the rect inside
 * the canonical frame; [isUsable] rejects rectangles that collapsed against an
 * edge or came from a degenerate detection.
 */
object RecognitionCrop {

    /**
     * @param face   detection box in **canonical** (upright, full-res) pixels
     * @param frameWidth  canonical frame width
     * @param frameHeight canonical frame height
     * @param marginH fraction of box width added on the left AND right
     * @param marginV fraction of box height added on the top AND bottom
     * @return the expanded rectangle clamped to `[0,frameWidth] x [0,frameHeight]`.
     *         May be smaller than requested (or degenerate) near an edge — check
     *         [isUsable] before using it.
     */
    fun expandAndClamp(
        face: BoundingBox,
        frameWidth: Int,
        frameHeight: Int,
        marginH: Float = PipelineDefaults.FACE_CROP_MARGIN_HORIZONTAL,
        marginV: Float = PipelineDefaults.FACE_CROP_MARGIN_VERTICAL,
    ): BoundingBox {
        // Normalise the incoming box first (ML Kit has been seen to emit l>r).
        val l0 = minOf(face.left, face.right)
        val r0 = maxOf(face.left, face.right)
        val t0 = minOf(face.top, face.bottom)
        val b0 = maxOf(face.top, face.bottom)

        val w = (r0 - l0).coerceAtLeast(0)
        val h = (b0 - t0).coerceAtLeast(0)
        val dx = (w * marginH).toInt()
        val dy = (h * marginV).toInt()

        val safeW = frameWidth.coerceAtLeast(0)
        val safeH = frameHeight.coerceAtLeast(0)

        val left = (l0 - dx).coerceIn(0, safeW)
        val top = (t0 - dy).coerceIn(0, safeH)
        val right = (r0 + dx).coerceIn(0, safeW)
        val bottom = (b0 + dy).coerceIn(0, safeH)
        return BoundingBox(left, top, right, bottom)
    }

    /**
     * True if [crop] is a sane rectangle to actually cut a bitmap from: positive
     * area and a shorter edge of at least [minShortEdgePx]. Guards against
     * near-edge collapse and degenerate detections.
     */
    fun isUsable(
        crop: BoundingBox,
        minShortEdgePx: Int = PipelineDefaults.MIN_RECOGNITION_CROP_PX,
    ): Boolean {
        val w = crop.right - crop.left
        val h = crop.bottom - crop.top
        if (w <= 0 || h <= 0) return false
        return minOf(w, h) >= minShortEdgePx
    }
}
