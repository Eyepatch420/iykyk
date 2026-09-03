package com.example.ikyky.features.processing.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.processing.domain.model.ProcessingStage
import com.example.ikyky.features.processing.domain.usecase.ProcessVideoUseCase
import com.example.ikyky.features.processing.presentation.state.ProcessingUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns one processing run. The heavy work is entirely inside
 * [ProcessVideoUseCase] (which switches to a background dispatcher itself); this
 * ViewModel only launches it in [viewModelScope], mirrors progress into
 * immutable state, and supports cancellation. No ML / pipeline logic here.
 */
class ProcessingViewModel(
    private val processVideo: ProcessVideoUseCase,
    private val sessionId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProcessingUiState())
    val uiState: StateFlow<ProcessingUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun start(uriString: String) {
        if (job?.isActive == true || _uiState.value.finished) return
        _uiState.value = ProcessingUiState(running = true, stage = ProcessingStage.LOADING_VIDEO)

        job = viewModelScope.launch {
            val result = processVideo(sessionId, uriString) { progress ->
                _uiState.update {
                    it.copy(
                        stage = progress.stage,
                        fraction = progress.fraction,
                        detail = progress.detail,
                        diagnostics = progress.diagnostics,
                    )
                }
            }
            _uiState.update {
                when (result) {
                    is AppResult.Success -> it.copy(
                        running = false,
                        stage = ProcessingStage.COMPLETED,
                        fraction = 1f,
                        outcome = result.value,
                        diagnostics = result.value.diagnostics,
                    )
                    is AppResult.Failure -> it.copy(
                        running = false,
                        stage = ProcessingStage.ERROR,
                        error = result.error,
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _uiState.update {
            it.copy(running = false, stage = ProcessingStage.ERROR, error = AppError.Cancelled)
        }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}
