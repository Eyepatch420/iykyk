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
    fun frameLoopFraction_withNoPlan_isTheLoadFloor() {
        assertEquals(ProgressModel.stageFraction(ProcessingStage.EXTRACTING_FRAMES),
            ProgressModel.frameLoopFraction(0, 0), 1e-4f)
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
