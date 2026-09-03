package com.example.ikyky.features.processing.domain.model

import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.Landmark

/**
 * A **lightweight, framework-free projection** of a single [FaceObservation]
 * carried on an [AppearanceCandidate] so the Phase-3 embedding stage can pick
 * and re-decode several frames within an appearance. Holds NO bitmaps — the
 * caller re-decodes [timestampMs] on demand and uses [canonicalBox] +
 * [landmarks] for the recognition crop / alignment.
 */
data class AppearanceObservationRef(
    val observationId: String,
    val trackletId: Long,
    val frameIndex: Int,
    val timestampMs: Long,
    /** Face box in canonical (upright, full-res) coordinates. */
    val canonicalBox: BoundingBox,
    /** Landmarks in canonical coordinates (may be empty). */
    val landmarks: List<Landmark>,
    /** Phase-2 cheap quality score (0f..1f). */
    val qualityScore: Float,
    /** Phase-2 "usable" flag (below the cheap quality line if false). */
    val usable: Boolean,
)

/**
 * A **continuous visible segment** of a single physical face — the Phase 2 end
 * product. One tracklet that passed the minimum-length gates becomes one
 * candidate. Two people visible together yield two candidates; the same person
 * re-entering the frame yields another candidate.
 *
 * This is NOT yet a person. Global identity merging (embedding + clustering) is
 * Phase 3/4, at which point several [AppearanceCandidate]s may collapse into one
 * `Person` with an appearance count.
 *
 * Holds only lightweight data — no bitmaps. [observations] lets Phase 3 sample
 * several frames of this segment for embedding; [bestFrameIndex] /
 * [bestFrameTimestampMs] mark the single representative frame for a later crop.
 */
data class AppearanceCandidate(
    val id: String,
    val trackletId: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val firstFrameIndex: Int,
    val lastFrameIndex: Int,
    val observationCount: Int,
    /** ML Kit tracking id last seen for this segment, if any (debug only). */
    val lastTrackingId: Int?,
    val meanQuality: Float,
    val bestQuality: Float,
    val bestFrameIndex: Int,
    val bestFrameTimestampMs: Long,
    /** Face box (canonical coords) in the best frame — for the future crop. */
    val bestFrameBox: BoundingBox,
    /** Every observation of this segment, as lightweight refs (no bitmaps). */
    val observations: List<AppearanceObservationRef> = emptyList(),
) {
    val durationMs: Long get() = endTimestampMs - startTimestampMs

    companion object {
        fun fromObservations(
            id: String,
            trackletId: Long,
            lastTrackingId: Int?,
            observations: List<FaceObservation>,
        ): AppearanceCandidate {
            require(observations.isNotEmpty())
            val best = observations.maxBy { it.qualityScore }
            val meanQ = observations.map { it.qualityScore }.average().toFloat()
            return AppearanceCandidate(
                id = id,
                trackletId = trackletId,
                startTimestampMs = observations.first().timestampMs,
                endTimestampMs = observations.last().timestampMs,
                firstFrameIndex = observations.first().frameIndex,
                lastFrameIndex = observations.last().frameIndex,
                observationCount = observations.size,
                lastTrackingId = lastTrackingId,
                meanQuality = meanQ,
                bestQuality = best.qualityScore,
                bestFrameIndex = best.frameIndex,
                bestFrameTimestampMs = best.timestampMs,
                bestFrameBox = best.box,
                observations = observations.map { o ->
                    AppearanceObservationRef(
                        observationId = o.id,
                        trackletId = trackletId,
                        frameIndex = o.frameIndex,
                        timestampMs = o.timestampMs,
                        canonicalBox = o.face.boundingBox,
                        landmarks = o.face.landmarks,
                        qualityScore = o.qualityScore,
                        usable = o.usable,
                    )
                },
            )
        }
    }
}
