package com.example.ikyky.core.ml.detector

import com.example.ikyky.core.common.constants.PipelineDefaults

/**
 * Tunable ML Kit face-detector configuration.
 *
 * Production uses [DEFAULT]. Phase 2.5 debugging builds detectors with other
 * values to compare detection behaviour (resolution / minFaceSize / mode /
 * tracking) against Sample 1 — the production default is only changed once the
 * evidence justifies it.
 */
data class DetectorTuning(
    val accurateMode: Boolean = true,
    val minFaceFraction: Float = PipelineDefaults.MLKIT_MIN_FACE_FRACTION,
    val enableTracking: Boolean = true,
    val landmarks: Boolean = true,
    val classification: Boolean = true,
) {
    companion object {
        val DEFAULT = DetectorTuning()
    }
}
