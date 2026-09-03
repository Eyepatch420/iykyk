package com.example.ikyky.core.ml.embedding

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.core.model.PreprocessedFace

/**
 * Produces an L2-normalized identity embedding for a single aligned face crop.
 * The underlying runtime (LiteRT today) never leaks past this interface.
 */
interface FaceEmbedder {

    /** Embedding dimension the model produces (192 for MobileFaceNet). */
    val dimension: Int

    suspend fun embed(face: PreprocessedFace): AppResult<FaceEmbedding>

    /** Releases the interpreter. Safe to call multiple times. */
    fun close()
}
