package com.example.ikyky.features.collage.domain.usecase

import android.graphics.Bitmap
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.collage.domain.model.LayoutStyle

/**
 * Turns the session's people (one representative crop each) into a single
 * collage bitmap containing every person exactly once.
 *
 * Phase 1: contract only. Implementation composes [LayoutEngine] +
 * [CollageRenderer].
 */
interface GenerateCollageUseCase {
    suspend operator fun invoke(
        sessionId: String,
        outputWidth: Int,
        outputHeight: Int,
        preferredStyle: LayoutStyle? = null,
    ): AppResult<Bitmap>
}
