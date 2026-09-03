package com.example.ikyky.features.processing.domain.model

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding

/**
 * One observation of an appearance that has been run through the embedding
 * pipeline (crop → align → 112×112 → MobileFaceNet → L2-normalized vector).
 *
 * Keeps enough provenance to reason about an appearance's internal consistency
 * over time (Phase 3 diagnostics) and to feed Phase 4 clustering, but holds
 * NO bitmaps — only the vector and lightweight metadata.
 */
data class EmbeddedFaceObservation(
    val appearanceId: String,
    val trackletId: Long,
    /** Source frame index in the sampled stream. */
    val frameIndex: Int,
    /** Source timestamp within the video. */
    val timestampMs: Long,
    /** Originating [com.example.ikyky.core.ml.tracking.FaceObservation] id. */
    val observationId: String,
    /** Face box in canonical (upright, full-res) coordinates. */
    val faceBox: BoundingBox,
    /** Phase-2 cheap quality score of the source observation (0f..1f). */
    val qualityScore: Float,
    /** L2-normalized 192-d identity vector. */
    val embedding: FaceEmbedding,
    /** True if eye landmarks drove a similarity-transform alignment (vs a box crop). */
    val alignedByLandmarks: Boolean,
)
