package com.example.ikyky.core.ml.preprocessing

import android.graphics.Bitmap
import com.example.ikyky.core.model.DetectedFace

/**
 * Aligns a face so the eyes sit on a canonical horizontal line before it is fed
 * to the embedding model. Alignment materially improves identity-embedding
 * quality for pose-varied video frames.
 *
 * If eye landmarks are unavailable / implausible the implementation falls back
 * to a margin-expanded bounding-box crop (never a mirror, never a crash).
 */
interface FaceAligner {
    /**
     * @param frame the full (canonical, upright) frame bitmap
     * @param face  the detection within [frame], in canonical coordinates
     * @param outputSize edge length of the square aligned crop
     * @return an aligned square crop, or null if alignment is not possible
     */
    fun align(frame: Bitmap, face: DetectedFace, outputSize: Int): Bitmap?

    /** [align] plus whether landmarks drove the transform (false ⇒ box-crop fallback). */
    fun alignDetailed(frame: Bitmap, face: DetectedFace, outputSize: Int): AlignResult?
}

/** @property alignedByLandmarks true if a landmark similarity transform produced [bitmap]. */
data class AlignResult(val bitmap: Bitmap, val alignedByLandmarks: Boolean)
