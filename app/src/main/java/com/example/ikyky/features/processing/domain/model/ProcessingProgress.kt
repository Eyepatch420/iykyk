package com.example.ikyky.features.processing.domain.model

/**
 * A single progress tick emitted by the pipeline while it runs on a background
 * dispatcher. [fraction] is derived from real work done
 * (e.g. framesProcessed / framesPlanned), never faked.
 */
data class ProcessingProgress(
    val stage: ProcessingStage,
    /** 0f..1f fraction within the whole Phase-2 pipeline. */
    val fraction: Float,
    /** Optional human-readable detail, e.g. "frame 42 / 120 · 3 faces". */
    val detail: String? = null,
    /** Running counters so the UI can show live diagnostics. */
    val diagnostics: ProcessingDiagnostics = ProcessingDiagnostics(),
)
