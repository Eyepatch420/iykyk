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

class DefaultFacePreprocessor(
    private val aligner: SimilarityTransformFaceAligner = SimilarityTransformFaceAligner(),
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
            aligner.presentationCrop(frame, face, PipelineDefaults.PRESENTATION_CROP_MARGIN)
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
