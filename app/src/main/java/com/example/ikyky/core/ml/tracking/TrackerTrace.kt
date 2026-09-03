package com.example.ikyky.core.ml.tracking

import com.example.ikyky.core.model.BoundingBox

/**
 * Debug-only sink for per-decision tracker events. Production passes `null`;
 * the Phase 2.5 sweep harness passes a collector to explain exactly why each
 * observation was linked, created, or a tracklet closed.
 *
 * Not wired into any user-facing UI.
 */
interface TrackerTrace {
    fun onMatch(e: MatchEvent)
    fun onOpen(e: OpenEvent)
    fun onClose(e: CloseEvent)

    enum class Method { TRACKING_ID, SPATIAL_FALLBACK }

    data class MatchEvent(
        val frameTimestampMs: Long,
        val trackletId: Long,
        val obsId: String,
        val method: Method,
        val prevBox: BoundingBox,
        val newBox: BoundingBox,
        val prevTrackingId: Int?,
        val newTrackingId: Int?,
        val iou: Float,
        val centerDist: Float,
        val sizeRatio: Float,
        /** true when [newTrackingId] differs from [prevTrackingId] yet the match was accepted. */
        val trackingIdChanged: Boolean,
    )

    data class OpenEvent(
        val frameTimestampMs: Long,
        val trackletId: Long,
        val obsId: String,
        val box: BoundingBox,
        val trackingId: Int?,
        val reason: String,
    )

    data class CloseEvent(
        val frameTimestampMs: Long,
        val trackletId: Long,
        val lastTimestampMs: Long,
        val gapMs: Long,
        val observationCount: Int,
    )
}
