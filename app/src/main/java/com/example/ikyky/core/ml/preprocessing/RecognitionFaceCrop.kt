package com.example.ikyky.core.ml.preprocessing

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.LandmarkType

/**
 * **RECOGNITION crop geometry** — the canonical ArcFace 5-point similarity
 * transform. Pure math, no Android types, fully unit-testable on the JVM.
 *
 * This is one of TWO distinct crops in the pipeline and they must never be
 * conflated (Phase 6 brief):
 *
 *  - [TrackerAppearanceCrop] — expanded bounding box, feeds the tracker's
 *    embedding gate. This is what Config K / Option A gates with.
 *  - **[RecognitionFaceCrop]** (this file) — 5-point aligned, feeds MobileFaceNet
 *    for identity. Phase 5H showed this single change lifts cross-shot AUC from
 *    0.768 to 0.905 and pair-F1 from 0.479 to 0.862 at identical inference cost.
 *
 * ## Coordinate systems
 *
 *  - **Source**: canonical frame pixels — upright (rotation already applied by
 *    the decoder), full-resolution-equivalent, origin top-left, `+x` right,
 *    `+y` down. These are the coordinates carried by [DetectedFace] after
 *    `FrameGeometry.toCanonical`.
 *  - **Destination**: the square output crop, `[0, outputSize) x [0, outputSize)`,
 *    same axis convention. The template below is defined at 112x112 and scaled
 *    linearly by `outputSize / 112`.
 *
 * ## Point ordering — VIEWER-relative, and this is the dangerous part
 *
 * [ARCFACE_TEMPLATE_112] is ordered **viewer-left first**. Slot 0 is the eye that
 * appears on the *viewer's* left, i.e. at the smaller x in the image. This was
 * verified against 200 real detections from the reference detector in Phase 5I:
 * `kps[0].x < kps[1].x` held in 100.0% of cases.
 *
 * ### The label convention cannot be trusted — points are ordered by MEASUREMENT
 *
 * ML Kit's documentation states that `LEFT_EYE` is the *subject's* left eye,
 * which would appear on the **viewer's right** (larger x). The Phase 5I spec
 * (§9.1) therefore prescribed a fixed swap: slot 0 <- `RIGHT_EYE`.
 *
 * **On-device measurement contradicts that.** Surveying 235 real detections
 * across all three sample clips, `LEFT_EYE.x < RIGHT_EYE.x` held in **235/235**
 * cases (100.0%), and likewise `MOUTH_LEFT.x < MOUTH_RIGHT.x` in 235/235 — i.e.
 * the labels behave viewer-relative on this footage. Applying the spec's fixed
 * swap to these landmarks yields a degenerate, upside-down fit: **27.2 px**
 * residual against the template (determinant 0.0056), versus **1.7 px** when the
 * points are ordered by actual viewer position.
 *
 * Rather than hard-code either convention, [orderedLandmarks] assigns the
 * template slots by **measured x position**: the eye at the smaller x becomes
 * slot 0 regardless of which label it carries. This is what the template
 * actually requires (it is defined viewer-left-first), and it is correct under
 * BOTH conventions and for mirrored/selfie footage — where the subject-relative
 * reading genuinely flips.
 *
 * [landmarkOrderIsPlausible] still asserts the resulting geometry, and the
 * caller still falls back to the expanded box crop rather than emitting a
 * mirrored or inverted crop.
 *
 * ## Transform
 *
 * [umeyama] solves the least-squares similarity transform (rotation + uniform
 * scale + translation; **no reflection**, no shear) mapping the 5 source points
 * onto the 5 template points. Direction is **source -> destination**, so the
 * resulting 2x3 matrix is applied directly as a forward warp of the frame into
 * the crop.
 *
 *  - **rotation**: the reflection-free maximiser of `trace(R^T * cov)`. In 2D
 *    this has a direct closed form (`cos ∝ c00 + c11`, `sin ∝ c10 - c01`) that
 *    is identical to the general SVD-with-sign-fix result but cannot express a
 *    reflection at all. [SimilarityTransform.determinant] is asserted positive.
 *  - **scale**: `sum(singularValues * d.diagonal) / variance(source)`, which for
 *    the 2D case is `hypot(c00 + c11, c10 - c01) / variance(source)` — one
 *    uniform factor for both axes.
 *  - **translation**: `dstMean - scale * R * srcMean`.
 *
 * ## Output
 *
 * `outputSize x outputSize` RGB, later packed NHWC float32 with
 * `(px - 127.5) / 127.5` by [com.example.ikyky.core.ml.embedding.InputTensorPacker].
 * Channel order is RGB (Android bitmaps are ARGB; the packer extracts R,G,B in
 * that order). The Python reference works in BGR only because OpenCV decodes
 * BGR — it feeds the *same* physical channel order to the model.
 */
object RecognitionFaceCrop {

    /**
     * Canonical ArcFace 112x112 5-point template, **viewer-left first**.
     *
     * This is the de-facto standard template the ArcFace / MobileFaceNet model
     * family was trained against. Do not substitute another template: the model's
     * expected input geometry is fixed by its training data.
     */
    val ARCFACE_TEMPLATE_112: Array<DoubleArray> = arrayOf(
        doubleArrayOf(38.2946, 51.6963), // 0 - eye,   viewer-LEFT
        doubleArrayOf(73.5318, 51.5014), // 1 - eye,   viewer-RIGHT
        doubleArrayOf(56.0252, 71.7366), // 2 - nose tip
        doubleArrayOf(41.5493, 92.3655), // 3 - mouth, viewer-LEFT
        doubleArrayOf(70.7299, 92.2041), // 4 - mouth, viewer-RIGHT
    )

    const val VIEWER_LEFT_EYE = 0
    const val VIEWER_RIGHT_EYE = 1
    const val NOSE = 2
    const val VIEWER_LEFT_MOUTH = 3
    const val VIEWER_RIGHT_MOUTH = 4

    /**
     * The landmark PAIR each template slot is resolved from.
     *
     * Slots 0/1 (eyes) and 3/4 (mouth corners) are filled by comparing the two
     * candidates' x coordinates — smaller x wins the viewer-left slot — rather
     * than by trusting either label convention. See the class docs for the
     * measurement that forced this.
     */
    val EYE_LANDMARKS: Pair<LandmarkType, LandmarkType> =
        LandmarkType.LEFT_EYE to LandmarkType.RIGHT_EYE
    val MOUTH_LANDMARKS: Pair<LandmarkType, LandmarkType> =
        LandmarkType.MOUTH_LEFT to LandmarkType.MOUTH_RIGHT
    val NOSE_LANDMARK: LandmarkType = LandmarkType.NOSE_BASE

    /** Every landmark type the 5-point alignment needs present. */
    val REQUIRED_LANDMARKS: List<LandmarkType> = listOf(
        LandmarkType.LEFT_EYE, LandmarkType.RIGHT_EYE, LandmarkType.NOSE_BASE,
        LandmarkType.MOUTH_LEFT, LandmarkType.MOUTH_RIGHT,
    )

    /** A 2x3 affine matrix `[[a, b, tx], [c, d, ty]]` mapping source -> destination. */
    data class SimilarityTransform(
        val a: Double, val b: Double, val tx: Double,
        val c: Double, val d: Double, val ty: Double,
    ) {
        /** Positive for a true similarity; negative would mean a mirror. */
        val determinant: Double get() = a * d - b * c

        fun apply(x: Double, y: Double): DoubleArray =
            doubleArrayOf(a * x + b * y + tx, c * x + d * y + ty)

        /** Row-major `[a, b, tx, c, d, ty]` — the layout `android.graphics.Matrix` wants. */
        fun toFloatArray(): FloatArray = floatArrayOf(
            a.toFloat(), b.toFloat(), tx.toFloat(),
            c.toFloat(), d.toFloat(), ty.toFloat(),
        )
    }

    /**
     * The five landmarks a face must have, in **template slot order**
     * (viewer-left eye, viewer-right eye, nose, viewer-left mouth,
     * viewer-right mouth), or null if any is missing.
     */
    fun orderedLandmarks(face: DetectedFace): Array<DoubleArray>? {
        fun point(type: LandmarkType): DoubleArray? {
            val lm = face.landmarks.firstOrNull { it.type == type } ?: return null
            if (!lm.x.isFinite() || !lm.y.isFinite()) return null
            return doubleArrayOf(lm.x.toDouble(), lm.y.toDouble())
        }

        val eyeA = point(EYE_LANDMARKS.first) ?: return null
        val eyeB = point(EYE_LANDMARKS.second) ?: return null
        val nose = point(NOSE_LANDMARK) ?: return null
        val mouthA = point(MOUTH_LANDMARKS.first) ?: return null
        val mouthB = point(MOUTH_LANDMARKS.second) ?: return null

        // Assign by MEASURED viewer position, not by label. The template is
        // defined viewer-left-first, so the smaller x always takes slot 0 / 3.
        val (leftEye, rightEye) = if (eyeA[0] <= eyeB[0]) eyeA to eyeB else eyeB to eyeA
        val (leftMouth, rightMouth) =
            if (mouthA[0] <= mouthB[0]) mouthA to mouthB else mouthB to mouthA

        return arrayOf(leftEye, rightEye, nose, leftMouth, rightMouth)
    }

    /**
     * Guards the mapping described in the class docs.
     *
     * For an upright, non-mirrored frame the viewer-left points must sit at
     * smaller x than their viewer-right counterparts. If this fails, the
     * landmarks were swapped (a coding error) or the face is so extremely
     * profiled/rolled that the assumption breaks — either way the correct
     * response is the box-crop fallback, **never** a mirrored recognition crop.
     *
     * @return true when `p[0].x < p[1].x` and `p[3].x < p[4].x` and the eyes are
     *   separated by a plausible fraction of the detection box width.
     */
    fun landmarkOrderIsPlausible(points: Array<DoubleArray>, box: BoundingBox): Boolean {
        if (points.size != 5) return false
        for (p in points) if (!p[0].isFinite() || !p[1].isFinite()) return false

        // viewer-left must be left of viewer-right, in BOTH pairs
        if (points[VIEWER_LEFT_EYE][0] >= points[VIEWER_RIGHT_EYE][0]) return false
        if (points[VIEWER_LEFT_MOUTH][0] >= points[VIEWER_RIGHT_MOUTH][0]) return false

        // eyes must be above the mouth in image coordinates (+y is down)
        val eyeY = (points[VIEWER_LEFT_EYE][1] + points[VIEWER_RIGHT_EYE][1]) / 2.0
        val mouthY = (points[VIEWER_LEFT_MOUTH][1] + points[VIEWER_RIGHT_MOUTH][1]) / 2.0
        if (eyeY >= mouthY) return false

        // inter-ocular distance must be a sane fraction of the detection box
        val dx = points[VIEWER_RIGHT_EYE][0] - points[VIEWER_LEFT_EYE][0]
        val dy = points[VIEWER_RIGHT_EYE][1] - points[VIEWER_LEFT_EYE][1]
        val inter = kotlin.math.sqrt(dx * dx + dy * dy)
        val boxW = box.width.toDouble().coerceAtLeast(1.0)
        val ratio = inter / boxW
        return ratio in MIN_INTEROCULAR_RATIO..MAX_INTEROCULAR_RATIO
    }

    /** The template scaled from its native 112 px to [outputSize]. */
    fun templateFor(outputSize: Int): Array<DoubleArray> {
        val s = outputSize / 112.0
        return Array(5) { i ->
            doubleArrayOf(ARCFACE_TEMPLATE_112[i][0] * s, ARCFACE_TEMPLATE_112[i][1] * s)
        }
    }

    /**
     * Full source->destination transform for one face, or null if the landmarks
     * are missing / implausible (caller must then use the box-crop fallback).
     */
    fun transformFor(face: DetectedFace, outputSize: Int): SimilarityTransform? {
        if (outputSize <= 0) return null
        val src = orderedLandmarks(face) ?: return null
        if (!landmarkOrderIsPlausible(src, face.boundingBox)) return null
        val m = umeyama(src, templateFor(outputSize)) ?: return null
        // A similarity transform never mirrors. Belt-and-braces: the Umeyama sign
        // fix already forbids reflection, so this can only fire on a degenerate
        // (collinear / coincident) point set.
        return if (m.determinant > 0.0) m else null
    }

    /**
     * Umeyama least-squares similarity transform: rotation + uniform scale +
     * translation mapping [src] onto [dst]. Reflection is explicitly excluded.
     *
     * Reproduces `ikyky_lab/preprocessing/align.py::_umeyama` exactly.
     *
     * @return null if the point sets are degenerate.
     */
    fun umeyama(src: Array<DoubleArray>, dst: Array<DoubleArray>): SimilarityTransform? {
        val n = src.size
        if (n < 2 || dst.size != n) return null

        var sxm = 0.0; var sym = 0.0; var dxm = 0.0; var dym = 0.0
        for (i in 0 until n) {
            sxm += src[i][0]; sym += src[i][1]
            dxm += dst[i][0]; dym += dst[i][1]
        }
        sxm /= n; sym /= n; dxm /= n; dym /= n

        // cross-covariance cov = (dst_centred^T * src_centred) / n, and the
        // source variance used by the Umeyama scale term
        var c00 = 0.0; var c01 = 0.0; var c10 = 0.0; var c11 = 0.0
        var varSrc = 0.0
        for (i in 0 until n) {
            val sx = src[i][0] - sxm
            val sy = src[i][1] - sym
            val dx = dst[i][0] - dxm
            val dy = dst[i][1] - dym
            c00 += dx * sx; c01 += dx * sy
            c10 += dy * sx; c11 += dy * sy
            varSrc += sx * sx + sy * sy
        }
        c00 /= n; c01 /= n; c10 /= n; c11 /= n
        varSrc /= n

        // In 2D the reflection-free Umeyama solution has a direct closed form and
        // needs no SVD at all. Writing R as [[cos, -sin], [sin, cos]], the
        // rotation that maximises trace(R^T * cov) is
        //
        //     cos ∝ c00 + c11 ,   sin ∝ c10 - c01
        //
        // and the corresponding Umeyama scale term sum(s_i * d_ii) equals the
        // norm of that same vector. This is exactly what the general
        // SVD-with-sign-fix computes, without the sign hazards of a hand-rolled
        // 2x2 SVD (an earlier closed form here silently inverted the rotation).
        val cosTerm = c00 + c11
        val sinTerm = c10 - c01
        val norm = kotlin.math.sqrt(cosTerm * cosTerm + sinTerm * sinTerm)

        val r00: Double
        val r01: Double
        val r10: Double
        val r11: Double
        if (norm < 1e-12) {
            // Degenerate: no preferred orientation. Fall back to the identity
            // rotation rather than an arbitrary one.
            r00 = 1.0; r01 = 0.0; r10 = 0.0; r11 = 1.0
        } else {
            val cosA = cosTerm / norm
            val sinA = sinTerm / norm
            r00 = cosA; r01 = -sinA
            r10 = sinA; r11 = cosA
        }

        // scale = sum(singularValues * d.diagonal) / var(src) == norm / var(src)
        val scale = if (varSrc < 1e-12) 1.0 else norm / varSrc

        val a = scale * r00
        val b = scale * r01
        val c = scale * r10
        val d = scale * r11
        val tx = dxm - (a * sxm + b * sym)
        val ty = dym - (c * sxm + d * sym)

        if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite() ||
            !tx.isFinite() || !ty.isFinite()
        ) {
            return null
        }
        return SimilarityTransform(a, b, tx, c, d, ty)
    }

    private const val MIN_INTEROCULAR_RATIO = 0.15
    private const val MAX_INTEROCULAR_RATIO = 1.20
}
