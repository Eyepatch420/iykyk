package com.example.ikyky.features.processing.domain.model

/**
 * Debug counters accumulated while a video is processed. Surfaced in the
 * processing UI (compactly) and in the final result so pipeline behaviour can be
 * inspected and compared across sampling rates / detector settings.
 *
 * Phase 3+ will add `embeddingCalls`, `clusters`, `persons`.
 */
data class ProcessingDiagnostics(
    val framesPlanned: Int = 0,
    val framesSampled: Int = 0,
    val framesDecodedOk: Int = 0,
    val framesWithFaces: Int = 0,
    val totalFaceObservations: Int = 0,
    /**
     * Observations whose cheap Phase-2 quality signal was below the "usable"
     * line. They are STILL fed to the tracker — this is not a rejection, only a
     * flag for the later representative-frame scorer.
     */
    val observationsLowQuality: Int = 0,
    /** Raw tracklets the tracker produced, before the appearance-length filter. */
    val rawTracklets: Int = 0,
    /** Tracklets dropped by the min-observations / min-duration filter. */
    val trackletsFilteredOut: Int = 0,
    /** Tracklets that survived the filter (== [appearancesDetected]). */
    val trackletsAfterFilter: Int = 0,
    val appearancesDetected: Int = 0,
    val multiFaceFrames: Int = 0,
    val maxFacesInAnyFrame: Int = 0,
    val totalProcessingMs: Long = 0,
    val avgFrameProcessingMs: Double = 0.0,
) {
    val framesRejectedAsInvalid: Int get() = framesSampled - framesDecodedOk

    // --- back-compat aliases for existing call sites / older report text ---
    @Deprecated("renamed", ReplaceWith("observationsLowQuality"))
    val observationsRejectedLowQuality: Int get() = observationsLowQuality
    val trackletsCreated: Int get() = rawTracklets
    val trackletsAfterGapClose: Int get() = trackletsAfterFilter
}
