package com.example.ikyky.core.ml.shots

import com.example.ikyky.core.common.constants.PipelineDefaults
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The cheap per-frame features the shot detector compares between consecutive
 * frames. **No neural network is involved** — this runs on every decoded frame,
 * so it must stay trivially cheap.
 *
 * Pure math on primitive arrays (no `Bitmap`), so the whole signal computation is
 * unit-testable on the JVM. [ShotScanner] supplies the downscaled pixel data.
 *
 * Reproduces `ikyky_lab/shots/detector.py::_features` / `_pair_scores`.
 *
 * @property gray thumbnail grayscale, `thumbW * thumbH`, values 0..255
 * @property hsvHistogram L1-normalised joint H/S/V histogram, `bins^3` entries
 * @property edges Canny-style edge mask over [gray], same dimensions
 * @property sharpness variance of Laplacian on the LARGER thumbnail
 */
class FrameSignals(
    val gray: FloatArray,
    val hsvHistogram: FloatArray,
    val edges: BooleanArray,
    val sharpness: Double,
)

/** One consecutive-frame-pair comparison. */
data class PairScore(val mad: Double, val hist: Double, val edge: Double)

object FrameSignalMath {

    /**
     * Mean absolute difference of the grayscale thumbnails, scaled to [0, 1].
     */
    fun meanAbsoluteDifference(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "thumbnail sizes differ" }
        if (a.isEmpty()) return 0.0
        var sum = 0.0
        for (i in a.indices) sum += abs(b[i] - a[i])
        return (sum / a.size) / 255.0
    }

    /**
     * Symmetric chi-square distance between two L1-normalised histograms, mapped
     * into [0, 1) by `chi / (1 + chi)`.
     */
    fun histogramDistance(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "histogram sizes differ" }
        var chi = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            val denom = a[i].toDouble() + b[i].toDouble() + 1e-10
            chi += d * d / denom
        }
        chi *= 0.5
        return chi / (1.0 + chi)
    }

    /**
     * Edge-change ratio: `1 - |A and B| / |A or B|`. Near 0 when the two frames
     * share their edge structure, near 1 when the scene has been replaced.
     */
    fun edgeChangeRatio(a: BooleanArray, b: BooleanArray): Double {
        require(a.size == b.size) { "edge map sizes differ" }
        var union = 0
        var inter = 0
        for (i in a.indices) {
            if (a[i] || b[i]) union++
            if (a[i] && b[i]) inter++
        }
        return if (union == 0) 0.0 else 1.0 - inter.toDouble() / union
    }

    fun score(a: FrameSignals, b: FrameSignals): PairScore = PairScore(
        mad = meanAbsoluteDifference(a.gray, b.gray),
        hist = histogramDistance(a.hsvHistogram, b.hsvHistogram),
        edge = edgeChangeRatio(a.edges, b.edges),
    )

    /**
     * Joint H/S/V histogram over `bins^3` cells, L1-normalised.
     *
     * Ranges match OpenCV's 8-bit HSV convention so the bin layout is identical
     * to the Python reference: H in [0, 180), S and V in [0, 256).
     */
    fun hsvHistogram(
        hue: IntArray,
        sat: IntArray,
        value: IntArray,
        bins: Int = PipelineDefaults.SHOT_HIST_BINS,
    ): FloatArray {
        val hist = FloatArray(bins * bins * bins)
        val n = hue.size
        for (i in 0 until n) {
            val hb = (hue[i] * bins / 180).coerceIn(0, bins - 1)
            val sb = (sat[i] * bins / 256).coerceIn(0, bins - 1)
            val vb = (value[i] * bins / 256).coerceIn(0, bins - 1)
            hist[(hb * bins + sb) * bins + vb] += 1f
        }
        var sum = 0.0
        for (v in hist) sum += v
        if (sum > 0.0) {
            val inv = (1.0 / sum).toFloat()
            for (i in hist.indices) hist[i] *= inv
        }
        return hist
    }

    /**
     * 3x3 Laplacian variance — the sharpness signal used to widen transition
     * spans across their blurred frames. Border pixels are excluded.
     */
    fun varianceOfLaplacian(gray: FloatArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val lap = -4f * gray[i] +
                    gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width]
                sum += lap
                sumSq += lap.toDouble() * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    /**
     * Binary edge mask via gradient magnitude with hysteresis, approximating
     * `cv2.Canny(gray, 80, 160)`.
     *
     * Exact per-pixel parity with OpenCV's Canny is neither achievable nor
     * needed: the edge signal is one of three equally-weighted inputs, is
     * normalised by its own 99th percentile, and the transition spikes sit ~4x
     * above the noise floor with a wide empty gap in the score histogram. What
     * matters is that the ratio moves sharply when the scene is replaced.
     */
    fun edgeMask(
        gray: FloatArray,
        width: Int,
        height: Int,
        lowThreshold: Float = 80f,
        highThreshold: Float = 160f,
    ): BooleanArray {
        val out = BooleanArray(gray.size)
        if (width < 3 || height < 3) return out

        val mag = FloatArray(gray.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                // Sobel
                val gx =
                    -gray[i - width - 1] + gray[i - width + 1] +
                        -2f * gray[i - 1] + 2f * gray[i + 1] +
                        -gray[i + width - 1] + gray[i + width + 1]
                val gy =
                    -gray[i - width - 1] - 2f * gray[i - width] - gray[i - width + 1] +
                        gray[i + width - 1] + 2f * gray[i + width] + gray[i + width + 1]
                mag[i] = kotlin.math.sqrt(gx * gx + gy * gy)
            }
        }

        // strong seeds
        val strong = BooleanArray(gray.size)
        for (i in mag.indices) if (mag[i] >= highThreshold) { strong[i] = true; out[i] = true }

        // hysteresis: grow through weak pixels connected to a strong seed
        val stack = ArrayDeque<Int>()
        for (i in strong.indices) if (strong[i]) stack.addLast(i)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % width
            val y = i / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val j = ny * width + nx
                    if (!out[j] && mag[j] >= lowThreshold) {
                        out[j] = true
                        stack.addLast(j)
                    }
                }
            }
        }
        return out
    }

    /** Target thumbnail size preserving aspect ratio, longest edge == [edgePx]. */
    fun thumbnailSize(width: Int, height: Int, edgePx: Int): Pair<Int, Int> {
        val longer = max(width, height)
        if (longer <= 0) return 1 to 1
        val s = edgePx.toDouble() / longer
        return max(1, (width * s).toInt()) to max(1, (height * s).toInt())
    }

    /** ARGB -> grayscale, using the same luma weights as `cv2.COLOR_BGR2GRAY`. */
    fun toGray(argb: IntArray): FloatArray = FloatArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (0.299f * r + 0.587f * g + 0.114f * b)
    }

    /**
     * ARGB -> OpenCV-convention 8-bit HSV channels (H in [0,180), S/V in [0,256)).
     */
    fun toHsvChannels(argb: IntArray): Triple<IntArray, IntArray, IntArray> {
        val n = argb.size
        val h = IntArray(n)
        val s = IntArray(n)
        val v = IntArray(n)
        for (i in 0 until n) {
            val p = argb[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val delta = mx - mn
            val hue = when {
                delta < 1e-6f -> 0f
                mx == r -> 60f * (((g - b) / delta) % 6f)
                mx == g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            val hp = if (hue < 0f) hue + 360f else hue
            h[i] = ((hp / 2f).toInt()).coerceIn(0, 179)
            s[i] = (if (mx <= 0f) 0f else delta / mx * 255f).toInt().coerceIn(0, 255)
            v[i] = (mx * 255f).toInt().coerceIn(0, 255)
        }
        return Triple(h, s, v)
    }
}
