package com.example.ikyky.features.collage.data

import android.graphics.Bitmap
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase

/** Phase 1 placeholder — see [com.example.ikyky.features.processing.data.ProcessVideoUseCasePhase1Stub]. */
class GenerateCollagePhase1Stub constructor() : GenerateCollageUseCase {
    override suspend fun invoke(
        sessionId: String,
        outputWidth: Int,
        outputHeight: Int,
        preferredStyle: LayoutStyle?,
    ): AppResult<Bitmap> =
        AppResult.Failure(
            AppError.Unexpected("Collage generation is not implemented yet (Phase 1 skeleton).")
        )
}
