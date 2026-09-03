package com.example.ikyky.processing

import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.ml.tracking.Tracklet
import com.example.ikyky.core.ml.tracking.TrackletState
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.features.processing.domain.usecase.AppearanceSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSegmenterTest {

    private var seq = 0
    private fun obs(frame: Int, q: Float = 1f) = FaceObservation(
        id = "o${seq++}",
        frameIndex = frame,
        timestampMs = frame * 250L,
        face = DetectedFace(BoundingBox(0, 0, 100, 100), trackingId = null),
        qualityScore = q,
        usable = true,
    )

    private fun tracklet(id: Long, frames: IntRange, q: (Int) -> Float = { 1f }) =
        Tracklet(
            id = id,
            observations = frames.map { obs(it, q(it)) },
            state = TrackletState.CLOSED,
        )

    @Test
    fun shortTracklet_isDroppedAsNoise() {
        // 1 observation, 0 ms span → below both gates
        val seg = AppearanceSegmenter().segment(listOf(tracklet(1, 5..5)))
        assertTrue(seg.isEmpty())
    }

    @Test
    fun twoFrameShortDuration_isDropped() {
        // frames 0..1 → 250 ms span, below MIN_APPEARANCE_DURATION_MS (300)
        val seg = AppearanceSegmenter().segment(listOf(tracklet(1, 0..1)))
        assertTrue(seg.isEmpty())
    }

    @Test
    fun validSegment_becomesOneAppearance() {
        // frames 0..4 → 1000 ms span, 5 obs
        val seg = AppearanceSegmenter().segment(listOf(tracklet(1, 0..4)))
        assertEquals(1, seg.size)
        assertEquals(0L, seg[0].startTimestampMs)
        assertEquals(1000L, seg[0].endTimestampMs)
        assertEquals(5, seg[0].observationCount)
    }

    @Test
    fun separateTracklets_sameFakeIdentity_stayTwoAppearances() {
        // NOT merged — global identity is a later phase
        val a = tracklet(1, 0..4)
        val b = tracklet(2, 40..46)
        val seg = AppearanceSegmenter().segment(listOf(a, b))
        assertEquals(2, seg.size)
        assertTrue(seg[0].startTimestampMs < seg[1].startTimestampMs)
    }

    @Test
    fun twoSimultaneousTracklets_yieldTwoAppearances() {
        val a = tracklet(10, 20..27)
        val b = tracklet(11, 20..27)
        val seg = AppearanceSegmenter().segment(listOf(a, b))
        assertEquals(2, seg.size)
    }

    @Test
    fun bestFrame_isTheHighestQualityObservation() {
        val seg = AppearanceSegmenter().segment(
            listOf(tracklet(1, 0..6) { f -> if (f == 3) 0.9f else 0.2f })
        )
        assertEquals(1, seg.size)
        assertEquals(3, seg[0].bestFrameIndex)
        assertEquals(0.9f, seg[0].bestQuality, 1e-4f)
    }
}
