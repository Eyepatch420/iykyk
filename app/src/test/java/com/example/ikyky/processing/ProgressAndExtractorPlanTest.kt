package com.example.ikyky.processing

import com.example.ikyky.core.media.FrameSamplingRequest
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.features.processing.domain.model.ProcessingStage
import com.example.ikyky.features.processing.domain.usecase.ProgressModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressAndExtractorPlanTest {

    @Test
    fun frameLoopFraction_isMonotonicAndBounded() {
        var prev = -1f
        for (done in 0..120) {
            val f = ProgressModel.frameLoopFraction(done, 120)
            assertTrue("fraction $f out of [0,1]", f in 0f..1f)
            assertTrue("fraction not monotonic at $done", f >= prev)
            prev = f
        }
        assertTrue(ProgressModel.frameLoopFraction(120, 120) <= 0.95f + 1e-4f)
        assertTrue(ProgressModel.frameLoopFraction(60, 120) > ProgressModel.frameLoopFraction(0, 120))
    }

    @Test
    fun shotScanFraction_isMonotonicAndSitsBelowTheDetectLoop() {
        // Phase 6.1: the shot scan owns its own band [LOAD_END, SHOT_SCAN_END].
        var prev = -1f
        for (done in 0..750) {
            val f = ProgressModel.shotScanFraction(done, 750)
            assertTrue("fraction $f out of [0,1]", f in 0f..1f)
            assertTrue("shot-scan fraction not monotonic at $done", f >= prev)
            prev = f
        }
        // the scan starts where "extracting frames" starts...
        assertEquals(
            ProgressModel.stageFraction(ProcessingStage.EXTRACTING_FRAMES),
            ProgressModel.shotScanFraction(0, 750),
            1e-4f,
        )
        // ...and ends exactly where the sampled detect loop begins.
        assertEquals(
            ProgressModel.frameLoopFraction(0, 240),
            ProgressModel.shotScanFraction(750, 750),
            1e-4f,
        )
        // the scan must never overtake the detect loop's progress region
        assertTrue(
            ProgressModel.shotScanFraction(750, 750) <= ProgressModel.frameLoopFraction(1, 240) + 1e-4f,
        )
    }

    @Test
    fun frameLoopFraction_withNoPlan_isTheShotScanEnd() {
        // With no sampled frames planned the detect loop contributes nothing, so
        // its "0%" is the point the shot scan handed over.
        assertEquals(
            ProgressModel.stageFraction(ProcessingStage.DETECTING_FACES),
            ProgressModel.frameLoopFraction(0, 0),
            1e-4f,
        )
    }

    @Test
    fun stageFraction_completed_isOne() {
        assertEquals(1f, ProgressModel.stageFraction(ProcessingStage.COMPLETED), 0f)
    }

    @Test
    fun plannedFrameCount_matchesFpsAndDuration() {
        // pure arithmetic mirror of MediaMetadataFrameExtractor.plannedFrameCount
        val meta = VideoMetadata(
            uriString = "file://x",
            durationMs = 30_000L,
            rawWidth = 1080,
            rawHeight = 1920,
            rotationDegrees = 0,
        )
        val request = FrameSamplingRequest(uriString = "file://x", metadata = meta, fps = 4f)
        val stepMs = (1000f / request.fps).toLong()
        val expected = ((meta.durationMs - 1) / stepMs).toInt() + 1
        assertEquals(120, expected)
    }

    @Test
    fun videoMetadata_uprightDimensions_accountForRotation() {
        val landscapeStored = VideoMetadata("u", 1000, 1920, 1080, rotationDegrees = 90)
        assertEquals(1080, landscapeStored.displayWidth)
        assertEquals(1920, landscapeStored.displayHeight)
        assertTrue(landscapeStored.isPortrait)

        val trueLandscape = VideoMetadata("u", 1000, 1920, 1080, rotationDegrees = 0)
        assertEquals(1920, trueLandscape.displayWidth)
        assertTrue(!trueLandscape.isPortrait)
    }
}
