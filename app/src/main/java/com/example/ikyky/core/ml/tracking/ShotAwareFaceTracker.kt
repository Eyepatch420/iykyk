package com.example.ikyky.core.ml.tracking

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding
import kotlin.math.hypot
import kotlin.math.max

/**
 * **The frozen Phase 5I / Config K / Option A tracker.**
 *
 * Association of a detection to an open track must pass FOUR gates, in order:
 *
 *  1. **temporal** — time since the track's last observation <= [maxGapMs]
 *  2. **shot boundary** — the detection's frame and the track's last frame are
 *     not separated by a confirmed transition. **HARD, never overridable** by
 *     geometry or appearance.
 *  3. **geometry** — IoU / centre-distance / size-ratio plausibility
 *  4. **appearance** — cosine between this detection's gate embedding and the
 *     track's representation is not strongly incompatible
 *
 * ## Why the shot barrier exists
 *
 * These videos are montages. Phase 5F found ~17 whip-pan transitions per 30 s
 * clip, and the old geometry-only tracker happily bridged them — a face on the
 * left before the pan and a *different* face on the left after it look like the
 * same track. That single defect was the root cause of multiple people collapsing
 * into one appearance. Adding the barrier plus the appearance gate moved minimum
 * intra-tracklet cosine from **-0.077 to +0.268**.
 *
 * ## The anti-contamination rule
 *
 * The association decision is made **first**; a track's appearance state is
 * updated **only after** an observation is accepted ([OpenTrack.accept]). A
 * rejected observation must never move the representation — otherwise one
 * wrongly-admitted face drags the track's notion of who it is following, and the
 * gate then admits more of the same. This ordering is load-bearing, not stylistic.
 *
 * ## The gate crop
 *
 * Embeddings passed here come from the **expanded box crop**
 * ([com.example.ikyky.core.ml.preprocessing.TrackerAppearanceCrop]), NOT the
 * recognition crop. Option B (gating on the 5-point crop) was evaluated in Phase
 * 5I and rejected — see that type's docs.
 *
 * Deterministic: candidates are sorted by descending score with the track id as a
 * stable tie-break; no randomness anywhere.
 */
class ShotAwareFaceTracker(
    private val maxGapMs: Long = PipelineDefaults.SHOT_AWARE_MAX_GAP_MS,
    private val minIou: Float = PipelineDefaults.SHOT_AWARE_MIN_IOU,
    private val maxCenterDistFraction: Float = PipelineDefaults.SHOT_AWARE_MAX_CENTER_DIST_FRACTION,
    private val maxSizeRatio: Float = PipelineDefaults.SHOT_AWARE_MAX_SIZE_RATIO,
    private val appearanceMinCos: Float = PipelineDefaults.SHOT_AWARE_APPEARANCE_MIN_COS,
    private val appearanceHistory: Int = PipelineDefaults.SHOT_AWARE_APPEARANCE_HISTORY,
    private val wIou: Float = PipelineDefaults.SHOT_AWARE_W_IOU,
    private val wAppearance: Float = PipelineDefaults.SHOT_AWARE_W_APPEARANCE,
    private val useGeometry: Boolean = true,
    private val useShotBoundaries: Boolean = true,
    private val useAppearanceGate: Boolean = true,
) {

    /**
     * @param crossesTransition **the absolute barrier**: true when a confirmed
     *   transition span lies between two decoded-frame indices. Supplied by
     *   [com.example.ikyky.core.ml.shots.ShotScan.crossesTransition].
     * @param frameDiagonal canonical frame diagonal in px; the centre-distance
     *   gate is a fraction of THIS, not of the box size.
     * @param gateEmbeddings gate-crop embedding per observation id; a missing
     *   entry simply means the appearance gate abstains for that observation.
     */
    fun track(
        observations: List<FaceObservation>,
        frameDiagonal: Float,
        crossesTransition: (Int, Int) -> Boolean,
        gateEmbeddings: Map<String, FaceEmbedding> = emptyMap(),
    ): List<Tracklet> {
        val byFrame = observations.groupBy { it.frameIndex }.toSortedMap()
        val maxCenter = maxCenterDistFraction * frameDiagonal

        val finished = ArrayList<Tracklet>()
        var openTracks = ArrayList<OpenTrack>()
        var nextId = 0L

        for ((frameIndex, dets) in byFrame) {
            if (dets.isEmpty()) continue
            val now = dets.first().timestampMs

            // --- retire tracks past the gap OR separated by a transition ------
            val stillOpen = ArrayList<OpenTrack>(openTracks.size)
            for (t in openTracks) {
                val gapOk = now - t.lastTimestampMs <= maxGapMs
                val shotOk = !(useShotBoundaries && crossesTransition(t.lastFrameIndex, frameIndex))
                if (gapOk && shotOk) stillOpen += t else finished += t.toTracklet()
            }
            openTracks = stillOpen

            // --- score every (track, detection) pair passing ALL hard gates ---
            val candidates = ArrayList<Candidate>()
            for (ti in openTracks.indices) {
                val t = openTracks[ti]
                val rep = t.representation()
                for (di in dets.indices) {
                    val d = dets[di]

                    // GATE 2 — absolute shot barrier, checked before anything else
                    if (useShotBoundaries && crossesTransition(t.lastFrameIndex, d.frameIndex)) {
                        continue
                    }

                    val iou = t.lastBox.iou(d.box)

                    // GATE 3 — geometry
                    if (useGeometry) {
                        if (iou < minIou) continue
                        val cd = hypot(
                            t.lastBox.centerX - d.box.centerX,
                            t.lastBox.centerY - d.box.centerY,
                        )
                        if (cd > maxCenter) continue
                        val areaA = max(t.lastBox.area.toFloat(), 1e-6f)
                        val areaB = max(d.box.area.toFloat(), 1e-6f)
                        if (max(areaA / areaB, areaB / areaA) > maxSizeRatio) continue
                    }

                    // GATE 4 — appearance. Decide only; never update state here.
                    var appCos: Float? = null
                    val detEmb = if (useAppearanceGate) gateEmbeddings[d.id] else null
                    if (useAppearanceGate && rep != null && detEmb != null) {
                        val cos = rep.cosineSimilarity(detEmb)
                        if (cos < appearanceMinCos) continue // HARD reject
                        appCos = cos
                    }

                    var score = wIou * iou
                    if (appCos != null) score += wAppearance * appCos
                    candidates += Candidate(score, ti, di, t.id)
                }
            }

            // deterministic: best score first, ties broken by track id then det index
            candidates.sortWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.trackId }
                    .thenBy { it.detIndex }
            )

            val usedTracks = HashSet<Int>()
            val usedDets = HashSet<Int>()
            for (c in candidates) {
                if (c.trackIndex in usedTracks || c.detIndex in usedDets) continue
                // ACCEPT — and only now is the track's state allowed to change.
                openTracks[c.trackIndex].accept(dets[c.detIndex], gateEmbeddings[dets[c.detIndex].id])
                usedTracks += c.trackIndex
                usedDets += c.detIndex
            }

            // --- unmatched detections open new tracks -------------------------
            for (di in dets.indices) {
                if (di in usedDets) continue
                val d = dets[di]
                val t = OpenTrack(nextId++, appearanceHistory)
                t.accept(d, gateEmbeddings[d.id])
                openTracks += t
            }
        }

        for (t in openTracks) finished += t.toTracklet()
        return finished.sortedWith(compareBy({ it.startTimestampMs }, { it.id }))
    }

    private data class Candidate(
        val score: Float,
        val trackIndex: Int,
        val detIndex: Int,
        val trackId: Long,
    )

    /**
     * A track still accepting observations.
     *
     * [embeddingHistory] holds **accepted observations only** — this is what makes
     * the anti-contamination rule real rather than aspirational.
     */
    private class OpenTrack(val id: Long, private val historySize: Int) {
        val observations = ArrayList<FaceObservation>()
        private val embeddingHistory = ArrayList<FaceEmbedding>()

        val lastBox: BoundingBox get() = observations.last().box
        val lastTimestampMs: Long get() = observations.last().timestampMs
        val lastFrameIndex: Int get() = observations.last().frameIndex

        /**
         * The track's identity estimate: the **median-of-history** member — the
         * one with the highest summed cosine to the rest of the recent window.
         * Frozen as `appearance_repr = "median"` by Config K. More robust than
         * the last-accepted vector (which a single bad frame poisons) and than
         * the mean (which drifts smoothly toward an intruder).
         */
        fun representation(): FaceEmbedding? {
            if (embeddingHistory.isEmpty()) return null
            val window = embeddingHistory.takeLast(historySize)
            if (window.size == 1) return window[0]
            var best = window[0]
            var bestSum = Float.NEGATIVE_INFINITY
            for (a in window) {
                var sum = 0f
                for (b in window) sum += a.cosineSimilarity(b)
                if (sum > bestSum) {
                    bestSum = sum
                    best = a
                }
            }
            return best
        }

        /** Called ONLY after the association decision has already been made. */
        fun accept(observation: FaceObservation, embedding: FaceEmbedding?) {
            observations += observation
            if (embedding != null) {
                embeddingHistory += embedding
                val cap = max(historySize * 2, 8)
                if (embeddingHistory.size > cap) {
                    val keep = embeddingHistory.takeLast(historySize * 2)
                    embeddingHistory.clear()
                    embeddingHistory += keep
                }
            }
        }

        fun toTracklet(): Tracklet = Tracklet(
            id = id,
            observations = observations.toList(),
            state = TrackletState.CLOSED,
            lastTrackingId = observations.lastOrNull()?.trackingId,
        )
    }
}
