package com.example.ikyky.core.ml.tracking

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace

/**
 * A single face observation tied to the frame it came from.
 *
 * [face] holds the detection in **canonical** (upright, full-resolution)
 * coordinates. [qualityScore] is the cheap Phase 2 signal (0f..1f); [usable]
 * is its reject flag. Low-quality observations are still kept for tracking
 * continuity — quality only matters for later representative-frame selection.
 *
 * [blurVariance] is the variance-of-Laplacian of the face patch measured during
 * detection (higher = sharper; `NaN` when it could not be measured). It is
 * **carried data only** — the tracker never reads it; it exists so Phase 8.2's
 * representative-candidate selection can rank on real sharpness instead of the
 * saturated [qualityScore].
 */
data class FaceObservation(
    val id: String,
    val frameIndex: Int,
    val timestampMs: Long,
    val face: DetectedFace,
    val qualityScore: Float = 1f,
    val usable: Boolean = true,
    val blurVariance: Double = Double.NaN,
) {
    val box: BoundingBox get() = face.boundingBox
    val trackingId: Int? get() = face.trackingId
}

/**
 * Continuity state of a tracklet.
 */
enum class TrackletState { ACTIVE, CLOSED }

/**
 * A short-term temporal chain of observations believed to be the same physical
 * face across nearby frames (via ML Kit tracking id, with an IoU / centre-
 * distance fallback).
 *
 * NOT a global identity: a person who leaves and re-enters the frame produces
 * multiple tracklets, and two people visible together produce one tracklet each.
 */
data class Tracklet(
    val id: Long,
    val observations: List<FaceObservation>,
    val state: TrackletState = TrackletState.CLOSED,
    /** The ML Kit tracking id this tracklet was last associated with, if any. */
    val lastTrackingId: Int? = observations.lastOrNull()?.trackingId,
) {
    val startTimestampMs: Long get() = observations.first().timestampMs
    val endTimestampMs: Long get() = observations.last().timestampMs
    val durationMs: Long get() = endTimestampMs - startTimestampMs
    val frameCount: Int get() = observations.size
    val lastBox: BoundingBox get() = observations.last().box
    val firstFrameIndex: Int get() = observations.first().frameIndex
    val lastFrameIndex: Int get() = observations.last().frameIndex
}
