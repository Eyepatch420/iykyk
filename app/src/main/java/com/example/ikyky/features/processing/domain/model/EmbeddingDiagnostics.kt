package com.example.ikyky.features.processing.domain.model

/**
 * Counters and timings for the Phase-3 embedding stage. Purely diagnostic —
 * surfaced alongside [ProcessingDiagnostics] so the embedding path can be
 * inspected and benchmarked without a profiler.
 */
data class EmbeddingDiagnostics(
    val appearancesProcessed: Int = 0,
    val appearancesEmbedded: Int = 0,
    /** Appearances that yielded zero usable embeddings (all crops invalid, etc.). */
    val appearancesWithoutEmbedding: Int = 0,
    val observationsSelected: Int = 0,
    val embeddingCalls: Int = 0,
    val embeddingFailures: Int = 0,
    val framesRedecoded: Int = 0,
    val alignedByLandmarks: Int = 0,
    val alignedByBoxFallback: Int = 0,
    val outliersRejected: Int = 0,

    val modelLoadMs: Long = 0,
    val totalCropMs: Long = 0,
    val totalAlignMs: Long = 0,
    val totalPreprocessMs: Long = 0,
    val totalInferenceMs: Long = 0,
    val totalRedecodeMs: Long = 0,
    val totalStageMs: Long = 0,
) {
    val avgInferenceMs: Double
        get() = if (embeddingCalls == 0) 0.0 else totalInferenceMs.toDouble() / embeddingCalls
    val avgRedecodeMs: Double
        get() = if (framesRedecoded == 0) 0.0 else totalRedecodeMs.toDouble() / framesRedecoded
}
