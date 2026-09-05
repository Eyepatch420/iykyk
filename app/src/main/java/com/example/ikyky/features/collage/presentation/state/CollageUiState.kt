package com.example.ikyky.features.collage.presentation.state

import android.graphics.Bitmap
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.features.collage.domain.model.LayoutTemplate

data class CollageUiState(
    val loading: Boolean = false,
    val generating: Boolean = false,
    val personCount: Int = 0,
    /** Every template that fits [personCount], for the style picker. */
    val availableTemplates: List<LayoutTemplate> = emptyList(),
    val selectedTemplateId: String? = null,
    val collage: Bitmap? = null,
    val error: AppError? = null,
) {
    val selectedTemplate: LayoutTemplate?
        get() = availableTemplates.firstOrNull { it.id == selectedTemplateId }
}
