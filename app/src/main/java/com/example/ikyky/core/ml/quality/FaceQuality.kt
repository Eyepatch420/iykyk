package com.example.ikyky.core.ml.quality

import android.graphics.Bitmap
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.DetectedFace
import kotlin.math.abs
import kotlin.math.min

/**
 * A cheap, model-free quality signal for one face observation.
 *
 * This is NOT the final representative-frame scorer (that is a later phase). It
 * exists so the pipeline can flag obviously unusable observations (tiny faces,
 * whip-pan blur) while still keeping them for tracking continuity.
 *
 * @property score          0f..1f rough usability
 * @property blurVariance   variance-of-Laplacian proxy; low ⇒ blurred
 * @property faceFraction   face longer-edge / frame shorter-edge
 * @property isUsable       score above the reject line
 */
data class QualitySignal(
    val score: Float,
    val blurVariance: Double,
    val faceFraction: Float,
    val isUsable: Boolean,
)

/**
 * Computes a [QualitySignal] from a face box and (optionally) a downscaled
 * grayscale patch of that face.
 */
object FaceQuality {

    /**
     * @param face        the detection (canonical coords)
     * @param frameWidth  canonical frame width
     * @param frameHeight canonical frame height
     * @param facePatch   optional small grayscale-friendly bitmap of the face crop
     *                     used only for the blur estimate; may be null
     */
    fun evaluate(
        face: DetectedFace,
        frameWidth: Int,
        frameHeight: Int,
        facePatch: Bitmap?,
    ): QualitySignal {
        val longerEdge = maxOf(face.boundingBox.width, face.boundingBox.height).coerceAtLeast(1)
        val shorterFrame = min(frameWidth, frameHeight).coerceAtLeast(1)
        val faceFraction = longerEdge.toFloat() / shorterFrame

        val blurVar = facePatch?.let { varianceOfLaplacian(it) } ?: Double.NaN

        var score = 1f
        // size penalty
        if (faceFraction < PipelineDefaults.QUALITY_MIN_FACE_FRACTION) {
            score *= (faceFraction / PipelineDefaults.QUALITY_MIN_FACE_FRACTION).coerceIn(0f, 1f)
        }
        // blur penalty (only when we actually measured it)
        if (!blurVar.isNaN()) {
            val blurFactor = (blurVar / (PipelineDefaults.BLUR_VARIANCE_MIN * 4.0))
                .coerceIn(0.0, 1.0)
            score *= blurFactor.toFloat()
        }
        // eyes-closed / extreme pose soft penalty using ML Kit classification
        val eyeOpen = minOf(
            face.leftEyeOpenProbability ?: 1f,
            face.rightEyeOpenProbability ?: 1f,
        )
        if (eyeOpen < 0.3f) score *= 0.7f
        face.headPose?.let { pose ->
            val yaw = abs(pose.eulerY)
            if (yaw > 45f) score *= 0.6f
        }

        val usable = score >= 0.15f &&
            faceFraction >= PipelineDefaults.QUALITY_MIN_FACE_FRACTION * 0.6f &&
            (blurVar.isNaN() || blurVar >= PipelineDefaults.BLUR_VARIANCE_MIN)

        return QualitySignal(
            score = score.coerceIn(0f, 1f),
            blurVariance = blurVar,
            faceFraction = faceFraction,
            isUsable = usable,
        )
    }

    /**
     * Variance-of-Laplacian on the luminance of a small bitmap — a standard
     * cheap sharpness proxy. Larger = sharper.
     */
    fun varianceOfLaplacian(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return Double.NaN
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val lum = DoubleArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val lap = (lum[idx - 1] + lum[idx + 1] + lum[idx - w] + lum[idx + w]) - 4.0 * lum[idx]
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return Double.NaN
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
