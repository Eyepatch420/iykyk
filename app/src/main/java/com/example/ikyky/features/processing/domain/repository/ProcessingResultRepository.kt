package com.example.ikyky.features.processing.domain.repository

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.model.EmbeddingDiagnostics
import com.example.ikyky.features.processing.domain.model.ProcessingDiagnostics

/**
 * In-memory holder for one processing session's pipeline output — the Phase-2
 * appearance candidates + diagnostics, and (Phase 3) the per-appearance
 * embeddings. Populated by the pipeline, read by the people / clustering
 * stages. Not persisted (single-session app).
 */
interface ProcessingResultRepository {
    fun setResult(
        sessionId: String,
        candidates: List<AppearanceCandidate>,
        diagnostics: ProcessingDiagnostics,
        sourceUri: String? = null,
    )

    fun getCandidates(sessionId: String): List<AppearanceCandidate>
    fun getDiagnostics(sessionId: String): ProcessingDiagnostics?

    /** The video URI this session was processed from (needed by the embedding stage). */
    fun getSourceUri(sessionId: String): String?

    // --- Phase 3 ---
    fun setEmbeddings(
        sessionId: String,
        appearanceEmbeddings: List<AppearanceEmbedding>,
        perObservation: List<EmbeddedFaceObservation>,
        diagnostics: EmbeddingDiagnostics,
    )

    fun getAppearanceEmbeddings(sessionId: String): List<AppearanceEmbedding>
    fun getEmbeddedObservations(sessionId: String): List<EmbeddedFaceObservation>
    fun getEmbeddingDiagnostics(sessionId: String): EmbeddingDiagnostics?

    fun clear()
}
