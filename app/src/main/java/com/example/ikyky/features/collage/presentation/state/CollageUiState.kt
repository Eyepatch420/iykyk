package com.example.ikyky.features.collage.presentation.state

import android.graphics.Bitmap
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.features.collage.domain.model.LayoutStyle

data class CollageUiState(
    val generating: Boolean = false,
    val style: LayoutStyle? = null,
    val collage: Bitmap? = null,
    val error: AppError? = null,
)
