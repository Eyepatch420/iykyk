package com.example.ikyky.core.ui.state

import com.example.ikyky.core.common.error.AppError

/**
 * Generic unidirectional screen state envelope. Feature screens expose
 * `StateFlow<SomeFeatureUiState>` from their ViewModel; where a screen has
 * nothing beyond load/success/error it can reuse [UiState] directly.
 */
sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val error: AppError) : UiState<Nothing>
}
