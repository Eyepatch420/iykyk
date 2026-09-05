package com.example.ikyky.features.people.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.media.VideoMetadataReader
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import com.example.ikyky.features.people.domain.usecase.BuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.SelectRepresentativeImagesUseCase
import com.example.ikyky.features.people.presentation.state.PeopleUiState
import com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the identity stage for one session.
 *
 * Phase 2 (appearance candidates) is already in [processingRepository]. On
 * [load] this runs **Phase 3** (appearance → embeddings) then **Phase 4**
 * (must-not-link → clustering → `Person[]`), then **Phase 7** (representative
 * image selection — one crop per person, persisted via
 * [SelectRepresentativeImagesUseCase]), stores the result and mirrors it into
 * immutable UI state. If people were already built for this session, it just
 * reads them back.
 *
 * No ML / clustering logic here — only orchestration + state.
 */
class PeopleViewModel(
    private val peopleRepository: PeopleResultRepository,
    private val processingRepository: ProcessingResultRepository,
    private val metadataReader: VideoMetadataReader,
    private val generateEmbeddings: GenerateAppearanceEmbeddingsUseCase,
    private val buildIdentities: BuildIdentitiesUseCase,
    private val selectRepresentativeImages: SelectRepresentativeImagesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PeopleUiState())
    val uiState: StateFlow<PeopleUiState> = _uiState.asStateFlow()

    fun load(sessionId: String) {
        val existing = peopleRepository.getPeople(sessionId)
        if (existing.isNotEmpty()) {
            _uiState.value = PeopleUiState(
                people = existing,
                diagnostics = peopleRepository.getDiagnostics(sessionId),
                loading = false,
            )
            return
        }

        _uiState.value = PeopleUiState(loading = true)
        viewModelScope.launch {
            val appearances = processingRepository.getCandidates(sessionId)
            val uri = processingRepository.getSourceUri(sessionId)
            if (appearances.isEmpty() || uri.isNullOrBlank()) {
                _uiState.update { it.copy(loading = false, error = "No processed video for this session") }
                return@launch
            }

            val metadata = when (val m = metadataReader.read(uri)) {
                is AppResult.Success -> m.value
                is AppResult.Failure -> {
                    _uiState.update { it.copy(loading = false, error = m.error.message) }
                    return@launch
                }
            }

            // Phase 3 — appearance embeddings
            val emb = when (val r = generateEmbeddings(uri, metadata, appearances)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> {
                    _uiState.update { it.copy(loading = false, error = r.error.message) }
                    return@launch
                }
            }
            processingRepository.setEmbeddings(
                sessionId, emb.appearanceEmbeddings, emb.perObservation, emb.diagnostics,
            )

            // Phase 4 (+ 4.5 dense refinement) — identities
            when (
                val r = buildIdentities(
                    appearances = appearances,
                    embeddedObservations = emb.perObservation,
                    uriString = uri,
                    metadata = metadata,
                    denseEmbedder = generateEmbeddings,
                )
            ) {
                is AppResult.Success -> {
                    // Phase 7 — one representative crop per person. A pure
                    // presentation step: it can only fill in
                    // Person.representativeFrame or leave it null on failure,
                    // never change WHO is in which cluster.
                    val withImages = selectRepresentativeImages.select(
                        sessionId = sessionId,
                        uriString = uri,
                        metadata = metadata,
                        people = r.value.people,
                        candidates = appearances,
                    )
                    peopleRepository.setPeople(sessionId, withImages, r.value.diagnostics)
                    _uiState.update {
                        it.copy(
                            loading = false,
                            people = withImages,
                            diagnostics = r.value.diagnostics,
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(loading = false, error = r.error.message) }
                }
            }
        }
    }
}
