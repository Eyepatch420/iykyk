package com.example.ikyky.core.common.error

/**
 * Framework-agnostic error taxonomy for the on-device pipeline.
 *
 * Kept intentionally small for a time-boxed assignment; each stage of the
 * pipeline maps its failures onto one of these cases so the UI can render a
 * sensible message without knowing about ML Kit / LiteRT / MediaMetadataRetriever.
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {

    /** The selected media could not be opened, is not a video, or is unreadable. */
    data class InvalidVideo(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    /** Frame extraction failed or produced no usable frames. */
    data class FrameExtraction(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    /** An ML component (detector / embedder / model loader) failed. */
    data class MlInference(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    /** The embedding model asset is missing or could not be loaded. */
    data class ModelUnavailable(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    /** No faces / no distinct people were found in the video. */
    data class NoPeopleDetected(override val message: String = "No people detected in the video") :
        AppError(message)

    /** Saving the collage to the gallery failed. */
    data class Storage(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)

    /** The video decoded but produced no face observations at all. */
    data class NoFacesDetected(override val message: String = "No faces detected in the video") :
        AppError(message)

    /** The user cancelled a long-running operation. */
    data object Cancelled : AppError("Operation cancelled")

    /** Anything not otherwise classified. */
    data class Unexpected(override val message: String, override val cause: Throwable? = null) :
        AppError(message, cause)
}
