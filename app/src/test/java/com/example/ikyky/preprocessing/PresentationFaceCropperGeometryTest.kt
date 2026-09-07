package com.example.ikyky.preprocessing

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper
import com.example.ikyky.core.ml.preprocessing.RecognitionCrop
import com.example.ikyky.core.model.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM geometry tests for the Phase 8.1 presentation crop (Steps 3-4-11 of
 * the Phase 8.1 brief). These exist specifically to prove the new margins
 * (0.30h / 0.40v) stay tight enough that two people standing side by side in a
 * frame do NOT both land inside each other's crop rectangle — the exact defect
 * the old 0.6-margin [com.example.ikyky.core.ml.preprocessing.SimilarityTransformFaceAligner.presentationCrop]
 * produced.
 */
class PresentationFaceCropperGeometryTest {

    private val frameW = 1080
    private val frameH = 1920

    @Test
    fun rectFor_usesPresentationMargins_notTrackerOrRecognitionMargins() {
        val face = BoundingBox(400, 700, 600, 1000) // 200 x 300
        val rect = PresentationFaceCropper.rectFor(face, frameW, frameH)
        val expected = RecognitionCrop.expandAndClamp(
            face, frameW, frameH,
            marginH = PipelineDefaults.PRESENTATION_CROP_MARGIN_HORIZONTAL,
            marginV = PipelineDefaults.PRESENTATION_CROP_MARGIN_VERTICAL,
        )
        assertEquals(expected, rect)
    }

    // Two people side-by-side at a realistic conversational distance (gap wider
    // than the box's own margin expansion): each one's crop rect must not reach
    // across into the other's box. This is the regression test for the
    // "collage tile contains two people" bug -- the old 0.6 margin would have
    // pulled both faces into a single crop at this same spacing.
    @Test
    fun rectFor_twoPeopleSideBySide_cropsDoNotCrossIntoEachOther() {
        val personA = BoundingBox(left = 100, top = 400, right = 300, bottom = 700) // 200 wide, dx = 60
        val personB = BoundingBox(left = 400, top = 400, right = 600, bottom = 700) // 100px gap from A

        val rectA = PresentationFaceCropper.rectFor(personA, frameW, frameH)
        val rectB = PresentationFaceCropper.rectFor(personB, frameW, frameH)

        assertTrue("A's crop must not reach past B's own left edge", rectA.right <= personB.left)
        assertTrue("B's crop must not reach past A's own right edge", rectB.left >= personA.right)
    }

    @Test
    fun rectFor_threePeopleInOneFrame_eachCropStaysWithinItsOwnGap() {
        val boxes = listOf(
            BoundingBox(50, 500, 220, 800),
            BoundingBox(320, 500, 490, 800),
            BoundingBox(590, 500, 760, 800),
        )
        val rects = boxes.map { PresentationFaceCropper.rectFor(it, frameW, frameH) }

        assertTrue(rects[0].right <= boxes[1].left)
        assertTrue(rects[1].left >= boxes[0].right)
        assertTrue(rects[1].right <= boxes[2].left)
        assertTrue(rects[2].left >= boxes[1].right)
    }

    @Test
    fun rectFor_edgeOfFrame_clampsSafelyAndStaysUsable() {
        val nearRightEdge = BoundingBox(frameW - 220, 600, frameW - 20, 900)
        val rect = PresentationFaceCropper.rectFor(nearRightEdge, frameW, frameH)
        assertTrue(rect.right <= frameW)
        assertTrue(RecognitionCrop.isUsable(rect))
    }

    // With neighbor boxes supplied, a generous margin must be pulled back to
    // the midline between this face and the neighbor -- even when the raw
    // margin would otherwise reach across a small gap.
    @Test
    fun rectFor_withNeighborBox_isClampedToTheMidlineNotAcross() {
        val personA = BoundingBox(left = 200, top = 400, right = 360, bottom = 640) // 160 wide, dx = 48
        val personB = BoundingBox(left = 380, top = 400, right = 540, bottom = 640) // only 20px gap

        // no neighbor info -> old behavior, reaches past B's left edge
        val naive = PresentationFaceCropper.rectFor(personA, frameW, frameH)
        assertTrue("sanity: without neighbor info the margin does cross", naive.right > personB.left)

        // with neighbor info -> pulled back to B's near edge, not across it
        val safe = PresentationFaceCropper.rectFor(personA, frameW, frameH, others = listOf(personB))
        assertTrue("neighbor-aware crop must stop at or before B's left edge", safe.right <= personB.left)
        // and must never trim into A's own box
        assertTrue(safe.right >= personA.right)
    }

    @Test
    fun rectFor_withNeighborOnBothSides_clampsBothEdges() {
        val center = BoundingBox(left = 460, top = 400, right = 620, bottom = 640)
        val leftN = BoundingBox(left = 240, top = 400, right = 400, bottom = 640)
        val rightN = BoundingBox(left = 680, top = 400, right = 840, bottom = 640)
        val safe = PresentationFaceCropper.rectFor(center, frameW, frameH, others = listOf(leftN, rightN))
        assertTrue(safe.left >= leftN.right)
        assertTrue(safe.right <= rightN.left)
        assertTrue(safe.left <= center.left && safe.right >= center.right)
    }

    @Test
    fun rectFor_neighborThatIsActuallyTheSameFace_isIgnored() {
        val face = BoundingBox(400, 400, 560, 640)
        val nearDup = BoundingBox(402, 401, 561, 642) // same face, jitter
        val withDup = PresentationFaceCropper.rectFor(face, frameW, frameH, others = listOf(nearDup))
        val without = PresentationFaceCropper.rectFor(face, frameW, frameH)
        assertEquals("a near-identical box must not trim the crop", without, withDup)
    }

    @Test
    fun rectFor_degenerateBox_isRejectedAsUnusable() {
        val zero = BoundingBox(500, 500, 500, 500)
        val rect = PresentationFaceCropper.rectFor(zero, frameW, frameH)
        assertFalse(RecognitionCrop.isUsable(rect))
    }

    @Test
    fun rectFor_preservesAspectRatioIntent_widthAndHeightBothExpand() {
        val face = BoundingBox(400, 700, 600, 1000) // 200 x 300 -> 2:3
        val rect = PresentationFaceCropper.rectFor(face, frameW, frameH)
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        // both dimensions grew from the source box -- neither margin degenerates to zero
        assertTrue(w > 200)
        assertTrue(h > 300)
    }

    // wouldLeakNeighbor: the signal the representative-frame selector uses to
    // skip a frame whose neighbor-safe crop would still contain another face.
    @Test
    fun wouldLeakNeighbor_falseWhenNeighborIsCleanlySeparated() {
        val face = BoundingBox(200, 400, 360, 640)
        val far = BoundingBox(700, 400, 860, 640)
        assertFalse(
            PresentationFaceCropper.wouldLeakNeighbor(face, frameW, frameH, listOf(far)),
        )
    }

    @Test
    fun wouldLeakNeighbor_trueWhenNeighborOverlapsFaceOnEveryAxis() {
        // Neighbor overlaps the face box itself on both axes -> the edge
        // pull-back can't cut it, so the crop would leak.
        val face = BoundingBox(400, 400, 560, 640)
        val overlapping = BoundingBox(480, 460, 660, 700)
        assertTrue(
            PresentationFaceCropper.wouldLeakNeighbor(face, frameW, frameH, listOf(overlapping)),
        )
    }

    @Test
    fun wouldLeakNeighbor_ignoresAnOverSplitDuplicateOfTheSameFace() {
        val face = BoundingBox(400, 400, 560, 640)
        val nearDup = BoundingBox(403, 402, 562, 641)
        assertFalse(
            PresentationFaceCropper.wouldLeakNeighbor(face, frameW, frameH, listOf(nearDup)),
        )
    }

    // --- Phase 8.2 landmark-tight crop -------------------------------------

    private fun lm(t: com.example.ikyky.core.model.LandmarkType, x: Float, y: Float) =
        com.example.ikyky.core.model.Landmark(t, x, y)

    /** A face landmark set spanning eyes(y=560) to mouth(y=690), ears x∈[430,650]. */
    private fun faceLandmarks() = listOf(
        lm(com.example.ikyky.core.model.LandmarkType.LEFT_EYE, 480f, 560f),
        lm(com.example.ikyky.core.model.LandmarkType.RIGHT_EYE, 600f, 560f),
        lm(com.example.ikyky.core.model.LandmarkType.NOSE_BASE, 540f, 620f),
        lm(com.example.ikyky.core.model.LandmarkType.MOUTH_LEFT, 500f, 685f),
        lm(com.example.ikyky.core.model.LandmarkType.MOUTH_RIGHT, 580f, 685f),
        lm(com.example.ikyky.core.model.LandmarkType.MOUTH_BOTTOM, 540f, 700f),
        lm(com.example.ikyky.core.model.LandmarkType.LEFT_EAR, 430f, 590f),
        lm(com.example.ikyky.core.model.LandmarkType.RIGHT_EAR, 650f, 590f),
    )

    // Cropping from landmarks must IGNORE the oversized detection box: given a
    // huge head-and-shoulders box, the rect is driven by the landmark span, so
    // it is a small fraction of the frame.
    @Test
    fun landmarkRectFor_ignoresOversizedDetectionBox_producesTightPortrait() {
        val hugeBox = BoundingBox(0, 100, 1000, 1400) // ~93% of frame short edge
        val rect = PresentationFaceCropper.landmarkRectFor(hugeBox, faceLandmarks(), frameW, frameH)

        // landmark span is 220 wide (430..650) x 140 tall (560..700)
        // margins: side 0.45*220≈99 each; top 1.10*140≈154; bottom 0.65*140≈91
        // -> roughly x∈[331,749] (~418w), y∈[406,791] (~385h)
        assertTrue("crop width must be far below the frame", (rect.right - rect.left) < frameW / 2)
        assertTrue("crop height must be far below the frame", (rect.bottom - rect.top) < frameH / 2)
        // must still contain the whole face
        assertTrue(rect.left <= 430 && rect.right >= 650)
        assertTrue(rect.top <= 560 && rect.bottom >= 700)
        // more forehead room above the eyes than chin room below the mouth
        val aboveEyes = 560 - rect.top
        val belowMouth = rect.bottom - 700
        assertTrue("forehead margin should exceed chin margin", aboveEyes > belowMouth)
    }

    @Test
    fun landmarkRectFor_fallsBackToDetectionBoxWhenTooFewLandmarks() {
        val box = BoundingBox(300, 400, 700, 850)
        val sparse = listOf(
            lm(com.example.ikyky.core.model.LandmarkType.LEFT_EYE, 400f, 550f),
            lm(com.example.ikyky.core.model.LandmarkType.RIGHT_EYE, 600f, 550f),
        )
        val lmRect = PresentationFaceCropper.landmarkRectFor(box, sparse, frameW, frameH)
        val boxRect = PresentationFaceCropper.rectFor(box, frameW, frameH)
        assertEquals("with <4 core landmarks it must equal the detection-box rect", boxRect, lmRect)
    }

    @Test
    fun landmarkRectFor_staysInsideFrameBounds() {
        // face near the top-left; forehead margin would push above 0
        val lms = listOf(
            lm(com.example.ikyky.core.model.LandmarkType.LEFT_EYE, 60f, 40f),
            lm(com.example.ikyky.core.model.LandmarkType.RIGHT_EYE, 160f, 40f),
            lm(com.example.ikyky.core.model.LandmarkType.NOSE_BASE, 110f, 80f),
            lm(com.example.ikyky.core.model.LandmarkType.MOUTH_BOTTOM, 110f, 120f),
            lm(com.example.ikyky.core.model.LandmarkType.LEFT_EAR, 20f, 60f),
            lm(com.example.ikyky.core.model.LandmarkType.RIGHT_EAR, 200f, 60f),
        )
        val rect = PresentationFaceCropper.landmarkRectFor(
            BoundingBox(0, 0, 240, 200), lms, frameW, frameH,
        )
        assertTrue(rect.left >= 0 && rect.top >= 0)
        assertTrue(rect.right <= frameW && rect.bottom <= frameH)
        assertTrue(RecognitionCrop.isUsable(rect))
    }

    @Test
    fun landmarkRectFor_trimsAwayANeighbourFace() {
        // face landmarks centred ~x540; a neighbour box on the right at x∈[800,1000]
        val neighbour = BoundingBox(800, 400, 1000, 900)
        val rect = PresentationFaceCropper.landmarkRectFor(
            BoundingBox(200, 200, 900, 1200), faceLandmarks(), frameW, frameH, listOf(neighbour),
        )
        assertTrue("crop must not reach into the neighbour box", rect.right <= neighbour.left)
    }
}
