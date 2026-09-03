package com.example.ikyky.core.ml.detector

import android.graphics.Bitmap
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.model.DetectedFace

/**
 * On-device multi-face detector abstraction.
 *
 * The concrete implementation ([MlKitFaceDetector]) wraps ML Kit; callers only
 * ever see [DetectedFace]. Detection is expected to run off the main thread.
 */
interface FaceDetector {

    /** Detect all faces in a single frame bitmap. */
    suspend fun detect(bitmap: Bitmap, rotationDegrees: Int = 0): AppResult<List<DetectedFace>>

    /** Releases native resources. Safe to call multiple times. */
    fun close()
}
