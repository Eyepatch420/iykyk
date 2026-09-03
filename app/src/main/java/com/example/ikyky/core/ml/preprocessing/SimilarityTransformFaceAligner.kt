package com.example.ikyky.core.ml.preprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.extensions.cropOrNull
import com.example.ikyky.core.common.extensions.expandBy
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.LandmarkType
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Eye-line alignment via a 2D similarity transform (rotate + uniform scale +
 * translate). Produces the square [outputSize] recognition crop for the
 * embedding model.
 *
 * Strategy:
 *  1. If BOTH eye landmarks are present and sane (positive separation, inside a
 *     plausible band of the detection box) → rotate so the eye line is
 *     horizontal and scale so the inter-ocular distance is a fixed fraction of
 *     the output. This is the pose-robust path.
 *  2. Otherwise → fall back to a **recognition crop** (margin-expanded,
 *     `PipelineDefaults.FACE_CROP_MARGIN_*`, clamped) resized to the square. No
 *     rotation, never a mirror.
 *
 * The transform is a pure similarity transform (`postRotate` + positive
 * `postScale`), so a face is never mirrored. Coordinates are the canonical
 * frame's — the caller passes canonical boxes/landmarks.
 */
class SimilarityTransformFaceAligner : FaceAligner {

    override fun align(frame: Bitmap, face: DetectedFace, outputSize: Int): Bitmap? =
        alignDetailed(frame, face, outputSize)?.bitmap

    override fun alignDetailed(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int,
    ): AlignResult? {
        if (outputSize <= 0) return null
        val eyes = validEyes(face)
        return if (eyes != null) {
            AlignResult(alignByEyes(frame, eyes.first, eyes.second, outputSize), true)
        } else {
            recognitionCropResized(frame, face, outputSize)?.let { AlignResult(it, false) }
        }
    }

    // --- eye-based similarity transform ---------------------------------------

    private data class Eye(val x: Float, val y: Float)

    /**
     * Returns (leftEye, rightEye) in canonical pixels if both are present and
     * geometrically plausible for this detection, else null.
     */
    private fun validEyes(face: DetectedFace): Pair<Eye, Eye>? {
        val l = face.landmarks.firstOrNull { it.type == LandmarkType.LEFT_EYE } ?: return null
        val r = face.landmarks.firstOrNull { it.type == LandmarkType.RIGHT_EYE } ?: return null
        val le = Eye(l.x, l.y)
        val re = Eye(r.x, r.y)
        val interOcular = hypot((re.x - le.x).toDouble(), (re.y - le.y).toDouble()).toFloat()
        val box = face.boundingBox
        val boxW = (box.right - box.left).coerceAtLeast(1)
        // eyes should be separated by a sensible fraction of the face width and
        // both should sit inside the (slightly padded) detection box
        if (interOcular < 0.15f * boxW || interOcular > 1.5f * boxW) return null
        val padX = 0.35f * boxW
        val padY = 0.35f * (box.bottom - box.top).coerceAtLeast(1)
        val minX = box.left - padX; val maxX = box.right + padX
        val minY = box.top - padY; val maxY = box.bottom + padY
        for (e in listOf(le, re)) {
            if (e.x < minX || e.x > maxX || e.y < minY || e.y > maxY) return null
        }
        return le to re
    }

    private fun alignByEyes(
        frame: Bitmap,
        leftEye: Eye,
        rightEye: Eye,
        outputSize: Int,
    ): Bitmap {
        // Eye-line angle (canonical space; +x right, +y down).
        val angleDeg = Math.toDegrees(
            atan2(
                (rightEye.y - leftEye.y).toDouble(),
                (rightEye.x - leftEye.x).toDouble(),
            )
        ).toFloat()

        val interOcular = hypot(
            (rightEye.x - leftEye.x).toDouble(),
            (rightEye.y - leftEye.y).toDouble(),
        ).toFloat().coerceAtLeast(1f)

        // Canonical ArcFace-ish layout: eyes ~38% of width apart, eye line at
        // ~38% of height, centred horizontally.
        val desiredInterOcular = outputSize * DESIRED_INTEROCULAR_FRACTION
        val scale = desiredInterOcular / interOcular

        val eyesCx = (leftEye.x + rightEye.x) / 2f
        val eyesCy = (leftEye.y + rightEye.y) / 2f
        val targetCx = outputSize / 2f
        val targetCy = outputSize * DESIRED_EYE_LINE_FRACTION

        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val matrix = Matrix().apply {
            postTranslate(-eyesCx, -eyesCy)
            postRotate(-angleDeg)          // de-rotate the eye line to horizontal
            postScale(scale, scale)        // positive → no mirror
            postTranslate(targetCx, targetCy)
        }
        canvas.drawBitmap(
            frame,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return output
    }

    // --- bounding-box fallback ----------------------------------------------

    /** Margin-expanded, clamped recognition crop, resized to the square. */
    private fun recognitionCropResized(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int,
    ): Bitmap? {
        val c = RecognitionCrop.expandAndClamp(
            face = face.boundingBox,
            frameWidth = frame.width,
            frameHeight = frame.height,
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

    /** Larger, un-rotated crop meant for the collage, never for embedding. */
    fun presentationCrop(
        frame: Bitmap,
        face: DetectedFace,
        margin: Float = PipelineDefaults.PRESENTATION_CROP_MARGIN,
    ): Bitmap? {
        val b = face.boundingBox
        val rect = Rect(b.left, b.top, b.right, b.bottom).expandBy(margin)
        return frame.cropOrNull(rect)
    }

    private companion object {
        const val DESIRED_INTEROCULAR_FRACTION = 0.38f
        const val DESIRED_EYE_LINE_FRACTION = 0.38f
    }
}
