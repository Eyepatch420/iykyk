package com.example.ikyky.core.ml.preprocessing

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.extensions.cropOrNull
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace

/**
 * **TRACKER gate crop** — the margin-expanded bounding-box crop whose embedding
 * feeds the shot-aware tracker's appearance gate.
 *
 * This is the `expanded` alignment of the Python reference and is FROZEN as such
 * by Phase 5I: Option A keeps the tracker gating on the expanded crop, while
 * only *recognition* moves to the 5-point transform. Option B — gating on the
 * 5-point crop instead — was tested and **rejected**: it produced fewer valid
 * tracklets (22 vs 26) and collapsed must-not-link separation (+0.066 vs +0.159).
 *
 * ## Why this is a separate type from [RecognitionFaceCrop]
 *
 * The Phase 6 brief requires the two crops stay distinct concepts so neither can
 * be silently reused for the other. They answer different questions:
 *
 *  - **this** — "is this detection the same *thing* the track was following a
 *    moment ago?" Coarse, geometry-anchored, tolerant of pose.
 *  - [RecognitionFaceCrop] — "*who* is this?" Canonically aligned to the
 *    geometry MobileFaceNet was trained on.
 *
 * Gating with the recognition crop is also subtly circular: the gate would judge
 * with the very crop it aligns using, which inflates apparent intra-track
 * coherence while making genuinely different people harder to separate. That is
 * exactly what the Option B numbers showed.
 *
 * Geometry lives in [RecognitionCrop.expandAndClamp] (shared, pure, JVM-testable);
 * this type owns the bitmap extraction and the frozen margins.
 */
object TrackerAppearanceCrop {

    /** Margins frozen by Phase 5I §9.2: box + 0.30 h / 0.40 v, clamped, >= 24 px. */
    const val MARGIN_H: Float = PipelineDefaults.FACE_CROP_MARGIN_HORIZONTAL
    const val MARGIN_V: Float = PipelineDefaults.FACE_CROP_MARGIN_VERTICAL

    /** The expanded, clamped rectangle in canonical frame pixels. */
    fun rectFor(face: BoundingBox, frameWidth: Int, frameHeight: Int): BoundingBox =
        RecognitionCrop.expandAndClamp(
            face = face,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            marginH = MARGIN_H,
            marginV = MARGIN_V,
        )

    /**
     * The square gate crop for [face] within [frame], or null when the clamped
     * rectangle is unusable (face collapsed against a frame edge, or a
     * degenerate detection). A null means the gate simply has no embedding for
     * this observation — the tracker then falls back to geometry alone.
     */
    fun extract(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int = PipelineDefaults.EMBEDDING_INPUT_SIZE,
    ): Bitmap? {
        if (outputSize <= 0) return null
        val c = rectFor(face.boundingBox, frame.width, frame.height)
        if (!RecognitionCrop.isUsable(c)) return null
        val sub = frame.cropOrNull(Rect(c.left, c.top, c.right, c.bottom)) ?: return null
        return if (sub.width == outputSize && sub.height == outputSize) {
            sub
        } else {
            val scaled = Bitmap.createScaledBitmap(sub, outputSize, outputSize, true)
            if (scaled !== sub) sub.recycle()
            scaled
        }
    }
}
