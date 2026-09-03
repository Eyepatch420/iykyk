package com.example.ikyky.core.model

import android.graphics.Bitmap

/**
 * A face crop that has been aligned, resized and is ready for the embedding
 * model. [bitmap] is exactly [size] x [size] (default 112) with no further
 * normalization applied yet — pixel normalization happens inside the embedder so
 * the model's expected input scaling stays co-located with the model.
 *
 * The **recognition crop** ([bitmap]) and the **presentation crop**
 * ([presentationCrop]) are separate on purpose: the tiny detection box must
 * never be used directly as a collage tile.
 */
data class PreprocessedFace(
    val bitmap: Bitmap,
    val size: Int,
    /** The presentation-quality crop for this same face (larger, un-aligned). */
    val presentationCrop: Bitmap? = null,
    /**
     * True if a landmark-driven similarity transform produced [bitmap]; false if
     * it fell back to a margin-expanded bounding-box crop.
     */
    val alignedByLandmarks: Boolean = false,
)
