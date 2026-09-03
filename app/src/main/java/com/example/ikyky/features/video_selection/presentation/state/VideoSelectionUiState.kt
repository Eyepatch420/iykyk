package com.example.ikyky.features.video_selection.presentation.state

import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.features.video_selection.domain.model.SelectedVideo

/** Immutable UI state for the video selection screen. */
data class VideoSelectionUiState(
    val isValidating: Boolean = false,
    val selected: SelectedVideo? = null,
    val error: AppError? = null,
) {
    val canProceed: Boolean get() = selected != null && !isValidating
}
