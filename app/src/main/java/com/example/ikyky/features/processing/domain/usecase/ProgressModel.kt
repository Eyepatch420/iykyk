package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.features.processing.domain.model.ProcessingStage

/**
 * Maps real work done to a single 0f..1f pipeline fraction.
 *
 * Phase-2 weighting: the per-frame detect loop dominates wall-clock, so it owns
 * most of the bar. Loading + finalizing are near-instant book-ends.
 */
object ProgressModel {

    private const val LOAD_END = 0.03f
    private const val DETECT_END = 0.95f      // frame loop spans [LOAD_END, DETECT_END]
    private const val FINALIZE_END = 1.00f

    /** Fraction while extracting + detecting frame [framesDone] of [framesPlanned]. */
    fun frameLoopFraction(framesDone: Int, framesPlanned: Int): Float {
        if (framesPlanned <= 0) return LOAD_END
        val p = (framesDone.toFloat() / framesPlanned).coerceIn(0f, 1f)
        return LOAD_END + p * (DETECT_END - LOAD_END)
    }

    fun stageFraction(stage: ProcessingStage): Float = when (stage) {
        ProcessingStage.LOADING_VIDEO -> 0f
        ProcessingStage.EXTRACTING_FRAMES,
        ProcessingStage.DETECTING_FACES -> LOAD_END
        ProcessingStage.TRACKING_APPEARANCES -> DETECT_END
        ProcessingStage.FINALIZING_APPEARANCES -> 0.5f * (DETECT_END + FINALIZE_END)
        ProcessingStage.COMPLETED -> 1f
        ProcessingStage.ERROR -> 0f
        else -> 0f
    }
}
