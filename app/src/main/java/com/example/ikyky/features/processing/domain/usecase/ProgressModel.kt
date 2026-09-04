package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.features.processing.domain.model.ProcessingStage

/**
 * Maps real work done to a single 0f..1f pipeline fraction.
 *
 * The pipeline runs TWO decode passes:
 *   1. the every-frame shot scan (~750 frames, cheap per frame, but ~55 s total)
 *   2. the 8 FPS sampled detect loop (~240 frames, ML Kit per frame)
 *
 * The shot scan is a large fraction of wall-clock, so it gets its own band —
 * otherwise the bar sits at 3% for a minute while a 750-frame pass runs behind a
 * misleading "0 / 240" counter (the Phase 6.1 UI bug).
 */
object ProgressModel {

    private const val LOAD_END = 0.03f
    private const val SHOT_SCAN_END = 0.45f   // shot scan spans [LOAD_END, SHOT_SCAN_END]
    private const val DETECT_END = 0.95f      // detect loop spans [SHOT_SCAN_END, DETECT_END]
    private const val FINALIZE_END = 1.00f

    /** Fraction while the shot scan has analysed [framesDone] of [framesPlanned]. */
    fun shotScanFraction(framesDone: Int, framesPlanned: Int): Float {
        if (framesPlanned <= 0) return LOAD_END
        val p = (framesDone.toFloat() / framesPlanned).coerceIn(0f, 1f)
        return LOAD_END + p * (SHOT_SCAN_END - LOAD_END)
    }

    /** Fraction while extracting + detecting sampled frame [framesDone] of [framesPlanned]. */
    fun frameLoopFraction(framesDone: Int, framesPlanned: Int): Float {
        if (framesPlanned <= 0) return SHOT_SCAN_END
        val p = (framesDone.toFloat() / framesPlanned).coerceIn(0f, 1f)
        return SHOT_SCAN_END + p * (DETECT_END - SHOT_SCAN_END)
    }

    fun stageFraction(stage: ProcessingStage): Float = when (stage) {
        ProcessingStage.LOADING_VIDEO -> 0f
        ProcessingStage.EXTRACTING_FRAMES -> LOAD_END
        ProcessingStage.DETECTING_FACES -> SHOT_SCAN_END
        ProcessingStage.TRACKING_APPEARANCES -> DETECT_END
        ProcessingStage.FINALIZING_APPEARANCES -> 0.5f * (DETECT_END + FINALIZE_END)
        ProcessingStage.COMPLETED -> 1f
        ProcessingStage.ERROR -> 0f
        else -> 0f
    }
}
