package com.example.ikyky.features.processing.data.repository

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.processing.domain.model.EmbeddingDiagnostics
import com.example.ikyky.features.processing.domain.model.ProcessingDiagnostics
import com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository
import java.util.concurrent.ConcurrentHashMap

class InMemoryProcessingResultRepository : ProcessingResultRepository {

    private data class Entry(
        val candidates: List<AppearanceCandidate>,
        val diagnostics: ProcessingDiagnostics,
        val sourceUri: String? = null,
        val appearanceEmbeddings: List<AppearanceEmbedding> = emptyList(),
        val embeddedObservations: List<EmbeddedFaceObservation> = emptyList(),
        val embeddingDiagnostics: EmbeddingDiagnostics? = null,
    )

    private val bySession = ConcurrentHashMap<String, Entry>()

    override fun setResult(
        sessionId: String,
        candidates: List<AppearanceCandidate>,
        diagnostics: ProcessingDiagnostics,
        sourceUri: String?,
    ) {
        bySession.compute(sessionId) { _, prev ->
            (prev ?: Entry(candidates, diagnostics)).copy(
                candidates = candidates,
                diagnostics = diagnostics,
                sourceUri = sourceUri ?: prev?.sourceUri,
            )
        }
    }

    override fun getCandidates(sessionId: String): List<AppearanceCandidate> =
        bySession[sessionId]?.candidates.orEmpty()

    override fun getDiagnostics(sessionId: String): ProcessingDiagnostics? =
        bySession[sessionId]?.diagnostics

    override fun getSourceUri(sessionId: String): String? =
        bySession[sessionId]?.sourceUri

    override fun setEmbeddings(
        sessionId: String,
        appearanceEmbeddings: List<AppearanceEmbedding>,
        perObservation: List<EmbeddedFaceObservation>,
        diagnostics: EmbeddingDiagnostics,
    ) {
        bySession.compute(sessionId) { _, prev ->
            (prev ?: Entry(emptyList(), ProcessingDiagnostics())).copy(
                appearanceEmbeddings = appearanceEmbeddings,
                embeddedObservations = perObservation,
                embeddingDiagnostics = diagnostics,
            )
        }
    }

    override fun getAppearanceEmbeddings(sessionId: String): List<AppearanceEmbedding> =
        bySession[sessionId]?.appearanceEmbeddings.orEmpty()

    override fun getEmbeddedObservations(sessionId: String): List<EmbeddedFaceObservation> =
        bySession[sessionId]?.embeddedObservations.orEmpty()

    override fun getEmbeddingDiagnostics(sessionId: String): EmbeddingDiagnostics? =
        bySession[sessionId]?.embeddingDiagnostics

    override fun clear() = bySession.clear()
}
