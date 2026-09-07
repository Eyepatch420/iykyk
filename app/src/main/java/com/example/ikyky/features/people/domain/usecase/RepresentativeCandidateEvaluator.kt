package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper
import com.example.ikyky.core.ml.preprocessing.RecognitionCrop
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.LandmarkType
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import kotlin.math.abs

/**
 * **Phase 8.2 — representative-candidate quality selection.**
 *
 * Given every observation of one person (across all their appearances), this
 * type does *reject-then-rank*:
 *
 *  1. **Reject** invalid candidates — oversized detection boxes (C1), corner
 *     false positives (C2), too-soft / too-off-pose frames (C3), degenerate
 *     landmark geometry, and (via [validateOnePerson]) crops that would contain
 *     a second person or almost none of the target.
 *  2. **Rank** the survivors deterministically on real signals (C3/C4):
 *     landmark completeness, sharpness, pose frontality, face-size sanity,
 *     temporal position — never on the saturated Phase-2 `qualityScore`.
 *  3. Fall back, in strict priority order, to a detection-box crop and finally
 *     to "no valid representative" — a fallback candidate always ranks below a
 *     valid landmark candidate.
 *
 * It never deletes a tracklet, never changes identity membership, and reads
 * only data already produced by the frozen pipeline (boxes, landmarks, blur
 * variance, head pose). Nothing here calls MobileFaceNet or the detector.
 */
object RepresentativeCandidateEvaluator {

    /** Why a candidate was rejected — surfaced in diagnostics. */
    enum class Reject {
        OVERSIZED_BOX, CORNER_FALSE_POSITIVE, NON_FACE_BOX_SHAPE, TOO_BLURRED,
        EXTREME_POSE, DEGENERATE_LANDMARKS, FOREIGN_FACE_IN_CROP,
        TARGET_FACE_ABSENT, UNUSABLE_CROP_RECT,
    }

    /**
     * How the crop rect for an accepted candidate is built, in descending
     * priority:
     *  - [LANDMARK_TIGHT] — landmark-driven portrait (the good path);
     *  - [DETECTION_BOX_FALLBACK] — landmark-poor frame, crop from the detection
     *    box (still one-person-validated).
     *
     * If neither is available the person's representative is left **unavailable**
     * (the caller sets `representativeFrame = null`). Per the Phase 8.2 brief a
     * missing representative is preferable to a bad one; a person whose every
     * frame is genuinely crowded is an identity-clustering artefact
     * (over-splitting), out of scope here.
     */
    enum class CropKind { LANDMARK_TIGHT, DETECTION_BOX_FALLBACK }

    data class Candidate(
        val appearanceId: String,
        val observation: AppearanceObservationRef,
        val cropKind: CropKind,
        /** 0f..1f composite, higher is better. Only meaningful for accepted candidates. */
        val score: Float,
    )

    data class Evaluation(
        val chosen: Candidate?,
        val rejected: Map<String, Reject>, // observationId -> reason
        val consideredCount: Int,
        val acceptedCount: Int,
        val usedFallback: Boolean,
    )

    /**
     * @param observationsByAppearance appearanceId -> that appearance's observations
     * @param frameWidth/frameHeight   canonical frame dims
     * @param faceBoxesByFrame         every detected face box per frame index
     *                                  (across ALL people) — the sibling set for
     *                                  the crowding/one-person checks
     */
    fun evaluate(
        observationsByAppearance: Map<String, List<AppearanceObservationRef>>,
        frameWidth: Int,
        frameHeight: Int,
        faceBoxesByFrame: Map<Int, List<BoundingBox>>,
    ): Evaluation {
        val rejected = LinkedHashMap<String, Reject>()
        val accepted = ArrayList<Candidate>()
        var considered = 0

        val shortEdge = minOf(frameWidth, frameHeight).coerceAtLeast(1)
        val frameArea = frameWidth.toLong() * frameHeight

        // First/last observation frame per appearance -> transition frames.
        val edgeFrames = observationsByAppearance.values.flatMap { obs ->
            listOfNotNull(obs.firstOrNull()?.frameIndex, obs.lastOrNull()?.frameIndex)
        }.toSet()

        for ((appId, obsList) in observationsByAppearance) {
            for (o in obsList) {
                considered++
                val reason = rejectReason(o, shortEdge, frameArea, frameWidth, frameHeight, faceBoxesByFrame)
                if (reason != null) {
                    rejected[o.observationId] = reason
                    continue
                }
                accepted += Candidate(
                    appearanceId = appId,
                    observation = o,
                    cropKind = cropKindFor(o),
                    score = compositeScore(o, shortEdge, isTransition = o.frameIndex in edgeFrames),
                )
            }
        }

        // Rank: LANDMARK_TIGHT strictly above DETECTION_BOX_FALLBACK, then by
        // composite score, then a deterministic tie-break. If nothing valid
        // remains the person's representative is left unavailable.
        val chosen = accepted.maxWithOrNull(
            compareBy<Candidate>(
                { if (it.cropKind == CropKind.LANDMARK_TIGHT) 1 else 0 },
                { it.score },
                { -it.observation.frameIndex },
                { it.observation.observationId },
            ),
        )

        return Evaluation(
            chosen = chosen,
            rejected = rejected,
            consideredCount = considered,
            acceptedCount = accepted.size,
            usedFallback = chosen?.cropKind == CropKind.DETECTION_BOX_FALLBACK,
        )
    }

    // ---- rejection --------------------------------------------------------

    private fun rejectReason(
        o: AppearanceObservationRef,
        shortEdge: Int,
        frameArea: Long,
        frameWidth: Int,
        frameHeight: Int,
        faceBoxesByFrame: Map<Int, List<BoundingBox>>,
    ): Reject? {
        val b = o.canonicalBox.normalized()

        // C1 — detection box far too large OR far too small for a portrait.
        val ffMax = maxOf(b.width, b.height).toFloat() / shortEdge
        val areaFrac = (b.width.toLong() * b.height).toFloat() / frameArea
        if (ffMax > PipelineDefaults.REP_MAX_FACE_FRACTION ||
            areaFrac > PipelineDefaults.REP_MAX_FACE_AREA_FRACTION
        ) return Reject.OVERSIZED_BOX
        if (ffMax < PipelineDefaults.REP_MIN_FACE_FRACTION) return Reject.TARGET_FACE_ABSENT

        // Non-face box shape: a real upright face box is roughly square-to-tall.
        // A short wide slab that still carries landmarks is an ML Kit artefact.
        val aspect = if (b.width > 0) b.height.toFloat() / b.width else 0f
        if (aspect < PipelineDefaults.REP_MIN_BOX_ASPECT ||
            aspect > PipelineDefaults.REP_MAX_BOX_ASPECT
        ) return Reject.NON_FACE_BOX_SHAPE

        // C2 — corner false positive: pinned into a corner AND small AND
        // landmarks don't fill the box.
        val edges = intArrayOf(b.left, b.top, frameWidth - b.right, frameHeight - b.bottom)
        val pinnedEdges = edges.count { it <= PipelineDefaults.REP_CORNER_EDGE_PX }
        if (pinnedEdges >= PipelineDefaults.REP_CORNER_MIN_EDGES &&
            ffMax < PipelineDefaults.REP_CORNER_MAX_FACE_FRACTION &&
            landmarkSpanFraction(o) < PipelineDefaults.REP_MIN_LANDMARK_SPAN
        ) {
            return Reject.CORNER_FALSE_POSITIVE
        }

        // C2b — small box hard against a frame edge. Audit evidence: legitimate
        // small close-ups (ff ≈ 0.37–0.40) sit well inside the frame; the
        // spurious detections (`[191,0,635,349]`, `[0,0,490,518]`, `[0,0,456,268]`)
        // are all small AND touch a border. A real face that large near an edge
        // would be at least [REP_EDGE_SMALL_MAX_FACE_FRACTION] of the short edge.
        val touchesEdge = edges.min() <= PipelineDefaults.REP_EDGE_TOUCH_PX
        if (touchesEdge && ffMax < PipelineDefaults.REP_EDGE_SMALL_MAX_FACE_FRACTION) {
            return Reject.CORNER_FALSE_POSITIVE
        }

        // Degenerate landmark geometry: fewer than 4 core landmarks AND no
        // usable eye pair -> can't build a portrait, can't even trust the box.
        val core = o.landmarks.count { it.type in CORE }
        val hasEyePair = o.landmarks.any { it.type == LandmarkType.LEFT_EYE } &&
            o.landmarks.any { it.type == LandmarkType.RIGHT_EYE }
        if (core < 3 && !hasEyePair) return Reject.DEGENERATE_LANDMARKS

        // C3 — sharpness (only when measured)
        if (!o.blurVariance.isNaN() && o.blurVariance < PipelineDefaults.REP_MIN_BLUR_VARIANCE) {
            return Reject.TOO_BLURRED
        }

        // C3 — pose
        o.headPose?.let { hp ->
            if (abs(hp.eulerY) > PipelineDefaults.REP_MAX_HEAD_ANGLE_DEG ||
                abs(hp.eulerX) > PipelineDefaults.REP_MAX_HEAD_ANGLE_DEG
            ) return Reject.EXTREME_POSE
        }

        // Hard one-person + zero/partial check on the PROPOSED crop rect, using
        // the frame's other detected face boxes (no extra inference).
        val siblings = (faceBoxesByFrame[o.frameIndex] ?: emptyList())
            .map { it.normalized() }
            .filter { it != b && iou(it, b) <= 0.5f }
        val rect = proposedRect(o, frameWidth, frameHeight, siblings)
        if (!RecognitionCrop.isUsable(rect)) return Reject.UNUSABLE_CROP_RECT

        // foreign face substantially inside the crop? (area fraction OR an
        // absolute pixel chunk — a small % of a big face box is still a
        // recognisable partial face)
        val foreign = siblings.any { s ->
            val sArea = s.width.toLong() * s.height
            if (sArea <= 0) return@any false
            val interW = (minOf(rect.right, s.right) - maxOf(rect.left, s.left)).coerceAtLeast(0)
            val interH = (minOf(rect.bottom, s.bottom) - maxOf(rect.top, s.top)).coerceAtLeast(0)
            val areaOverlap = interW.toLong() * interH / sArea.toFloat()
            areaOverlap > PipelineDefaults.REP_FOREIGN_FACE_MAX_OVERLAP ||
                (interW >= PipelineDefaults.REP_FOREIGN_FACE_MAX_PX &&
                    interH >= PipelineDefaults.REP_FOREIGN_FACE_MAX_PX)
        }
        if (foreign) return Reject.FOREIGN_FACE_IN_CROP

        // target face substantially present in the crop? (use landmark span box
        // when available, else the detection box)
        val targetBox = landmarkBox(o) ?: b
        val targetInCrop = overlapArea(rect, targetBox)
        val rectArea = rect.width.toLong() * rect.height
        if (rectArea > 0 && targetInCrop.toFloat() / rectArea < PipelineDefaults.REP_TARGET_MIN_FRACTION_OF_CROP) {
            return Reject.TARGET_FACE_ABSENT
        }

        return null
    }

    // ---- ranking --------------------------------------------------------

    private fun compositeScore(
        o: AppearanceObservationRef,
        shortEdge: Int,
        isTransition: Boolean,
    ): Float {
        var s = 0f

        // landmark completeness (0..0.30)
        val core = o.landmarks.count { it.type in CORE }
        s += (core / 10f).coerceIn(0f, 1f) * 0.30f

        // sharpness (0..0.25) — NaN treated as neutral 0.5
        val blurNorm = if (o.blurVariance.isNaN()) 0.5f
        else (o.blurVariance / PipelineDefaults.REP_BLUR_VARIANCE_IDEAL).coerceIn(0.0, 1.0).toFloat()
        s += blurNorm * 0.25f

        // pose frontality (0..0.20)
        val poseNorm = o.headPose?.let { hp ->
            val worst = maxOf(abs(hp.eulerY), abs(hp.eulerX))
            (1f - (worst / PipelineDefaults.REP_MAX_HEAD_ANGLE_DEG)).coerceIn(0f, 1f)
        } ?: 0.6f
        s += poseNorm * 0.20f

        // face-size sanity: prefer a face that is present but not frame-filling.
        // Audit median ffMax on this material is ~0.82, so the "ideal" plateau
        // sits a little below median (0.35–0.78) and the score falls off toward
        // the C1 ceiling — this makes the ranker favour the *least* oversized of
        // a person's frames without rejecting any. (0..0.15)
        val b = o.canonicalBox.normalized()
        val ff = maxOf(b.width, b.height).toFloat() / shortEdge
        val sizeNorm = when {
            ff < 0.15f -> ff / 0.15f
            ff <= 0.78f -> 1f
            else -> (1f - (ff - 0.78f) / (PipelineDefaults.REP_MAX_FACE_FRACTION - 0.78f)).coerceIn(0f, 1f)
        }
        s += sizeNorm * 0.15f

        // temporal: not a shot-transition frame (0..0.10)
        s += if (isTransition) 0f else 0.10f

        return s.coerceIn(0f, 1f)
    }

    private fun cropKindFor(o: AppearanceObservationRef): CropKind {
        val core = o.landmarks.count { it.type in CORE }
        return if (core >= 4) CropKind.LANDMARK_TIGHT else CropKind.DETECTION_BOX_FALLBACK
    }

    // ---- geometry helpers ---------------------------------------------------

    private val CORE = setOf(
        LandmarkType.LEFT_EYE, LandmarkType.RIGHT_EYE, LandmarkType.NOSE_BASE,
        LandmarkType.MOUTH_LEFT, LandmarkType.MOUTH_RIGHT, LandmarkType.MOUTH_BOTTOM,
        LandmarkType.LEFT_EAR, LandmarkType.RIGHT_EAR,
        LandmarkType.LEFT_CHEEK, LandmarkType.RIGHT_CHEEK,
    )

    private fun proposedRect(
        o: AppearanceObservationRef,
        frameWidth: Int,
        frameHeight: Int,
        siblings: List<BoundingBox>,
    ): BoundingBox =
        if (cropKindFor(o) == CropKind.LANDMARK_TIGHT) {
            PresentationFaceCropper.landmarkRectFor(
                o.canonicalBox, o.landmarks, frameWidth, frameHeight, siblings,
            )
        } else {
            PresentationFaceCropper.rectFor(o.canonicalBox, frameWidth, frameHeight, siblings)
        }

    private fun landmarkBox(o: AppearanceObservationRef): BoundingBox? {
        val core = o.landmarks.filter { it.type in CORE }
        if (core.size < 3) return null
        return BoundingBox(
            left = core.minOf { it.x }.toInt(),
            top = core.minOf { it.y }.toInt(),
            right = core.maxOf { it.x }.toInt(),
            bottom = core.maxOf { it.y }.toInt(),
        ).normalized()
    }

    private fun landmarkSpanFraction(o: AppearanceObservationRef): Float {
        val lb = landmarkBox(o) ?: return 0f
        val b = o.canonicalBox.normalized()
        if (b.width <= 0 || b.height <= 0) return 0f
        val sx = lb.width.toFloat() / b.width
        val sy = lb.height.toFloat() / b.height
        return minOf(sx, sy)
    }

    private fun BoundingBox.normalized() = BoundingBox(
        left = minOf(left, right), top = minOf(top, bottom),
        right = maxOf(left, right), bottom = maxOf(top, bottom),
    )

    private fun overlapArea(a: BoundingBox, b: BoundingBox): Long {
        val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return ix.toLong() * iy
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val inter = overlapArea(a, b)
        if (inter == 0L) return 0f
        val ua = a.width.toLong() * a.height + b.width.toLong() * b.height - inter
        return if (ua <= 0) 0f else inter.toFloat() / ua
    }
}
