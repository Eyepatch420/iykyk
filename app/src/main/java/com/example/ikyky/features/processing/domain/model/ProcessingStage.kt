package com.example.ikyky.features.processing.domain.model

/**
 * Ordered stages of the on-device pipeline, used for progress reporting.
 * Algorithms do NOT live in this enum.
 *
 * Phase 2 implements up to [FINALIZING_APPEARANCES] → [COMPLETED]. The later
 * embedding / clustering / collage stages remain defined for Phase 3+.
 */
enum class ProcessingStage {
    LOADING_VIDEO,
    EXTRACTING_FRAMES,
    DETECTING_FACES,
    TRACKING_APPEARANCES,
    FINALIZING_APPEARANCES,

    // --- Phase 3+ (not run yet) ---
    EVALUATING_QUALITY,
    ALIGNING_FACES,
    GENERATING_EMBEDDINGS,
    AGGREGATING_EMBEDDINGS,
    CLUSTERING_IDENTITIES,
    SELECTING_REPRESENTATIVES,
    RENDERING_COLLAGE,

    COMPLETED,
    ERROR,
}
