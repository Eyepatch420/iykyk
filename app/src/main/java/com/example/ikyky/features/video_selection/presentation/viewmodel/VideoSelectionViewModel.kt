package com.example.ikyky.features.video_selection.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.video_selection.domain.usecase.SelectVideoUseCase
import com.example.ikyky.features.video_selection.presentation.state.VideoSelectionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VideoSelectionViewModel constructor(
    private val selectVideo: SelectVideoUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoSelectionUiState())
    val uiState: StateFlow<VideoSelectionUiState> = _uiState.asStateFlow()

    fun onVideoPicked(uriString: String) {
        _uiState.update { it.copy(isValidating = true, error = null, selected = null) }
        viewModelScope.launch {
            when (val result = selectVideo(uriString)) {
                is AppResult.Success ->
                    _uiState.update { it.copy(isValidating = false, selected = result.value) }
                is AppResult.Failure ->
                    _uiState.update { it.copy(isValidating = false, error = result.error) }
            }
        }
    }

    fun onErrorConsumed() = _uiState.update { it.copy(error = null) }
}
