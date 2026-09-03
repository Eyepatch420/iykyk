package com.example.ikyky.features.result.presentation.state

import android.graphics.Bitmap
import android.net.Uri
import com.example.ikyky.core.common.error.AppError

data class ResultUiState(
    val collage: Bitmap? = null,
    val uniquePeople: Int = 0,
    val totalAppearances: Int = 0,
    val saving: Boolean = false,
    val savedUri: Uri? = null,
    val error: AppError? = null,
)
