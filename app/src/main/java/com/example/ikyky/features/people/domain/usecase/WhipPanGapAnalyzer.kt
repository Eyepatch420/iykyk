package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef

/**
 * Finds **whip-pan / disappearance boundaries** inside a single appearance
 * (Phase 4.5, brief steps 9–10).
 *
 * The assignment: a blurred whip-pan pass "counts for nobody". So if an
 * appearance's observations show a wall-clock gap larger than
 * [PipelineDefaults.WHIP_PAN_GAP_MS] AND the observations bracketing that gap are
 * blurred (quality ≤ [PipelineDefaults.WHIP_PAN_MAX_QUALITY]), the appearance is
 * closed at the gap — even if ML Kit kept a tracking id across it.
 *
 * Returns the timestamps (start of the later segment) at which to cut. Pure
 * Kotlin, deterministic.
 */
class WhipPanGapAnalyzer(
    private val gapMs: Long = PipelineDefaults.WHIP_PAN_GAP_MS,
    private val maxQuality: Float = PipelineDefaults.WHIP_PAN_MAX_QUALITY,
) {

    data class Gap(
        val beforeTimestampMs: Long,
        val afterTimestampMs: Long,
        val gapMs: Long,
        val beforeQuality: Float,
        val afterQuality: Float,
    )

    fun findWhipPanCuts(observations: List<AppearanceObservationRef>): List<Gap> {
        val obs = observations.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
        if (obs.size < 2) return emptyList()
        val cuts = ArrayList<Gap>()
        for (i in 0 until obs.size - 1) {
            val a = obs[i]
            val b = obs[i + 1]
            val g = b.timestampMs - a.timestampMs
            if (g > gapMs && a.qualityScore <= maxQuality && b.qualityScore <= maxQuality) {
                cuts += Gap(a.timestampMs, b.timestampMs, g, a.qualityScore, b.qualityScore)
            }
        }
        return cuts
    }
}
