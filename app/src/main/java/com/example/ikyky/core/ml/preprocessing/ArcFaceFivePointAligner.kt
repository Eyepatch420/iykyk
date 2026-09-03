package com.example.ikyky.core.ml.preprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.extensions.cropOrNull
import com.example.ikyky.core.model.DetectedFace

/**
 * **RECOGNITION aligner** — applies the frozen ArcFace 5-point similarity
 * transform ([RecognitionFaceCrop]) and produces the square crop fed to
 * MobileFaceNet.
 *
 * Frozen by Phase 5I §9.2 (`recognition crop = arcface_5pt`). This REPLACES the
 * eye-line [SimilarityTransformFaceAligner] for recognition — Phase 5H showed
 * that eye-line geometry (0.38 inter-ocular / 0.38 eye row) is a *different*
 * template from the one MobileFaceNet was trained on, and is the weaker of the
 * two by a wide margin cross-shot.
 *
 * [SimilarityTransformFaceAligner] is retained only for the presentation
 * (collage) crop and for legacy call sites; it must never feed the embedder.
 *
 * ## Fallback
 *
 * When the five landmarks are absent or fail [RecognitionFaceCrop.landmarkOrderIsPlausible]
 * — including the case where the eye/mouth ordering looks mirrored — this falls
 * back to the **expanded box crop**, matching the Python reference's
 * `arcface_5pt_fallback` branch exactly. It never emits a mirrored crop.
 */
class ArcFaceFivePointAligner : FaceAligner {

    override fun align(frame: Bitmap, face: DetectedFace, outputSize: Int): Bitmap? =
        alignDetailed(frame, face, outputSize)?.bitmap

    override fun alignDetailed(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int,
    ): AlignResult? {
        if (outputSize <= 0) return null

        val transform = RecognitionFaceCrop.transformFor(face, outputSize)
        if (transform != null) {
            return AlignResult(warp(frame, transform, outputSize), alignedByLandmarks = true)
        }
        // arcface_5pt_fallback: expanded box crop, resized. Never a mirror.
        return expandedBoxFallback(frame, face, outputSize)
            ?.let { AlignResult(it, alignedByLandmarks = false) }
    }

    /**
     * Forward-warps [frame] into an `outputSize x outputSize` bitmap using the
     * source->destination [transform], with bilinear filtering (the Python
     * reference uses `cv2.INTER_LINEAR`).
     */
    private fun warp(
        frame: Bitmap,
        transform: RecognitionFaceCrop.SimilarityTransform,
        outputSize: Int,
    ): Bitmap {
        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // android.graphics.Matrix is 3x3 row-major; the third row stays [0,0,1]
        // because a similarity transform has no perspective component.
        val t = transform.toFloatArray()
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    t[0], t[1], t[2],
                    t[3], t[4], t[5],
                    0f, 0f, 1f,
                )
            )
        }
        canvas.drawBitmap(
            frame,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /** The `arcface_5pt_fallback` branch: [TrackerAppearanceCrop] geometry, resized. */
    private fun expandedBoxFallback(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int,
    ): Bitmap? {
        val c = RecognitionCrop.expandAndClamp(
            face = face.boundingBox,
            frameWidth = frame.width,
            frameHeight = frame.height,
            marginH = PipelineDefaults.FACE_CROP_MARGIN_HORIZONTAL,
            marginV = PipelineDefaults.FACE_CROP_MARGIN_VERTICAL,
        )
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
