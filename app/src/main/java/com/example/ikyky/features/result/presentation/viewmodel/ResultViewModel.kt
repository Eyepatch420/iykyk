package com.example.ikyky.features.result.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.collage.domain.repository.CollageResultRepository
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import com.example.ikyky.features.result.domain.usecase.SaveCollageUseCase
import com.example.ikyky.features.result.domain.usecase.ShareCollageUseCase
import com.example.ikyky.features.result.presentation.state.ResultUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ResultViewModel constructor(
    private val peopleRepository: PeopleResultRepository,
    private val collageRepository: CollageResultRepository,
    private val saveCollage: SaveCollageUseCase,
    private val shareCollage: ShareCollageUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    fun load(sessionId: String) {
        val people = peopleRepository.getPeople(sessionId)
        _uiState.update {
            it.copy(
                collage = collageRepository.getCollage(sessionId),
                uniquePeople = people.size,
                totalAppearances = people.sumOf { p -> p.appearanceCount },
            )
        }
    }

    fun onSave(displayName: String) {
        val bitmap = _uiState.value.collage ?: return
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = saveCollage(bitmap, displayName)) {
                is AppResult.Success -> _uiState.update { it.copy(saving = false, savedUri = r.value) }
                is AppResult.Failure -> _uiState.update { it.copy(saving = false, error = r.error) }
            }
        }
    }

    fun onShare(displayName: String) {
        val bitmap = _uiState.value.collage ?: return
        viewModelScope.launch {
            val r = shareCollage(bitmap, displayName)
            if (r is AppResult.Failure) _uiState.update { it.copy(error = r.error) }
        }
    }
}
