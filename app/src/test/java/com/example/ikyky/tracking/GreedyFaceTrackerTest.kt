package com.example.ikyky.tracking

import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.ml.tracking.GreedyFaceTracker
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the Phase-2 short-term tracker. No Android, no ML Kit.
 *
 * Frames are 4 FPS (250 ms step). Boxes are in canonical pixels.
 */
class GreedyFaceTrackerTest {

    private var seq = 0
    private fun obs(
        frame: Int,
        box: BoundingBox,
        trackingId: Int? = null,
        quality: Float = 1f,
    ) = FaceObservation(
        id = "o${seq++}",
        frameIndex = frame,
        timestampMs = frame * 250L,
        face = DetectedFace(boundingBox = box, trackingId = trackingId),
        qualityScore = quality,
        usable = true,
    )

    private fun box(cx: Int, cy: Int, size: Int = 100) =
        BoundingBox(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

    private val tracker get() = GreedyFaceTracker()

    // 1. creation
    @Test
    fun singleFace_acrossFrames_isOneTracklet() {
        val obsList = (0..4).map { obs(it, box(500, 500 + it * 5)) }
        val tracklets = tracker.buildTracklets(obsList)
        assertEquals(1, tracklets.size)
        assertEquals(5, tracklets.first().frameCount)
    }

    // 2. continuation via ML Kit tracking id even when the box jumps far
    @Test
    fun trackingId_keepsContinuity_whenBoxJumps() {
        val obsList = listOf(
            obs(0, box(200, 300), trackingId = 7),
            obs(1, box(900, 1400), trackingId = 7), // large jump, low IoU
        )
        val tracklets = tracker.buildTracklets(obsList)
        assertEquals(1, tracklets.size)
        assertEquals(2, tracklets.first().frameCount)
    }

    // 3. IoU fallback when no tracking id
    @Test
    fun iouFallback_linksOverlappingBoxes_withoutTrackingId() {
        val obsList = listOf(
            obs(0, box(500, 500)),
            obs(1, box(508, 505)), // heavy overlap
            obs(2, box(515, 511)),
        )
        val tracklets = tracker.buildTracklets(obsList)
        assertEquals(1, tracklets.size)
        assertEquals(3, tracklets.first().frameCount)
    }

    // 4. termination after a long gap → two tracklets
    @Test
    fun longGap_terminatesTracklet_reappearanceIsNew() {
        val obsList = listOf(
            obs(0, box(500, 500)),
            obs(1, box(500, 500)),
            // gap: frames 2..10 missing (2500ms >> MAX_TRACK_GAP_MS)
            obs(11, box(500, 500)),
            obs(12, box(500, 500)),
        )
        val tracklets = tracker.buildTracklets(obsList).sortedBy { it.startTimestampMs }
        assertEquals(2, tracklets.size)
        assertEquals(2, tracklets[0].frameCount)
        assertEquals(2, tracklets[1].frameCount)
        assertNotEquals(tracklets[0].id, tracklets[1].id)
    }

    // 5. missed-frame tolerance: one missing sample keeps the tracklet open
    @Test
    fun oneMissedFrame_isTolerated() {
        val obsList = listOf(
            obs(0, box(500, 500)),
            obs(1, box(500, 500)),
            // frame 2 missing → 500ms gap between detections, == MAX_TRACK_GAP_MS, tolerated
            obs(3, box(503, 502)),
            obs(4, box(505, 504)),
        )
        val tracklets = tracker.buildTracklets(obsList)
        assertEquals(1, tracklets.size)
        assertEquals(4, tracklets.first().frameCount)
    }

    // 6. two faces in the same frame → two tracklets, each observation used once
    @Test
    fun twoFacesPerFrame_produceTwoTracklets() {
        val obsList = ArrayList<FaceObservation>()
        for (f in 0..5) {
            obsList += obs(f, box(300, 400 + f * 3))   // person A, left
            obsList += obs(f, box(800, 1200 + f * 3))  // person B, right
        }
        val tracklets = tracker.buildTracklets(obsList)
        assertEquals(2, tracklets.size)
        tracklets.forEach { assertEquals(6, it.frameCount) }
        // no observation double-counted
        val totalObs = tracklets.sumOf { it.frameCount }
        assertEquals(obsList.size, totalObs)
    }

    // 7. same tracking id, separated in time → separate tracklets (not merged)
    @Test
    fun sameTrackingId_butTimeGap_staysSeparate() {
        val obsList = listOf(
            obs(0, box(500, 500), trackingId = 3),
            obs(1, box(500, 500), trackingId = 3),
            obs(20, box(500, 500), trackingId = 3), // long gap
            obs(21, box(500, 500), trackingId = 3),
        )
        val tracklets = tracker.buildTracklets(obsList)
        assertEquals(2, tracklets.size)
    }

    // 8. two people appearing simultaneously mid-video, both leaving together
    @Test
    fun twoPeople_simultaneousSegment_areTwoIndependentTracklets() {
        val obsList = ArrayList<FaceObservation>()
        for (f in 40..46) {
            obsList += obs(f, box(250, 350), trackingId = 100)
            obsList += obs(f, box(820, 900), trackingId = 101)
        }
        val tracklets = tracker.buildTracklets(obsList).sortedBy { it.lastBox.left }
        assertEquals(2, tracklets.size)
        assertTrue(tracklets[0].lastBox.left < tracklets[1].lastBox.left)
    }

    // 9. empty input
    @Test
    fun emptyObservations_yieldNoTracklets() {
        assertTrue(tracker.buildTracklets(emptyList()).isEmpty())
    }

    // rule #8: a changed ML Kit tracking id must NOT be bridged by the spatial
    // fallback — prefer a false split over a false merge.
    @Test
    fun spatialFallback_doesNotBridgeDifferentTrackingIds_byDefault() {
        // two frames, boxes overlap heavily (IoU high) but tracking id flips 12→13
        val obsList = listOf(
            obs(0, box(500, 500, 400), trackingId = 12),
            obs(0, box(500, 500, 400), trackingId = 12), // keep tracklet 0 alive at frame 0
            obs(1, box(505, 503, 400), trackingId = 13), // same place, DIFFERENT id
        )
        // default GreedyFaceTracker: bridgeAcrossTrackingIds = false
        val tracklets = GreedyFaceTracker().buildTracklets(obsList)
        // the id-13 observation must open its own tracklet, not extend the id-12 one
        assertTrue("expected the flipped id to split", tracklets.size >= 2)
        assertTrue(tracklets.none { t ->
            t.observations.map { it.trackingId }.toSet() == setOf(12, 13)
        })
    }

    @Test
    fun spatialFallback_bridgesDifferentTrackingIds_whenExplicitlyEnabled() {
        val obsList = listOf(
            obs(0, box(500, 500, 400), trackingId = 12),
            obs(1, box(505, 503, 400), trackingId = 13),
        )
        val tracklets = GreedyFaceTracker(bridgeAcrossTrackingIds = true).buildTracklets(obsList)
        assertEquals(1, tracklets.size)
        assertEquals(2, tracklets.first().frameCount)
    }

    // streaming session parity with batch
    @Test
    fun streamingSession_matchesBatch() {
        val frames = (0..8).map { f ->
            listOf(obs(f, box(400, 400 + f * 4)), obs(f, box(900, 1300 - f * 4)))
        }
        val batch = tracker.buildTracklets(frames.flatten())

        val t = tracker
        val session = t.newSession()
        var closed = 0
        frames.forEachIndexed { i, group -> closed += session.onFrame(i * 250L, group).size }
        closed += session.finish().size
        assertEquals(batch.size, closed)
    }
}
