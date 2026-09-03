package com.example.ikyky.features.processing.data

import android.graphics.Bitmap
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.ml.embedding.FaceEmbedder
import com.example.ikyky.core.ml.preprocessing.TrackerAppearanceCrop
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.core.model.PreprocessedFace

/**
 * Produces the **tracker gate** embedding for one detection: the expanded-box
 * crop ([TrackerAppearanceCrop]) run through MobileFaceNet.
 *
 * Deliberately a distinct collaborator from the recognition path
 * (`GenerateAppearanceEmbeddingsUseCase`), because the two use **different
 * crops** and conflating them is exactly the failure mode Phase 5I's Option B
 * test ruled out. Nothing here may reach for the 5-point aligner.
 *
 * A null return is not an error — it means the gate has no opinion about this
 * observation (crop collapsed against a frame edge, inference failed), and the
 * tracker falls back to geometry alone for it.
 */
class TrackerGateEmbedder(
    private val embedder: FaceEmbedder,
    private val outputSize: Int = PipelineDefaults.EMBEDDING_INPUT_SIZE,
) {

    /**
     * @param frame the decoded frame bitmap
     * @param face the detection **in that bitmap's pixel space** (not canonical)
     */
    suspend fun embed(frame: Bitmap, face: DetectedFace): FaceEmbedding? {
        val crop = TrackerAppearanceCrop.extract(frame, face, outputSize) ?: return null
        return try {
            val pre = PreprocessedFace(
                bitmap = crop,
                size = outputSize,
                presentationCrop = null,
                // The gate crop is a plain box crop: no landmark transform was used.
                alignedByLandmarks = false,
            )
            when (val r = embedder.embed(pre)) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> null
            }
        } finally {
            if (!crop.isRecycled) runCatching { crop.recycle() }
        }
    }
}
