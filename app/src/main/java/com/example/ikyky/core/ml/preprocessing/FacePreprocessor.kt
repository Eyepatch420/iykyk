package com.example.ikyky.core.ml.preprocessing

import android.graphics.Bitmap
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.PreprocessedFace

/**
 * Turns a (frame, detection) pair into a [PreprocessedFace]:
 *
 *   detected face → align → resize → (recognition crop)   ← used for embedding
 *                        └→ margin-expanded crop (presentation) ← used for collage
 *
 * The recognition crop and the presentation crop are kept as separate concepts
 * on purpose: the tiny detection box must never be used directly as a collage
 * tile.
 */
interface FacePreprocessor {
    fun preprocess(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int = PipelineDefaults.EMBEDDING_INPUT_SIZE,
        includePresentationCrop: Boolean = true,
    ): AppResult<PreprocessedFace>
}

/**
 * @param aligner produces the **recognition** crop. FROZEN to
 *   [ArcFaceFivePointAligner] by Phase 5I §9.2 — the canonical 5-point
 *   similarity transform MobileFaceNet was trained against. The previous
 *   [SimilarityTransformFaceAligner] (eye-line, 0.38 inter-ocular / 0.38 eye row)
 *   is a *different* template and is the geometry Phase 5H identified as the
 *   single largest source of cross-shot recognition error.
 * @param presentationAligner produces the collage crop only. Still the eye-line
 *   aligner, which owns [SimilarityTransformFaceAligner.presentationCrop]; it
 *   must never feed the embedder.
 */
class DefaultFacePreprocessor(
    private val aligner: FaceAligner = ArcFaceFivePointAligner(),
    private val presentationAligner: SimilarityTransformFaceAligner = SimilarityTransformFaceAligner(),
) : FacePreprocessor {

    override fun preprocess(
        frame: Bitmap,
        face: DetectedFace,
        outputSize: Int,
        includePresentationCrop: Boolean,
    ): AppResult<PreprocessedFace> = runCatchingResult {
        val aligned = aligner.alignDetailed(frame, face, outputSize)
            ?: error("Face alignment failed")
        val presentation = if (includePresentationCrop) {
            presentationAligner.presentationCrop(frame, face, PipelineDefaults.PRESENTATION_CROP_MARGIN)
        } else {
            null
        }
        PreprocessedFace(
            bitmap = aligned.bitmap,
            size = outputSize,
            presentationCrop = presentation,
            alignedByLandmarks = aligned.alignedByLandmarks,
        )
    }
}
