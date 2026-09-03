package com.example.ikyky.core.model

/**
 * Our own representation of a single detected face in a single frame.
 * Deliberately decoupled from ML Kit's `Face` so the rest of the app never
 * imports `com.google.mlkit.*`.
 */
data class DetectedFace(
    val boundingBox: BoundingBox,
    val landmarks: List<Landmark> = emptyList(),
    val headPose: HeadPose? = null,
    val leftEyeOpenProbability: Float? = null,
    val rightEyeOpenProbability: Float? = null,
    val smilingProbability: Float? = null,
    /**
     * ML Kit's short-term tracking id. NOT a global identity — only temporal
     * continuity within a contiguous run of frames. May be null if tracking is
     * disabled or unavailable for this detection.
     */
    val trackingId: Int? = null,
)
