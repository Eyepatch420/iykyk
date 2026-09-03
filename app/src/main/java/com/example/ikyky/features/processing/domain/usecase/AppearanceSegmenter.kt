package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.ml.tracking.Tracklet
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate

/**
 * Turns short-term [Tracklet]s into [AppearanceCandidate]s — one continuous
 * visible segment each.
 *
 * Rules (all thresholds from [PipelineDefaults], none inline):
 *  - a tracklet is already a single continuous segment (the tracker closes it on
 *    a gap), so 1 tracklet → at most 1 candidate;
 *  - it must have >= [PipelineDefaults.MIN_APPEARANCE_OBSERVATIONS] observations
 *    AND span >= [PipelineDefaults.MIN_APPEARANCE_DURATION_MS] to survive
 *    (filters detector noise / whip-pan blips);
 *  - NOTHING is merged across time or across faces here — two people together
 *    stay two candidates, and the same person re-appearing stays separate.
 *    Global identity is Phase 3/4.
 */
class AppearanceSegmenter(
    private val minObservations: Int = PipelineDefaults.MIN_APPEARANCE_OBSERVATIONS,
    private val minDurationMs: Long = PipelineDefaults.MIN_APPEARANCE_DURATION_MS,
) {
    fun segment(tracklets: List<Tracklet>): List<AppearanceCandidate> =
        tracklets
            .asSequence()
            .filter { it.frameCount >= minObservations }
            .filter { it.durationMs >= minDurationMs }
            .map { t ->
                AppearanceCandidate.fromObservations(
                    id = "app_${t.id}",
                    trackletId = t.id,
                    lastTrackingId = t.lastTrackingId,
                    observations = t.observations,
                )
            }
            .sortedBy { it.startTimestampMs }
            .toList()
}
