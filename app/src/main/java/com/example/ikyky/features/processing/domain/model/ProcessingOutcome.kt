package com.example.ikyky.features.processing.domain.model

/**
 * The result payload produced when the Phase-2 pipeline finishes.
 *
 * Holds lightweight references only. The full [AppearanceCandidate] list and its
 * diagnostics are kept in an in-memory session holder
 * ([com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository])
 * keyed by [sessionId]; downstream screens read from there.
 *
 * `uniquePeopleCount` is intentionally absent — unique-people determination is
 * Phase 3/4 (clustering). Phase 2 knows appearance *candidates*, not people.
 */
data class ProcessingOutcome(
    val sessionId: String,
    val appearanceCandidateCount: Int,
    val diagnostics: ProcessingDiagnostics,
)
