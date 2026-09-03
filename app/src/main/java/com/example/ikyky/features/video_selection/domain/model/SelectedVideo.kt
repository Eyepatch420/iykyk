package com.example.ikyky.features.video_selection.domain.model

/**
 * A validated video selection ready to be handed to the processing flow.
 * The raw content URI is kept as a String so this domain model stays free of
 * `android.net.Uri`.
 */
data class SelectedVideo(
    val uriString: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
)
