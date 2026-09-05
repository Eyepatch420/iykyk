package com.example.ikyky.features.people.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import com.example.ikyky.features.people.domain.usecase.PersonAppearancesUseCase
import com.example.ikyky.features.people.presentation.state.PersonDetailUiState
import com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns one [com.example.ikyky.features.people.domain.model.Person]'s detail
 * view — their [com.example.ikyky.features.people.domain.model.Appearance]s,
 * for visually validating grouping/representative-frame quality (Phase 7 §8).
 *
 * People are already built by the time this runs (this screen is reached
 * FROM [com.example.ikyky.features.people.presentation.viewmodel.PeopleViewModel]'s
 * result) — this ViewModel only reads existing repository state, it never
 * re-runs clustering or embedding.
 */
class PersonDetailViewModel(
    private val peopleRepository: PeopleResultRepository,
    private val processingRepository: ProcessingResultRepository,
    private val personAppearances: PersonAppearancesUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    fun load(sessionId: String, personId: String) {
        viewModelScope.launch {
            val person = peopleRepository.getPeople(sessionId).firstOrNull { it.id == personId }
            if (person == null) {
                _uiState.value = PersonDetailUiState(
                    loading = false,
                    error = "Person not found for this session",
                )
                return@launch
            }
            val candidates = processingRepository.getCandidates(sessionId)
            _uiState.value = PersonDetailUiState(
                loading = false,
                person = person,
                appearances = personAppearances.appearancesFor(person, candidates),
            )
        }
    }
}
