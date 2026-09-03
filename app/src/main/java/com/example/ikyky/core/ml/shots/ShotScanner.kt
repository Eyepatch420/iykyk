package com.example.ikyky.core.ml.shots

import android.graphics.Bitmap
import com.example.ikyky.core.common.constants.PipelineDefaults

/**
 * Scans a video's frames for shot changes / whip-pan transitions.
 *
 * Implementations decode frames themselves; [DefaultShotScanner] holds the frame
 * -> signal -> score logic and is fed one bitmap at a time so peak memory stays
 * at two thumbnails regardless of video length.
 */
interface ShotScanner {
    /**
     * @param frameCount total frames that will be supplied
     * @param fps decode frame rate (used only to stamp timestamps)
     */
    fun newSession(frameCount: Int, fps: Double): Session

    interface Session {
        /** Feed the next decoded frame, in order. The bitmap is not retained. */
        fun onFrame(bitmap: Bitmap)

        /** No more frames — computes boundaries and transition spans. */
        fun finish(): ShotScan
    }
}

/**
 * The frozen every-frame shot detector (Phase 5I §9.2).
 *
 * Per frame it derives three cheap signals from a 64 px thumbnail — grayscale
 * MAD, a 32-bin HSV chi-square, and an edge-change ratio — plus a
 * variance-of-Laplacian sharpness from a 256 px thumbnail. Consecutive frames are
 * compared, the three signals are each normalised by their own 99th percentile
 * and equally weighted, and spikes above `max(0.55, mean + 2.5σ)` that are local
 * maxima become boundaries. [ShotBoundaryMath.reconstructTransitions] then pairs
 * blur-in/blur-out spikes into whip-pan spans.
 *
 * No neural network, no detector, no embeddings — this must be cheap enough to
 * run on every decoded frame, which is the whole point: sampling at 8 FPS would
 * step straight over a 7-frame whip-pan.
 */
class DefaultShotScanner : ShotScanner {

    override fun newSession(frameCount: Int, fps: Double): ShotScanner.Session =
        SessionImpl(frameCount, fps)

    private class SessionImpl(
        private val expectedFrameCount: Int,
        private val fps: Double,
    ) : ShotScanner.Session {

        private val mad = ArrayList<Double>(expectedFrameCount.coerceAtLeast(16))
        private val hist = ArrayList<Double>(expectedFrameCount.coerceAtLeast(16))
        private val edge = ArrayList<Double>(expectedFrameCount.coerceAtLeast(16))
        private val sharpness = ArrayList<Double>(expectedFrameCount.coerceAtLeast(16))
        private var previous: FrameSignals? = null
        private var count = 0

        override fun onFrame(bitmap: Bitmap) {
            val current = signalsOf(bitmap)
            count++
            sharpness += current.sharpness
            previous?.let { prev ->
                val s = FrameSignalMath.score(prev, current)
                mad += s.mad
                hist += s.hist
                edge += s.edge
            }
            previous = current
        }

        override fun finish(): ShotScan {
            if (count == 0) return ShotScan.EMPTY
            val score = ShotBoundaryMath.combine(
                mad.toDoubleArray(),
                hist.toDoubleArray(),
                edge.toDoubleArray(),
            )
            val boundaries = ShotBoundaryMath.pickBoundaries(score, fps)
            val transitions = ShotBoundaryMath.reconstructTransitions(
                boundaries = boundaries,
                sharpness = sharpness.toDoubleArray(),
                fps = fps,
                frameCount = count,
            )
            return ShotScan(count, fps, boundaries, transitions)
        }

        private fun signalsOf(bitmap: Bitmap): FrameSignals {
            val (tw, th) = FrameSignalMath.thumbnailSize(
                bitmap.width, bitmap.height, PipelineDefaults.SHOT_THUMB_EDGE_PX,
            )
            val thumb = scaledPixels(bitmap, tw, th)
            val gray = FrameSignalMath.toGray(thumb)
            val (h, s, v) = FrameSignalMath.toHsvChannels(thumb)
            val histogram = FrameSignalMath.hsvHistogram(h, s, v)
            val edges = FrameSignalMath.edgeMask(gray, tw, th)

            val (sw, sh) = FrameSignalMath.thumbnailSize(
                bitmap.width, bitmap.height, PipelineDefaults.SHOT_SHARP_EDGE_PX,
            )
            val sharpPixels = scaledPixels(bitmap, sw, sh)
            val sharpGray = FrameSignalMath.toGray(sharpPixels)
            val sharp = FrameSignalMath.varianceOfLaplacian(sharpGray, sw, sh)

            return FrameSignals(gray, histogram, edges, sharp)
        }

        private fun scaledPixels(bitmap: Bitmap, w: Int, h: Int): IntArray {
            val scaled = if (bitmap.width == w && bitmap.height == h) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            }
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            return px
        }
    }
}
