package com.example.ikyky.core.ml.shots

import com.example.ikyky.core.common.constants.PipelineDefaults
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A contiguous span of frames that is NOT usable footage — a whip-pan bracket or
 * a hard cut. The real scene change happens somewhere inside it.
 *
 * These videos are montages: every scene change is a whip-pan that shows up as a
 * *pair* of cheap-score spikes (blur-in, blur-out) a few frames apart with a
 * blurred run between them. Phase 5F found 17 transitions / 18 shots per clip on
 * a rigid ~1.68 s grid, byte-identical across all three samples.
 *
 * @property startFrame first unusable frame, inclusive (decoded-frame index)
 * @property endFrame first usable frame again, exclusive
 */
data class Transition(
    val startFrame: Int,
    val endFrame: Int,
    val startMs: Long,
    val endMs: Long,
    val peakScore: Double,
    val kind: Kind,
) {
    enum class Kind { WHIP_PAN, HARD_CUT }

    fun containsFrame(frameIndex: Int): Boolean = frameIndex in startFrame until endFrame
}

/** One detected spike in the combined cut score. */
data class ShotBoundary(
    val frameIndex: Int,
    val timestampMs: Long,
    val score: Double,
)

/**
 * The result of scanning a video for shot changes: the reconstructed transition
 * spans plus the barrier query the tracker uses.
 */
data class ShotScan(
    val frameCount: Int,
    val fps: Double,
    val boundaries: List<ShotBoundary>,
    val transitions: List<Transition>,
) {
    /**
     * **The absolute tracking barrier.** True if any transition span lies (even
     * partly) between the two decoded-frame indices.
     *
     * Phase 5F established that a confirmed transition is an ABSOLUTE barrier: no
     * tracklet may cross one, and no geometry or appearance evidence may override
     * it. Bridging these was the root cause of the tracker merging several people
     * into one appearance.
     */
    fun crossesTransition(frameA: Int, frameB: Int): Boolean {
        val lo = min(frameA, frameB)
        val hi = max(frameA, frameB)
        for (t in transitions) {
            if (t.startFrame < hi && t.endFrame > lo) return true
        }
        return false
    }

    fun transitionAt(frameIndex: Int): Transition? =
        transitions.firstOrNull { it.containsFrame(frameIndex) }

    companion object {
        val EMPTY = ShotScan(0, 0.0, emptyList(), emptyList())
    }
}

/**
 * Pure, Android-free shot-boundary math — normalisation, spike picking and
 * transition-span reconstruction. Kept separate from frame decoding so the whole
 * algorithm is unit-testable on the JVM.
 *
 * Reproduces `ikyky_lab/shots/detector.py` (`_pick_boundaries`,
 * `_reconstruct_transitions`) exactly.
 */
object ShotBoundaryMath {

    /**
     * Normalise a signal by its own 99th percentile, so each of the three cheap
     * signals contributes on a comparable scale regardless of its natural range.
     */
    fun normalizeByP99(x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return x
        val p = percentile(x, 99.0)
        return if (p > 1e-9) DoubleArray(x.size) { x[it] / p } else x.copyOf()
    }

    /** Linear-interpolated percentile, matching `numpy.percentile`'s default. */
    fun percentile(x: DoubleArray, q: Double): Double {
        if (x.isEmpty()) return 0.0
        val sorted = x.sortedArray()
        if (sorted.size == 1) return sorted[0]
        val pos = (q / 100.0) * (sorted.size - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        if (lo == hi) return sorted[lo]
        val frac = pos - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    /**
     * Combine the three per-pair signals into one cut score in roughly [0, 1].
     * Each is normalised by its own 99th percentile and equally weighted.
     */
    fun combine(mad: DoubleArray, hist: DoubleArray, edge: DoubleArray): DoubleArray {
        val n = mad.size
        require(hist.size == n && edge.size == n) { "signal lengths differ" }
        if (n == 0) return DoubleArray(0)
        val nm = normalizeByP99(mad)
        val nh = normalizeByP99(hist)
        val ne = normalizeByP99(edge)
        val wSum = PipelineDefaults.SHOT_W_MAD + PipelineDefaults.SHOT_W_HIST +
            PipelineDefaults.SHOT_W_EDGE
        return DoubleArray(n) {
            (PipelineDefaults.SHOT_W_MAD * nm[it] +
                PipelineDefaults.SHOT_W_HIST * nh[it] +
                PipelineDefaults.SHOT_W_EDGE * ne[it]) / wSum
        }
    }

    /**
     * Pick boundary spikes from the combined [score] series.
     *
     * `score[i]` describes the transition from frame `i` to frame `i+1`, so the
     * boundary's frame index is `i + 1`. A spike must clear
     * `max(absFloor, mean + z*std)` AND be a local maximum within ±nmsRadius.
     */
    fun pickBoundaries(score: DoubleArray, fps: Double): List<ShotBoundary> {
        if (score.isEmpty()) return emptyList()
        var mean = 0.0
        for (v in score) mean += v
        mean /= score.size
        var varSum = 0.0
        for (v in score) varSum += (v - mean) * (v - mean)
        val sd = sqrt(varSum / score.size).takeIf { it > 0.0 } ?: 1e-9
        val thr = max(PipelineDefaults.SHOT_ABS_FLOOR, mean + PipelineDefaults.SHOT_Z_THRESHOLD * sd)

        val out = ArrayList<ShotBoundary>()
        val radius = PipelineDefaults.SHOT_NMS_RADIUS
        val guard = PipelineDefaults.SHOT_EDGE_GUARD
        for (i in score.indices) {
            val bFrame = i + 1
            if (bFrame < guard || bFrame > score.size - guard) continue
            if (score[i] < thr) continue
            val lo = max(0, i - radius)
            val hi = min(score.size, i + radius + 1)
            var localMax = Double.NEGATIVE_INFINITY
            for (j in lo until hi) localMax = max(localMax, score[j])
            if (score[i] < localMax - 1e-12) continue
            out += ShotBoundary(
                frameIndex = bFrame,
                timestampMs = msOf(bFrame, fps),
                score = score[i],
            )
        }
        return out
    }

    /**
     * Turn instantaneous spikes into unusable-footage SPANS.
     *
     * Spikes within [PipelineDefaults.SHOT_PAIR_MAX_GAP_FRAMES] of each other are
     * one whip-pan bracket; a lone spike is a hard cut and gets a small pad.
     * Either way the span is then widened across the actually-blurred run
     * (sharpness below `blurRatio * median`), and touching spans are merged.
     *
     * @param sharpness per-frame variance-of-Laplacian, length == [frameCount]
     */
    fun reconstructTransitions(
        boundaries: List<ShotBoundary>,
        sharpness: DoubleArray,
        fps: Double,
        frameCount: Int,
    ): List<Transition> {
        if (boundaries.isEmpty()) return emptyList()
        val medSharp = if (sharpness.isEmpty()) 0.0 else median(sharpness)
        val blurThr = PipelineDefaults.SHOT_BLUR_RATIO * medSharp

        val frames = boundaries.map { it.frameIndex }.sorted()
        val scoreByFrame = boundaries.associate { it.frameIndex to it.score }

        // group spikes into whip-pan brackets
        val groups = ArrayList<MutableList<Int>>()
        groups += mutableListOf(frames.first())
        for (f in frames.drop(1)) {
            if (f - groups.last().last() <= PipelineDefaults.SHOT_PAIR_MAX_GAP_FRAMES) {
                groups.last() += f
            } else {
                groups += mutableListOf(f)
            }
        }

        val spans = ArrayList<Transition>()
        for (g in groups) {
            val lo = g.first()
            val hi = g.last()
            val kind = if (g.size >= 2) Transition.Kind.WHIP_PAN else Transition.Kind.HARD_CUT
            var start: Int
            var end: Int
            if (kind == Transition.Kind.HARD_CUT) {
                start = max(0, lo - PipelineDefaults.SHOT_HARD_CUT_PAD_FRAMES)
                end = min(frameCount, lo + PipelineDefaults.SHOT_HARD_CUT_PAD_FRAMES + 1)
            } else {
                start = lo
                end = hi
            }
            // widen across the blurred run
            while (start - 1 >= 0 && sharpness[start - 1] < blurThr) start--
            while (end < frameCount && end < sharpness.size && sharpness[end] < blurThr) end++
            val peak = g.maxOf { scoreByFrame[it] ?: 0.0 }
            spans += Transition(start, end, msOf(start, fps), msOf(end, fps), peak, kind)
        }

        // merge spans that now touch or overlap
        val merged = ArrayList<Transition>()
        for (t in spans.sortedBy { it.startFrame }) {
            val last = merged.lastOrNull()
            if (last != null && t.startFrame <= last.endFrame) {
                val newEnd = max(last.endFrame, t.endFrame)
                merged[merged.size - 1] = last.copy(
                    endFrame = newEnd,
                    endMs = msOf(newEnd, fps),
                    peakScore = max(last.peakScore, t.peakScore),
                    kind = if (t.kind == Transition.Kind.WHIP_PAN) Transition.Kind.WHIP_PAN else last.kind,
                )
            } else {
                merged += t
            }
        }
        return merged
    }

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** Frame index -> milliseconds, rounding exactly as the Python reference does. */
    fun msOf(frameIndex: Int, fps: Double): Long =
        if (fps <= 0.0) 0L else (frameIndex / fps * 1000.0).roundToInt().toLong()
}
