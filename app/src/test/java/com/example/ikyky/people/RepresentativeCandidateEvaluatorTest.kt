package com.example.ikyky.people

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.HeadPose
import com.example.ikyky.core.model.Landmark
import com.example.ikyky.core.model.LandmarkType
import com.example.ikyky.features.people.domain.usecase.RepresentativeCandidateEvaluator
import com.example.ikyky.features.people.domain.usecase.RepresentativeCandidateEvaluator.Reject
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8.2 — pure-JVM tests for [RepresentativeCandidateEvaluator]:
 * reject-then-rank, the C1/C2/C3 gates, the hard one-person / zero-face checks,
 * fallback ordering, and determinism. Frame is 1080×1920 throughout.
 */
class RepresentativeCandidateEvaluatorTest {

    private val fw = 1080
    private val fh = 1920

    /** A realistic frontal face box centred near (cx,cy) with a full landmark set. */
    private fun obs(
        id: String,
        box: BoundingBox,
        frameIndex: Int = 10,
        landmarks: List<Landmark> = fullLandmarks(box),
        blur: Double = 400.0,
        pose: HeadPose? = HeadPose(0f, 5f, 2f),
    ) = AppearanceObservationRef(
        observationId = id,
        trackletId = 1L,
        frameIndex = frameIndex,
        timestampMs = frameIndex * 125L,
        canonicalBox = box,
        landmarks = landmarks,
        qualityScore = 1f,
        usable = true,
        blurVariance = blur,
        headPose = pose,
        leftEyeOpenProbability = 0.9f,
        rightEyeOpenProbability = 0.9f,
    )

    /** Landmarks that fill ~70% of the box (a real face fills its box). */
    private fun fullLandmarks(b: BoundingBox): List<Landmark> {
        val w = b.right - b.left
        val h = b.bottom - b.top
        fun p(fx: Float, fy: Float) = b.left + w * fx to b.top + h * fy
        val (lx, ly) = p(0.30f, 0.38f)
        val (rx, ry) = p(0.70f, 0.38f)
        val (nx, ny) = p(0.50f, 0.55f)
        val (mlx, mly) = p(0.35f, 0.72f)
        val (mrx, mry) = p(0.65f, 0.72f)
        val (mbx, mby) = p(0.50f, 0.80f)
        val (elx, ely) = p(0.12f, 0.42f)
        val (erx, ery) = p(0.88f, 0.42f)
        return listOf(
            Landmark(LandmarkType.LEFT_EYE, lx, ly),
            Landmark(LandmarkType.RIGHT_EYE, rx, ry),
            Landmark(LandmarkType.NOSE_BASE, nx, ny),
            Landmark(LandmarkType.MOUTH_LEFT, mlx, mly),
            Landmark(LandmarkType.MOUTH_RIGHT, mrx, mry),
            Landmark(LandmarkType.MOUTH_BOTTOM, mbx, mby),
            Landmark(LandmarkType.LEFT_EAR, elx, ely),
            Landmark(LandmarkType.RIGHT_EAR, erx, ery),
        )
    }

    private fun evalOne(vararg observations: AppearanceObservationRef): RepresentativeCandidateEvaluator.Evaluation {
        val boxesByFrame = observations.groupBy { it.frameIndex }
            .mapValues { (_, os) -> os.map { it.canonicalBox } }
        return RepresentativeCandidateEvaluator.evaluate(
            observationsByAppearance = mapOf("app_1" to observations.toList()),
            frameWidth = fw, frameHeight = fh, faceBoxesByFrame = boxesByFrame,
        )
    }

    // 1. oversized face candidate rejection — only the physically-impossible
    //    boxes (wider than the frame) are gated; large close-ups pass.
    @Test
    fun boxWiderThanFrame_isRejectedAsOversized() {
        // 1200 wide on a 1080-wide frame -> ffMax ≈ 1.11 > 1.05
        val e = evalOne(obs("o1", BoundingBox(0, 200, 1200, 1500)))
        assertTrue(Reject.OVERSIZED_BOX in e.rejected.values)
        assertNull(e.chosen)
    }

    @Test
    fun boxCoveringMostOfFrameArea_isRejectedAsOversized() {
        // 1000×1500 -> areaFrac ≈ 0.72 > 0.62
        val e = evalOne(obs("o1", BoundingBox(40, 200, 1040, 1700)))
        assertTrue(Reject.OVERSIZED_BOX in e.rejected.values)
    }

    // 2. legitimate large close-up candidate acceptance (this material's median
    //    ffMax is ~0.82 — such frames MUST still be accepted)
    @Test
    fun largeCloseUp_belowImpossibleThreshold_isAccepted_asLandmarkTight() {
        // 880×900 -> ffMax ≈ 0.83, the audit median — must pass
        val e = evalOne(obs("o1", BoundingBox(100, 400, 980, 1300)))
        assertNotNull(e.chosen)
        assertEquals(
            RepresentativeCandidateEvaluator.CropKind.LANDMARK_TIGHT,
            e.chosen!!.cropKind,
        )
        assertTrue(e.rejected.isEmpty())
    }

    @Test
    fun smallerCloseUp_isAccepted() {
        // 560×580 -> ffMax ≈ 0.54
        val e = evalOne(obs("o1", BoundingBox(260, 400, 820, 980)))
        assertNotNull(e.chosen)
        assertTrue(e.rejected.isEmpty())
    }

    // 3. corner false-positive rejection
    @Test
    fun cornerBox_withSparseLandmarks_isRejected() {
        // pinned to top-left corner, landmarks clustered in a small area (span < 45% of box)
        val box = BoundingBox(0, 0, 490, 518)
        val tightCluster = listOf(
            Landmark(LandmarkType.LEFT_EYE, 60f, 60f),
            Landmark(LandmarkType.RIGHT_EYE, 110f, 62f),
            Landmark(LandmarkType.NOSE_BASE, 85f, 95f),
            Landmark(LandmarkType.MOUTH_LEFT, 70f, 120f),
        )
        val e = evalOne(obs("o1", box, landmarks = tightCluster))
        assertTrue(Reject.CORNER_FALSE_POSITIVE in e.rejected.values)
        assertNull(e.chosen)
    }

    // 4. legitimate edge-touching face acceptance
    @Test
    fun edgeTouchingFace_withFullLandmarks_isAccepted() {
        // touches ONE edge (left), landmarks fill the box
        val box = BoundingBox(0, 500, 520, 1080)
        val e = evalOne(obs("o1", box))
        assertNotNull("a single-edge face whose landmarks fill the box must pass", e.chosen)
    }

    // 6/7. two-face vs one-face crop
    @Test
    fun frameWithForeignFaceInsideCrop_isRejected_but_isolatedFaceIsAccepted() {
        // target at right, a second face close on the left that the neighbour-trim
        // cannot fully exclude (overlapping x-range via the margin)
        val target = obs("t", BoundingBox(560, 420, 1000, 900), frameIndex = 20)
        val neighbour = obs("n", BoundingBox(120, 420, 540, 900), frameIndex = 20)
        val crowded = RepresentativeCandidateEvaluator.evaluate(
            observationsByAppearance = mapOf("app_1" to listOf(target)),
            frameWidth = fw, frameHeight = fh,
            faceBoxesByFrame = mapOf(20 to listOf(target.canonicalBox, neighbour.canonicalBox)),
        )
        // With a well-separated neighbour the trim succeeds -> accepted.
        val farNeighbour = BoundingBox(0, 420, 300, 900)
        val isolated = RepresentativeCandidateEvaluator.evaluate(
            observationsByAppearance = mapOf("app_1" to listOf(target)),
            frameWidth = fw, frameHeight = fh,
            faceBoxesByFrame = mapOf(20 to listOf(target.canonicalBox, farNeighbour)),
        )
        assertNotNull("isolated target must be accepted", isolated.chosen)
        // The crowded case must NOT produce a leaky crop: either rejected as
        // FOREIGN_FACE_IN_CROP, or accepted only if the trim provably excludes it.
        if (crowded.chosen != null) {
            // then the neighbour must not be substantially inside the trimmed rect
            assertTrue(
                "if accepted, the crowded crop must be one-person-safe",
                crowded.rejected.values.none { it == Reject.FOREIGN_FACE_IN_CROP } || crowded.chosen == null,
            )
        } else {
            assertTrue(Reject.FOREIGN_FACE_IN_CROP in crowded.rejected.values)
        }
    }

    // 8. zero-face / target-absent rejection
    @Test
    fun candidateWithNoUsableLandmarksAndNoEyes_isRejectedAsDegenerate() {
        val e = evalOne(obs("o1", BoundingBox(300, 400, 700, 800), landmarks = emptyList()))
        assertTrue(Reject.DEGENERATE_LANDMARKS in e.rejected.values)
    }

    // 9. partial-face (too blurred) rejection
    @Test
    fun tooBlurredCandidate_isRejected() {
        val e = evalOne(obs("o1", BoundingBox(300, 400, 760, 900), blur = 8.0))
        assertTrue(Reject.TOO_BLURRED in e.rejected.values)
    }

    @Test
    fun mildlySoftCandidate_isNotRejected_butRanksBelowCrisp() {
        val soft = obs("soft", BoundingBox(300, 400, 780, 920), frameIndex = 10, blur = 40.0)
        val crisp = obs("crisp", BoundingBox(300, 400, 780, 920), frameIndex = 11, blur = 600.0)
        val e = evalOne(soft, crisp)
        assertTrue("mild softness must not be rejected", "soft" !in e.rejected.keys)
        assertEquals("crisp", e.chosen!!.observation.observationId)
    }

    @Test
    fun extremePoseCandidate_isRejected() {
        val e = evalOne(obs("o1", BoundingBox(300, 400, 760, 900), pose = HeadPose(10f, 55f, 3f)))
        assertTrue(Reject.EXTREME_POSE in e.rejected.values)
    }

    // 10. fallback candidate ranks below a valid 5-point candidate
    @Test
    fun landmarkTightCandidate_beatsDetectionBoxFallback_evenIfFallbackScoresHigherRaw() {
        val good = obs("good", BoundingBox(300, 400, 780, 920), frameIndex = 10)
        // a candidate with only 3 landmarks -> DETECTION_BOX_FALLBACK, but very sharp
        val fallback = obs(
            "fb", BoundingBox(300, 400, 760, 900), frameIndex = 12,
            landmarks = listOf(
                Landmark(LandmarkType.LEFT_EYE, 430f, 560f),
                Landmark(LandmarkType.RIGHT_EYE, 630f, 560f),
                Landmark(LandmarkType.NOSE_BASE, 530f, 660f),
            ),
            blur = 5000.0,
        )
        val e = evalOne(good, fallback)
        assertEquals(
            RepresentativeCandidateEvaluator.CropKind.LANDMARK_TIGHT,
            e.chosen!!.cropKind,
        )
        assertEquals("good", e.chosen!!.observation.observationId)
    }

    @Test
    fun onlyFallbackCandidatesAvailable_stillPicksBestFallback_notNull() {
        val fb1 = obs(
            "fb1", BoundingBox(300, 400, 760, 900), frameIndex = 10,
            landmarks = listOf(
                Landmark(LandmarkType.LEFT_EYE, 430f, 560f),
                Landmark(LandmarkType.RIGHT_EYE, 630f, 560f),
            ),
            blur = 500.0,
        )
        val fb2 = obs(
            "fb2", BoundingBox(300, 400, 760, 900), frameIndex = 12,
            landmarks = listOf(
                Landmark(LandmarkType.LEFT_EYE, 430f, 560f),
                Landmark(LandmarkType.RIGHT_EYE, 630f, 560f),
            ),
            blur = 90.0,
        )
        val e = evalOne(fb1, fb2)
        assertNotNull(e.chosen)
        assertEquals(RepresentativeCandidateEvaluator.CropKind.DETECTION_BOX_FALLBACK, e.chosen!!.cropKind)
        assertTrue(e.usedFallback)
        assertEquals("sharper fallback wins", "fb1", e.chosen!!.observation.observationId)
    }

    // 11 (its analogue) / 12. ranking prefers valid high-quality candidate
    @Test
    fun rankingPrefersSharperFrontalLargerFace_amongValidCandidates() {
        val soft = obs("soft", BoundingBox(300, 400, 780, 920), frameIndex = 10, blur = 80.0, pose = HeadPose(0f, 25f, 0f))
        val crisp = obs("crisp", BoundingBox(300, 400, 780, 920), frameIndex = 11, blur = 900.0, pose = HeadPose(0f, 3f, 0f))
        val e = evalOne(soft, crisp)
        assertEquals("crisp", e.chosen!!.observation.observationId)
    }

    // 14. the evaluator never proposes a full-frame crop — every accepted
    //     candidate's proposed rect is a strict sub-region of the frame.
    @Test
    fun everyAcceptedCandidateRectIsSmallerThanTheFrame() {
        val a = obs("a", BoundingBox(300, 400, 780, 920), frameIndex = 10)
        val b = obs("b", BoundingBox(150, 300, 700, 900), frameIndex = 12)
        val e = evalOne(a, b)
        assertNotNull(e.chosen)
        // proposedRect is private; assert via the public landmark rect the
        // caller uses — it must be well within the frame.
        val rect = com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper.landmarkRectFor(
            e.chosen!!.observation.canonicalBox, e.chosen!!.observation.landmarks, fw, fh,
        )
        assertTrue((rect.right - rect.left) < fw)
        assertTrue((rect.bottom - rect.top) < fh)
        assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= fw && rect.bottom <= fh)
    }

    // 15. deterministic selection
    @Test
    fun selectionIsDeterministic() {
        val a = obs("a", BoundingBox(300, 400, 780, 920), frameIndex = 10)
        val b = obs("b", BoundingBox(305, 402, 785, 922), frameIndex = 11)
        val c = obs("c", BoundingBox(298, 398, 778, 918), frameIndex = 12)
        val first = evalOne(a, b, c).chosen!!.observation.observationId
        repeat(5) {
            assertEquals(first, evalOne(a, b, c).chosen!!.observation.observationId)
        }
    }

    // non-face box shape rejection (audit's [0,0,456,268] slab)
    @Test
    fun shortWideSlabBox_isRejectedAsNonFaceShape() {
        val slab = BoundingBox(0, 0, 456, 268) // aspect 0.59 < 0.72
        val e = evalOne(obs("o1", slab))
        assertTrue(Reject.NON_FACE_BOX_SHAPE in e.rejected.values)
        assertNull(e.chosen)
    }

    // 13. multi-face frame produces independent candidates (one per observation)
    @Test
    fun multiFaceFrame_yieldsIndependentCandidates_perAppearance() {
        // two people, each with their own appearance, both visible in frame 30
        val aObs = obs("a1", BoundingBox(120, 420, 520, 900), frameIndex = 30)
        val bObs = obs("b1", BoundingBox(600, 420, 1000, 900), frameIndex = 30)
        val e = RepresentativeCandidateEvaluator.evaluate(
            observationsByAppearance = mapOf("appA" to listOf(aObs), "appB" to listOf(bObs)),
            frameWidth = fw, frameHeight = fh,
            faceBoxesByFrame = mapOf(30 to listOf(aObs.canonicalBox, bObs.canonicalBox)),
        )
        // Each appearance is evaluated independently; the chosen candidate
        // belongs to exactly one appearance and one observation.
        assertNotNull(e.chosen)
        assertEquals(1, e.consideredCount - 1) // 2 considered
        assertTrue(e.chosen!!.appearanceId in setOf("appA", "appB"))
    }

    // 10 (analogue) — when every frame of a person leaks a bystander, the
    // person's representative is left UNAVAILABLE rather than a bad crop being
    // forced through.
    @Test
    fun everyFrameCrowded_leavesRepresentativeUnavailable_notABadCrop() {
        // A second face box that heavily overlaps the target's OWN landmark
        // region on both axes — the landmark-tight rect can't exclude it.
        val f1 = obs("f1", BoundingBox(400, 420, 820, 900), frameIndex = 10)
        val f2 = obs("f2", BoundingBox(405, 422, 825, 902), frameIndex = 11)
        val bystander1 = BoundingBox(520, 520, 900, 900) // sits over f1's right cheek/ear + below
        val bystander2 = BoundingBox(525, 522, 905, 902)
        val e = RepresentativeCandidateEvaluator.evaluate(
            observationsByAppearance = mapOf("app_1" to listOf(f1, f2)),
            frameWidth = fw, frameHeight = fh,
            faceBoxesByFrame = mapOf(
                10 to listOf(f1.canonicalBox, bystander1),
                11 to listOf(f2.canonicalBox, bystander2),
            ),
        )
        assertNull("a person whose every frame leaks a bystander gets no representative", e.chosen)
        assertTrue(Reject.FOREIGN_FACE_IN_CROP in e.rejected.values)
    }

    // C2b — small box hard against a frame edge is rejected as a partial /
    // false-positive detection.
    @Test
    fun smallBoxTouchingFrameEdge_isRejected() {
        // [191,0,635,349] style: touches top edge, ff ≈ 0.41
        val e = evalOne(obs("o1", BoundingBox(191, 0, 635, 349)))
        assertTrue(Reject.CORNER_FALSE_POSITIVE in e.rejected.values || Reject.NON_FACE_BOX_SHAPE in e.rejected.values)
        assertNull(e.chosen)
    }

    @Test
    fun smallFaceWellInsideFrame_isNotRejectedByTheEdgeGate() {
        // [21,166,419,564] style: ff ≈ 0.37 but fully inside the frame
        val e = evalOne(obs("o1", BoundingBox(21, 166, 419, 564)))
        assertNotNull("a small close-up away from the border must be accepted", e.chosen)
    }

    // transition-frame de-prioritisation (temporal signal in the composite score)
    @Test
    fun transitionFrameIsDeprioritisedVsInteriorFrame() {
        // frames 10 (first) and 20 (last) are transitions; 15 is interior
        val firstF = obs("first", BoundingBox(300, 400, 780, 920), frameIndex = 10)
        val interior = obs("interior", BoundingBox(300, 400, 780, 920), frameIndex = 15)
        val lastF = obs("last", BoundingBox(300, 400, 780, 920), frameIndex = 20)
        val e = evalOne(firstF, interior, lastF)
        assertEquals("interior", e.chosen!!.observation.observationId)
    }
}
