package com.example.ikyky.features.people.domain.model

import com.example.ikyky.core.model.BoundingBox

/**
 * **Product-facing** view of one continuous appearance instance belonging to a
 * [Person] — what the People/Appearances screens and the future collage engine
 * consume, instead of the processing-internal
 * [com.example.ikyky.features.processing.domain.model.AppearanceCandidate].
 *
 * This is a thin, framework-free projection: every field already exists on the
 * frozen pipeline's `AppearanceCandidate` (see
 * [com.example.ikyky.features.people.domain.usecase.ToAppearance]) — nothing
 * here is invented, no new quality/confidence signal, no ML Kit / Bitmap /
 * TFLite type leaks past this boundary.
 */
data class Appearance(
    val id: String,
    val personId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    /** Frame index (sampled-stream index) of this appearance's best frame. */
    val bestFrameIndex: Int,
    val bestFrameTimestampMs: Long,
    /** Face box (canonical coords) in the best frame — for a crop/preview. */
    val bestFrameBox: BoundingBox,
    /** Phase-2 cheap quality score of the best frame (0f..1f). */
    val bestQuality: Float,
    val meanQuality: Float,
    val observationCount: Int,
) {
    val durationMs: Long get() = endTimestampMs - startTimestampMs
}
