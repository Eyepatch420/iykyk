package com.example.ikyky.features.collage.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.repository.CollageResultRepository
import com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase
import com.example.ikyky.features.collage.presentation.state.CollageUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollageViewModel constructor(
    private val generateCollage: GenerateCollageUseCase,
    private val collageRepository: CollageResultRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollageUiState())
    val uiState: StateFlow<CollageUiState> = _uiState.asStateFlow()

    fun generate(
        sessionId: String,
        width: Int,
        height: Int,
        style: LayoutStyle? = null,
    ) {
        _uiState.update { it.copy(generating = true, error = null, style = style) }
        viewModelScope.launch {
            val result = withContext(dispatchers.default) {
                generateCollage(sessionId, width, height, style)
            }
            _uiState.update {
                when (result) {
                    is AppResult.Success -> {
                        collageRepository.setCollage(sessionId, result.value)
                        it.copy(generating = false, collage = result.value)
                    }
                    is AppResult.Failure -> it.copy(generating = false, error = result.error)
                }
            }
        }
    }
}
