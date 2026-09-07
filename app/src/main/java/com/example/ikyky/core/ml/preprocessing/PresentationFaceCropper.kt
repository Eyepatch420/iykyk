package com.example.ikyky.core.ml.preprocessing

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.extensions.cropOrNull
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.Landmark
import com.example.ikyky.core.model.LandmarkType

/**
 * **Presentation crop** (Phase 8.1) — the human-facing, single-person crop used
 * for the People screen and the collage. Explicitly separate from
 * [RecognitionFaceCrop] (112x112, 5-point aligned, MobileFaceNet input) and from
 * [TrackerAppearanceCrop] (frozen, feeds the tracker's appearance gate) — this
 * type exists purely to turn one person's detection box into a crop meant for a
 * human to look at.
 *
 * ## Why this exists
 *
 * The prior presentation path ([SimilarityTransformFaceAligner.presentationCrop])
 * expanded the detection box by 60% on every side with no regard for how close a
 * neighboring face was, so two people standing at ordinary conversational
 * distance routinely both landed in each other's "representative" crop. This
 * type:
 *
 *  1. reuses the box-expansion primitive already proven safe for a tight,
 *     single-person crop — [RecognitionCrop.expandAndClamp] at the
 *     [PipelineDefaults.PRESENTATION_CROP_MARGIN_HORIZONTAL] /
 *     [PipelineDefaults.PRESENTATION_CROP_MARGIN_VERTICAL] margins
 *     [TrackerAppearanceCrop] has used in production since Phase 6, and
 *  2. additionally pulls the expanded rect back off any other face box detected
 *     in the SAME frame — trimming whichever single edge clears the neighbor
 *     for the least crop-area cost, never past the face's own box — so a
 *     generous margin still cannot pull a neighbor in (Phase 8.1 Step 3:
 *     "avoid unnecessarily including neighboring people").
 *
 * Unlike [TrackerAppearanceCrop] it never forces a square resize: the collage
 * renderer already crops/fits per slot
 * ([com.example.ikyky.features.collage.domain.model.CropMode]), so this type's
 * job stops at handing back an aspect-ratio-preserving crop of just that person.
 *
 * A full-frame [Bitmap] is only ever returned as the explicit last-resort
 * fallback by the caller when [crop] returns null — never silently here, and
 * always logged so a frequent fallback surfaces as a real defect.
 */
object PresentationFaceCropper {

    /**
     * **Phase 8.2 landmark-tight rect.** Builds the presentation rectangle from
     * the *facial landmarks* (eyes / nose / mouth / ears / cheeks) rather than
     * ML Kit's detection box, then expands by portrait-sensible margins:
     * generous above (forehead) and below (chin), moderate at the sides. This
     * sidesteps the audit's core problem — ML Kit routinely returns a
     * head-and-shoulders box 50–118 % of the frame short edge, and cropping from
     * that (even with the neighbour trim) yields a quarter-of-the-frame crop
     * that catches backgrounds and neighbours.
     *
     * Falls back to [rectFor] on the detection box when fewer than
     * [MIN_LANDMARKS_FOR_TIGHT] usable landmarks are present (the caller should
     * treat a detection-box crop as lower quality — see the representative
     * evaluator).
     *
     * @param face      the ML Kit detection box (canonical px) — used only for
     *                  the fallback and to clamp the landmark rect within it
     * @param landmarks canonical-space landmarks for this face
     * @param others    other face boxes in the same frame, for the neighbour trim
     */
    fun landmarkRectFor(
        face: BoundingBox,
        landmarks: List<Landmark>,
        frameWidth: Int,
        frameHeight: Int,
        others: List<BoundingBox> = emptyList(),
    ): BoundingBox {
        val core = landmarks.filter { it.type in CORE_LANDMARKS }
        if (core.size < MIN_LANDMARKS_FOR_TIGHT) {
            return rectFor(face, frameWidth, frameHeight, others)
        }
        val minX = core.minOf { it.x }
        val maxX = core.maxOf { it.x }
        val minY = core.minOf { it.y }
        val maxY = core.maxOf { it.y }
        val lmW = (maxX - minX).coerceAtLeast(1f)
        val lmH = (maxY - minY).coerceAtLeast(1f)

        // The landmark span covers roughly brow-line to chin and ear-to-ear.
        // Expand: sides by a fraction of lmW; top generously (forehead + hair);
        // bottom moderately (chin + a little neck).
        var left = (minX - lmW * LM_MARGIN_SIDE).toInt()
        var right = (maxX + lmW * LM_MARGIN_SIDE).toInt()
        var top = (minY - lmH * LM_MARGIN_TOP).toInt()
        var bottom = (maxY + lmH * LM_MARGIN_BOTTOM).toInt()

        left = left.coerceIn(0, frameWidth)
        right = right.coerceIn(0, frameWidth)
        top = top.coerceIn(0, frameHeight)
        bottom = bottom.coerceIn(0, frameHeight)

        val rect = BoundingBox(left, top, right, bottom)
        // The landmark centre is the face centre; use it (not the oversized
        // detection box) as the "self" reference for the neighbour trim.
        val selfForTrim = BoundingBox(
            left = minX.toInt(), top = minY.toInt(),
            right = maxX.toInt(), bottom = maxY.toInt(),
        )
        return clampAwayFromNeighbors(selfForTrim, rect, others)
    }

    /** Crop from [landmarkRectFor]; null when the rect is unusable. */
    fun cropFromLandmarks(
        frame: Bitmap,
        face: BoundingBox,
        landmarks: List<Landmark>,
        others: List<BoundingBox> = emptyList(),
    ): Bitmap? {
        val rect = landmarkRectFor(face, landmarks, frame.width, frame.height, others)
        if (!RecognitionCrop.isUsable(rect)) return null
        return frame.cropOrNull(Rect(rect.left, rect.top, rect.right, rect.bottom))
    }

    /**
     * The expanded, neighbor-safe rectangle in canonical frame pixels. [others]
     * are the boxes of OTHER faces in the same frame (this face's own box, or an
     * over-split near-duplicate of it, may be present and is ignored); the
     * returned rect is trimmed off any [others] box it would otherwise overlap,
     * so the crop stops before reaching a neighbor.
     */
    fun rectFor(
        face: BoundingBox,
        frameWidth: Int,
        frameHeight: Int,
        others: List<BoundingBox> = emptyList(),
    ): BoundingBox {
        val expanded = RecognitionCrop.expandAndClamp(
            face = face,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            marginH = PipelineDefaults.PRESENTATION_CROP_MARGIN_HORIZONTAL,
            marginV = PipelineDefaults.PRESENTATION_CROP_MARGIN_VERTICAL,
        )
        return clampAwayFromNeighbors(face.normalized(), expanded, others)
    }

    /**
     * Crops [frame] to just [face], preserving its natural aspect ratio (no
     * forced resize). [others] are the boxes of other faces in the same frame,
     * used to keep the crop from reaching a neighbor. Returns null when the
     * resulting rectangle is unusable (degenerate detection, a face collapsed
     * against a frame edge, or two faces so close the neighbor-safe rect
     * collapses) — the caller decides the fallback; this never silently
     * substitutes the full frame.
     */
    fun crop(
        frame: Bitmap,
        face: BoundingBox,
        others: List<BoundingBox> = emptyList(),
    ): Bitmap? {
        val rect = rectFor(face, frame.width, frame.height, others)
        if (!RecognitionCrop.isUsable(rect)) return null
        return frame.cropOrNull(Rect(rect.left, rect.top, rect.right, rect.bottom))
    }

    /**
     * True if the neighbor-safe crop rect for [face] would STILL contain a
     * meaningful slice ( > 1/5 of its area) of a non-self neighbor box in
     * [others] — i.e. the neighbor overlapped the face on every axis, so the
     * edge-pullback couldn't cut it. The caller can use this to pick a
     * different, less-crowded observation instead of accepting a leaky crop.
     */
    fun wouldLeakNeighbor(
        face: BoundingBox,
        frameWidth: Int,
        frameHeight: Int,
        others: List<BoundingBox>,
    ): Boolean {
        val rect = rectFor(face, frameWidth, frameHeight, others)
        val self = face.normalized()
        return others.asSequence()
            .map { it.normalized() }
            .filter { it != self && iou(it, self) <= SAME_FACE_IOU }
            .any { overlapArea(rect, it) > area(it) / 5 }
    }

    /**
     * Shrink [rect] toward [face] until no NON-self neighbor box in [others] has
     * a meaningful slice of its area inside the crop.
     *
     * Each iteration finds the neighbor with the largest overlap area and pulls
     * back the single rect edge that, when moved to that neighbor's near edge,
     * removes the overlap while costing the least crop area — but never past
     * [face]'s own box (a neighbor that genuinely overlaps the face on an axis
     * simply leaves that axis alone; the recognition pipeline, not this cropper,
     * owns that case). A neighbor whose box is essentially identical to [face]
     * (an over-split duplicate identity) is treated as self and skipped.
     */
    private fun clampAwayFromNeighbors(
        face: BoundingBox,
        rect: BoundingBox,
        others: List<BoundingBox>,
    ): BoundingBox {
        val neighbors = others.asSequence()
            .map { it.normalized() }
            .filter { it != face && iou(it, face) <= SAME_FACE_IOU }
            .toList()
        if (neighbors.isEmpty()) return rect

        var cur = rect
        repeat(neighbors.size * 4) {
            val worst = neighbors
                .map { it to overlapArea(cur, it) }
                .filter { it.second > 0L }
                .maxByOrNull { it.second }
                ?: return cur

            val (n, _) = worst
            // Candidate edge moves that eliminate this neighbor's overlap,
            // each clamped so it never crosses into the face's own box.
            val candidates = buildList {
                if (n.left > face.right) add(cur.copy(right = maxOf(face.right, minOf(cur.right, n.left))))
                if (n.right < face.left) add(cur.copy(left = minOf(face.left, maxOf(cur.left, n.right))))
                if (n.top > face.bottom) add(cur.copy(bottom = maxOf(face.bottom, minOf(cur.bottom, n.top))))
                if (n.bottom < face.top) add(cur.copy(top = minOf(face.top, maxOf(cur.top, n.bottom))))
            }.filter { it.right > it.left && it.bottom > it.top }

            // No safe cut (neighbor overlaps the face itself on every axis) —
            // leave it; this is the recognition pipeline's problem, not ours.
            val next = candidates.maxByOrNull { area(it) } ?: return cur
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    private fun area(b: BoundingBox): Long =
        (b.right - b.left).toLong().coerceAtLeast(0) * (b.bottom - b.top).coerceAtLeast(0)

    private fun overlapArea(a: BoundingBox, b: BoundingBox): Long {
        val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return ix.toLong() * iy
    }

    private fun BoundingBox.normalized(): BoundingBox = BoundingBox(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )

    /** Above this IoU a "neighbor" box is really the same face (an over-split
     * duplicate identity) — treat as self, do not clamp against it. */
    private const val SAME_FACE_IOU = 0.5f

    // --- Phase 8.2 landmark-tight crop ---------------------------------------

    /** Landmarks that reliably bracket the face itself (not hair/shoulders). */
    private val CORE_LANDMARKS = setOf(
        LandmarkType.LEFT_EYE, LandmarkType.RIGHT_EYE, LandmarkType.NOSE_BASE,
        LandmarkType.MOUTH_LEFT, LandmarkType.MOUTH_RIGHT, LandmarkType.MOUTH_BOTTOM,
        LandmarkType.LEFT_EAR, LandmarkType.RIGHT_EAR,
        LandmarkType.LEFT_CHEEK, LandmarkType.RIGHT_CHEEK,
    )

    /** Need at least this many core landmarks to trust a landmark-tight crop. */
    private const val MIN_LANDMARKS_FOR_TIGHT = 4

    /**
     * Portrait margins as fractions of the landmark span. The core-landmark box
     * runs eye-line to mouth (≈ upper-half of the face) and ear-to-ear, so the
     * vertical span *understates* the true face height — hence large vertical
     * multipliers: TOP must clear brow + forehead + hairline, BOTTOM must clear
     * the mouth-to-chin gap plus a little neck. Tuned on the Phase 8.2 audit
     * crops (v2: earlier 1.10/0.65 clipped chins/foreheads on tight landmark
     * spans). Produces a natural head-and-shoulders-free portrait ≈ 2.2–2.8× the
     * landmark span tall.
     */
    private const val LM_MARGIN_SIDE = 0.50f
    private const val LM_MARGIN_TOP = 1.25f
    private const val LM_MARGIN_BOTTOM = 0.90f

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix.toLong() * iy
        if (inter == 0L) return 0f
        val areaA = (a.right - a.left).toLong() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toLong() * (b.bottom - b.top)
        return inter.toFloat() / (areaA + areaB - inter)
    }
}
