package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.processing.domain.model.ProcessingOutcome
import com.example.ikyky.features.processing.domain.model.ProcessingProgress

/**
 * Orchestrates the on-device pipeline for one video.
 *
 * Phase 2 scope:
 *   video URI → metadata → frame timestamps → frame extraction →
 *   multi-face detection → tracklets → appearance candidates → result
 *
 * Embedding, clustering and collage stages are deliberately NOT invoked.
 *
 * Contract:
 *  - runs entirely off the main thread (caller wraps in a background dispatcher)
 *  - reports real progress via [onProgress], invoked on the pipeline's dispatcher
 *  - honours coroutine cancellation (never swallows `CancellationException`)
 *  - writes the appearance candidates + diagnostics into the
 *    `ProcessingResultRepository` under [sessionId] before returning
 */
interface ProcessVideoUseCase {
    suspend operator fun invoke(
        sessionId: String,
        uriString: String,
        onProgress: (ProcessingProgress) -> Unit,
    ): AppResult<ProcessingOutcome>
}
