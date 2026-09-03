package com.example.ikyky.features.processing.presentation.state

import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.features.processing.domain.model.ProcessingDiagnostics
import com.example.ikyky.features.processing.domain.model.ProcessingOutcome
import com.example.ikyky.features.processing.domain.model.ProcessingStage

/** Immutable UI state for the processing screen. */
data class ProcessingUiState(
    val running: Boolean = false,
    val stage: ProcessingStage = ProcessingStage.LOADING_VIDEO,
    val fraction: Float = 0f,
    val detail: String? = null,
    val diagnostics: ProcessingDiagnostics = ProcessingDiagnostics(),
    val outcome: ProcessingOutcome? = null,
    val error: AppError? = null,
) {
    val finished: Boolean get() = outcome != null
    val cancelled: Boolean get() = error is AppError.Cancelled
}
