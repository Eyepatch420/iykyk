package com.example.ikyky.shots

import com.example.ikyky.core.ml.shots.FrameSignalMath
import com.example.ikyky.core.ml.shots.ShotBoundary
import com.example.ikyky.core.ml.shots.ShotBoundaryMath
import com.example.ikyky.core.ml.shots.ShotScan
import com.example.ikyky.core.ml.shots.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6D — the every-frame shot / whip-pan detector's pure math.
 *
 * The scoring here decides where the ABSOLUTE tracking barriers land, so the
 * whip-pan reconstruction (paired blur-in / blur-out spikes fused into one span,
 * widened across the blurred run) is tested explicitly.
 */
class ShotBoundaryMathTest {

    // =======================================================================
    // NORMALISATION / COMBINATION
    // =======================================================================

    @Test
    fun percentileMatchesLinearInterpolation() {
        val x = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0)
        assertEquals(0.0, ShotBoundaryMath.percentile(x, 0.0), 1e-9)
        assertEquals(2.0, ShotBoundaryMath.percentile(x, 50.0), 1e-9)
        assertEquals(4.0, ShotBoundaryMath.percentile(x, 100.0), 1e-9)
        assertEquals(3.6, ShotBoundaryMath.percentile(x, 90.0), 1e-9)
    }

    @Test
    fun normalisationPutsThe99thPercentileAtAboutOne() {
        val x = DoubleArray(100) { it * 0.01 }
        val n = ShotBoundaryMath.normalizeByP99(x)
        assertEquals(1.0, ShotBoundaryMath.percentile(n, 99.0), 1e-9)
    }

    @Test
    fun normalisationOfAnAllZeroSignalDoesNotDivideByZero() {
        val n = ShotBoundaryMath.normalizeByP99(DoubleArray(10))
        assertTrue(n.all { it == 0.0 })
    }

    @Test
    fun combineWeightsTheThreeSignalsEqually() {
        // identical signals ⇒ the combination equals any one of them
        val s = DoubleArray(50) { it * 0.02 }
        val c = ShotBoundaryMath.combine(s.copyOf(), s.copyOf(), s.copyOf())
        val n = ShotBoundaryMath.normalizeByP99(s)
        for (i in c.indices) assertEquals(n[i], c[i], 1e-9)
    }

    // =======================================================================
    // SPIKE PICKING
    // =======================================================================

    /** A quiet series with sharp spikes at the given indices. */
    private fun series(size: Int, spikes: Map<Int, Double>, floor: Double = 0.05) =
        DoubleArray(size) { spikes[it] ?: floor }

    @Test
    fun aClearSpikeIsDetected_andItsFrameIndexIsTheLaterFrame() {
        // score[i] describes frame i -> i+1, so the boundary frame is i+1
        val s = series(100, mapOf(40 to 1.0))
        val b = ShotBoundaryMath.pickBoundaries(s, 25.0)
        assertEquals(1, b.size)
        assertEquals(41, b[0].frameIndex)
    }

    @Test
    fun quietMotionBelowTheAbsoluteFloorIsIgnored() {
        // 0.45 is typical handheld motion; the floor is 0.55
        val s = series(100, mapOf(40 to 0.45))
        assertTrue(ShotBoundaryMath.pickBoundaries(s, 25.0).isEmpty())
    }

    @Test
    fun nonMaximumNeighboursAreSuppressedWithinTheNmsRadius() {
        // a spike with slightly-lower shoulders 1 and 2 frames away
        val s = series(100, mapOf(39 to 0.90, 40 to 1.00, 41 to 0.95))
        val b = ShotBoundaryMath.pickBoundaries(s, 25.0)
        assertEquals("only the local max survives", 1, b.size)
        assertEquals(41, b[0].frameIndex)
    }

    @Test
    fun candidatesInTheEdgeGuardAreIgnored() {
        val s = series(100, mapOf(0 to 1.0, 98 to 1.0))
        val b = ShotBoundaryMath.pickBoundaries(s, 25.0)
        assertTrue("decode warm-up / tail spikes are skipped", b.none { it.frameIndex <= 1 })
    }

    @Test
    fun anEmptyScoreSeriesYieldsNoBoundaries() {
        assertTrue(ShotBoundaryMath.pickBoundaries(DoubleArray(0), 25.0).isEmpty())
    }

    @Test
    fun timestampsFollowTheFrameRate() {
        val s = series(100, mapOf(49 to 1.0))
        val b = ShotBoundaryMath.pickBoundaries(s, 25.0)
        assertEquals(50, b[0].frameIndex)
        assertEquals(2000L, b[0].timestampMs) // 50 / 25 fps
    }

    // =======================================================================
    // WHIP-PAN SPAN RECONSTRUCTION
    // =======================================================================

    private fun bnd(f: Int, score: Double = 1.0) = ShotBoundary(f, (f / 25.0 * 1000).toLong(), score)

    @Test
    fun pairedSpikesBecomeOneWhipPanSpan() {
        // The signature Phase 5F transition: blur-in and blur-out 7 frames apart.
        val sharp = DoubleArray(100) { 100.0 }
        val t = ShotBoundaryMath.reconstructTransitions(
            listOf(bnd(40), bnd(47)), sharp, 25.0, 100,
        )
        assertEquals(1, t.size)
        assertEquals(Transition.Kind.WHIP_PAN, t[0].kind)
        assertEquals(40, t[0].startFrame)
        assertEquals(47, t[0].endFrame)
    }

    @Test
    fun aLoneSpikeIsAHardCutAndGetsASmallPad() {
        val sharp = DoubleArray(100) { 100.0 }
        val t = ShotBoundaryMath.reconstructTransitions(listOf(bnd(40)), sharp, 25.0, 100)
        assertEquals(1, t.size)
        assertEquals(Transition.Kind.HARD_CUT, t[0].kind)
        assertEquals(39, t[0].startFrame)
        assertEquals(42, t[0].endFrame)
    }

    @Test
    fun spansAreWidenedAcrossTheBlurredRun() {
        // sharp everywhere except frames 36..50, which are heavily blurred
        val sharp = DoubleArray(100) { if (it in 36..50) 5.0 else 100.0 }
        val t = ShotBoundaryMath.reconstructTransitions(
            listOf(bnd(40), bnd(47)), sharp, 25.0, 100,
        )
        assertEquals(1, t.size)
        assertTrue("must widen back over the blur-in", t[0].startFrame <= 36)
        assertTrue("must widen forward over the blur-out", t[0].endFrame >= 51)
    }

    @Test
    fun spikesFurtherApartThanTheePairGapStaySeparateTransitions() {
        val sharp = DoubleArray(200) { 100.0 }
        val t = ShotBoundaryMath.reconstructTransitions(
            listOf(bnd(40), bnd(100)), sharp, 25.0, 200,
        )
        assertEquals(2, t.size)
    }

    @Test
    fun overlappingSpansAreMerged() {
        // two hard cuts one frame apart pad into each other
        val sharp = DoubleArray(100) { 100.0 }
        val t = ShotBoundaryMath.reconstructTransitions(
            listOf(bnd(40), bnd(41)), sharp, 25.0, 100,
        )
        assertEquals(1, t.size)
    }

    @Test
    fun noBoundariesYieldNoTransitions() {
        assertTrue(
            ShotBoundaryMath.reconstructTransitions(
                emptyList(), DoubleArray(100) { 100.0 }, 25.0, 100,
            ).isEmpty()
        )
    }

    // =======================================================================
    // THE BARRIER QUERY — what the tracker actually consumes
    // =======================================================================

    private fun scanWith(vararg spans: Pair<Int, Int>) = ShotScan(
        frameCount = 200,
        fps = 25.0,
        boundaries = emptyList(),
        transitions = spans.map {
            Transition(it.first, it.second, 0L, 0L, 1.0, Transition.Kind.WHIP_PAN)
        },
    )

    @Test
    fun crossesTransitionIsTrueWhenASpanLiesBetweenTwoFrames() {
        val scan = scanWith(40 to 47)
        assertTrue(scan.crossesTransition(35, 50))
        assertTrue(scan.crossesTransition(50, 35)) // order-independent
    }

    @Test
    fun crossesTransitionIsFalseWhenBothFramesAreOnTheSameSide() {
        val scan = scanWith(40 to 47)
        assertFalse(scan.crossesTransition(10, 39))
        assertFalse(scan.crossesTransition(47, 90))
    }

    @Test
    fun crossesTransitionIsFalseWithNoTransitions() {
        assertFalse(ShotScan.EMPTY.crossesTransition(0, 999))
    }

    @Test
    fun transitionAtFindsTheSpanContainingAFrame() {
        val scan = scanWith(40 to 47, 100 to 108)
        assertNotNull(scan.transitionAt(43))
        assertNotNull(scan.transitionAt(100))
        assertNull(scan.transitionAt(47)) // end is exclusive
        assertNull(scan.transitionAt(60))
    }

    // =======================================================================
    // CHEAP PER-FRAME SIGNALS
    // =======================================================================

    @Test
    fun identicalFramesScoreZeroOnEverySignal() {
        val g = FloatArray(64) { (it * 3 % 255).toFloat() }
        assertEquals(0.0, FrameSignalMath.meanAbsoluteDifference(g, g.copyOf()), 1e-12)
        val h = FloatArray(32) { 1f / 32f }
        assertEquals(0.0, FrameSignalMath.histogramDistance(h, h.copyOf()), 1e-12)
        val e = BooleanArray(64) { it % 3 == 0 }
        assertEquals(0.0, FrameSignalMath.edgeChangeRatio(e, e.copyOf()), 1e-12)
    }

    @Test
    fun aCompleteSceneReplacementScoresNearOne() {
        val black = FloatArray(64) { 0f }
        val white = FloatArray(64) { 255f }
        assertEquals(1.0, FrameSignalMath.meanAbsoluteDifference(black, white), 1e-9)

        val a = BooleanArray(64) { it < 32 }
        val b = BooleanArray(64) { it >= 32 }
        assertEquals("disjoint edge maps ⇒ ratio 1", 1.0, FrameSignalMath.edgeChangeRatio(a, b), 1e-9)
    }

    @Test
    fun histogramDistanceIsBoundedAndSymmetric() {
        val a = FloatArray(8) { if (it == 0) 1f else 0f }
        val b = FloatArray(8) { if (it == 7) 1f else 0f }
        val ab = FrameSignalMath.histogramDistance(a, b)
        val ba = FrameSignalMath.histogramDistance(b, a)
        assertEquals(ab, ba, 1e-12)
        assertTrue(ab > 0.0 && ab < 1.0)
    }

    @Test
    fun edgeChangeRatioOfTwoEmptyMapsIsZeroNotNaN() {
        val e = BooleanArray(64)
        assertEquals(0.0, FrameSignalMath.edgeChangeRatio(e, e.copyOf()), 1e-12)
    }

    @Test
    fun hsvHistogramIsL1Normalised() {
        val n = 100
        val h = IntArray(n) { it % 180 }
        val s = IntArray(n) { it * 2 % 256 }
        val v = IntArray(n) { it * 3 % 256 }
        val hist = FrameSignalMath.hsvHistogram(h, s, v, bins = 8)
        assertEquals(8 * 8 * 8, hist.size)
        assertEquals(1.0f, hist.sum(), 1e-5f)
    }

    @Test
    fun varianceOfLaplacianIsHigherForASharpImageThanAFlatOne() {
        val flat = FloatArray(16 * 16) { 128f }
        val checker = FloatArray(16 * 16) { if ((it / 16 + it % 16) % 2 == 0) 0f else 255f }
        val flatVar = FrameSignalMath.varianceOfLaplacian(flat, 16, 16)
        val sharpVar = FrameSignalMath.varianceOfLaplacian(checker, 16, 16)
        assertEquals(0.0, flatVar, 1e-9)
        assertTrue("a checkerboard is far sharper than a flat field", sharpVar > flatVar)
    }

    @Test
    fun thumbnailSizePreservesAspectRatio() {
        val (w, h) = FrameSignalMath.thumbnailSize(1080, 1920, 64)
        assertEquals(64, maxOf(w, h))
        assertEquals(36, w)
        assertEquals(64, h)
    }

    @Test
    fun grayscaleUsesLumaWeights() {
        val white = FrameSignalMath.toGray(intArrayOf(0xFFFFFFFF.toInt()))
        assertEquals(255f, white[0], 1e-3f)
        val black = FrameSignalMath.toGray(intArrayOf(0xFF000000.toInt()))
        assertEquals(0f, black[0], 1e-3f)
        val red = FrameSignalMath.toGray(intArrayOf(0xFFFF0000.toInt()))
        assertEquals(0.299f * 255f, red[0], 1e-2f)
    }

    @Test
    fun hsvConversionUsesOpenCvRanges() {
        // pure red: H=0, S=255, V=255
        val (h, s, v) = FrameSignalMath.toHsvChannels(intArrayOf(0xFFFF0000.toInt()))
        assertEquals(0, h[0])
        assertEquals(255, s[0])
        assertEquals(255, v[0])
        // pure green sits at H=120deg, i.e. 60 in OpenCV's halved scale
        val (h2, _, _) = FrameSignalMath.toHsvChannels(intArrayOf(0xFF00FF00.toInt()))
        assertEquals(60, h2[0])
    }
}
