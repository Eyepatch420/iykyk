package com.example.ikyky.core.ml.tracking

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.BoundingBox
import kotlin.math.hypot

/**
 * Greedy per-frame nearest-match tracker.
 *
 * For each new frame:
 *  1. every ACTIVE tracklet whose trailing gap exceeds [maxGapMs] is CLOSED;
 *  2. remaining tracklets are matched to this frame's observations:
 *     a. ML Kit tracking-id match (strong short-term evidence, unambiguous only);
 *     b. conservative spatial fallback (IoU primary; centre-distance + size-ratio
 *        gate). By default the fallback will NOT bridge an observation whose
 *        tracking id *differs* from the tracklet's last known id — a changed id
 *        is treated as evidence of a different face, and we prefer a false split
 *        (recoverable by embeddings later) over a false merge (destroys the
 *        appearance boundary). Toggle with [bridgeAcrossTrackingIds];
 *  3. unmatched observations open new tracklets.
 *
 * Each observation is used at most once; each tracklet extended at most once per
 * frame. No global-identity merging happens here.
 *
 * Pass a [TrackerTrace] to get a full decision log (debug only; production
 * passes null).
 */
class GreedyFaceTracker(
    private val iouThreshold: Float = PipelineDefaults.TRACKLET_IOU_THRESHOLD,
    private val centerDistFraction: Float = PipelineDefaults.TRACKLET_CENTER_DIST_FRACTION,
    private val sizeRatioTolerance: Float = PipelineDefaults.TRACKLET_SIZE_RATIO_TOLERANCE,
    private val maxGapMs: Long = PipelineDefaults.MAX_TRACK_GAP_MS,
    private val bridgeAcrossTrackingIds: Boolean = PipelineDefaults.TRACKLET_BRIDGE_ACROSS_TRACKING_IDS,
    private val trace: TrackerTrace? = null,
) : FaceTracker {

    override fun buildTracklets(observations: List<FaceObservation>): List<Tracklet> {
        val session = newSession()
        val byFrame = observations.groupBy { it.frameIndex }.toSortedMap()
        val closed = ArrayList<Tracklet>()
        for ((_, group) in byFrame) {
            val ts = group.first().timestampMs
            closed += session.onFrame(ts, group)
        }
        closed += session.finish()
        return closed.sortedBy { it.startTimestampMs }
    }

    override fun newSession(): FaceTracker.Session = SessionImpl()

    private inner class SessionImpl : FaceTracker.Session {

        private var nextId = 0L
        private val active = ArrayList<MutableTracklet>()
        private val closedList = ArrayList<Tracklet>()
        private var created = 0

        override val trackletsCreated: Int get() = created

        override fun onFrame(
            frameTimestampMs: Long,
            observations: List<FaceObservation>,
        ): List<Tracklet> {
            val justClosed = ArrayList<Tracklet>()

            // 1. close tracklets whose trailing gap is now too large
            val stillActive = ArrayList<MutableTracklet>(active.size)
            for (t in active) {
                val gap = frameTimestampMs - t.lastTimestampMs
                if (gap > maxGapMs) {
                    justClosed += t.toTracklet(TrackletState.CLOSED)
                    trace?.onClose(
                        TrackerTrace.CloseEvent(
                            frameTimestampMs = frameTimestampMs,
                            trackletId = t.id,
                            lastTimestampMs = t.lastTimestampMs,
                            gapMs = gap,
                            observationCount = t.observations.size,
                        )
                    )
                } else {
                    stillActive += t
                }
            }
            active.clear()
            active += stillActive

            if (observations.isEmpty()) {
                closedList += justClosed
                return justClosed
            }

            val obsUsed = BooleanArray(observations.size)
            val trackletMatched = BooleanArray(active.size)

            // 2a. ML Kit tracking-id matches (unambiguous on both sides)
            for (oi in observations.indices) {
                val tid = observations[oi].trackingId ?: continue
                val candidates = active.indices.filter {
                    !trackletMatched[it] && active[it].lastTrackingId == tid
                }
                if (candidates.size == 1) {
                    val ti = candidates.first()
                    linkMatch(frameTimestampMs, ti, oi, observations, TrackerTrace.Method.TRACKING_ID)
                    trackletMatched[ti] = true
                    obsUsed[oi] = true
                }
            }

            // 2b. conservative greedy spatial fallback
            data class Cand(val ti: Int, val oi: Int, val cost: Float)
            val cands = ArrayList<Cand>()
            for (ti in active.indices) {
                if (trackletMatched[ti]) continue
                val t = active[ti]
                for (oi in observations.indices) {
                    if (obsUsed[oi]) continue
                    val o = observations[oi]

                    // Guard: don't bridge two *different* known tracking ids.
                    if (!bridgeAcrossTrackingIds &&
                        t.lastTrackingId != null && o.trackingId != null &&
                        t.lastTrackingId != o.trackingId
                    ) {
                        continue
                    }

                    val cost = matchCost(t.lastBox, o.box) ?: continue
                    cands += Cand(ti, oi, cost)
                }
            }
            cands.sortBy { it.cost }
            for (c in cands) {
                if (trackletMatched[c.ti] || obsUsed[c.oi]) continue
                linkMatch(frameTimestampMs, c.ti, c.oi, observations, TrackerTrace.Method.SPATIAL_FALLBACK)
                trackletMatched[c.ti] = true
                obsUsed[c.oi] = true
            }

            // 3. unmatched observations open new tracklets
            for (oi in observations.indices) {
                if (obsUsed[oi]) continue
                val o = observations[oi]
                val id = nextId++
                active += MutableTracklet(id, o)
                created++
                trace?.onOpen(
                    TrackerTrace.OpenEvent(
                        frameTimestampMs = frameTimestampMs,
                        trackletId = id,
                        obsId = o.id,
                        box = o.box,
                        trackingId = o.trackingId,
                        reason = if (o.trackingId != null) "no tracklet had trackingId=${o.trackingId} / no spatial match"
                        else "no spatial match",
                    )
                )
            }

            closedList += justClosed
            return justClosed
        }

        override fun finish(): List<Tracklet> {
            val remaining = active.map { it.toTracklet(TrackletState.CLOSED) }
            active.clear()
            closedList += remaining
            return remaining
        }

        private fun linkMatch(
            frameTs: Long,
            ti: Int,
            oi: Int,
            observations: List<FaceObservation>,
            method: TrackerTrace.Method,
        ) {
            val t = active[ti]
            val o = observations[oi]
            val prevBox = t.lastBox
            val prevTid = t.lastTrackingId
            val iou = prevBox.iou(o.box)
            val meanSize = ((prevBox.width + prevBox.height + o.box.width + o.box.height) / 4f)
                .coerceAtLeast(1f)
            val cDist = hypot(prevBox.centerX - o.box.centerX, prevBox.centerY - o.box.centerY)
            val sRatio = run {
                val sa = maxOf(prevBox.width, prevBox.height).toFloat().coerceAtLeast(1f)
                val sb = maxOf(o.box.width, o.box.height).toFloat().coerceAtLeast(1f)
                maxOf(sa / sb, sb / sa)
            }
            t.extend(o)
            trace?.onMatch(
                TrackerTrace.MatchEvent(
                    frameTimestampMs = frameTs,
                    trackletId = t.id,
                    obsId = o.id,
                    method = method,
                    prevBox = prevBox,
                    newBox = o.box,
                    prevTrackingId = prevTid,
                    newTrackingId = o.trackingId,
                    iou = iou,
                    centerDist = cDist,
                    sizeRatio = sRatio,
                    trackingIdChanged = prevTid != null && o.trackingId != null && prevTid != o.trackingId,
                )
            )
        }

        /** @return match cost in [0,1] (lower = better) or null if the pair fails a gate. */
        private fun matchCost(a: BoundingBox, b: BoundingBox): Float? {
            val iou = a.iou(b)
            if (iou >= iouThreshold) return 1f - iou

            val meanSize = ((a.width + a.height + b.width + b.height) / 4f).coerceAtLeast(1f)
            val centerDist = hypot((a.centerX - b.centerX), (a.centerY - b.centerY))
            val distOk = centerDist <= centerDistFraction * meanSize

            val sizeRatio = run {
                val sa = maxOf(a.width, a.height).toFloat().coerceAtLeast(1f)
                val sb = maxOf(b.width, b.height).toFloat().coerceAtLeast(1f)
                maxOf(sa / sb, sb / sa)
            }
            val sizeOk = sizeRatio <= sizeRatioTolerance

            return if (distOk && sizeOk) {
                0.5f + 0.5f * (centerDist / (centerDistFraction * meanSize)).coerceIn(0f, 1f)
            } else {
                null
            }
        }
    }

    private class MutableTracklet(val id: Long, first: FaceObservation) {
        val observations = ArrayList<FaceObservation>().apply { add(first) }
        var lastTimestampMs = first.timestampMs
        var lastBox = first.box
        var lastTrackingId = first.trackingId

        fun extend(o: FaceObservation) {
            observations += o
            lastTimestampMs = o.timestampMs
            lastBox = o.box
            if (o.trackingId != null) lastTrackingId = o.trackingId
        }

        fun toTracklet(state: TrackletState) =
            Tracklet(
                id = id,
                observations = observations.toList(),
                state = state,
                lastTrackingId = lastTrackingId,
            )
    }
}
