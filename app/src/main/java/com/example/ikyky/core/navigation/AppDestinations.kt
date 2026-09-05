package com.example.ikyky.core.navigation

/**
 * Type-safe-ish route registry for the single-activity Compose navigation graph.
 * The pipeline is a linear flow: select → process → people/collage → result.
 */
object AppDestinations {
    const val VIDEO_SELECTION = "video_selection"
    const val PROCESSING = "processing"
    const val PEOPLE = "people"
    const val PERSON_DETAIL = "person_detail"
    const val COLLAGE = "collage"
    const val RESULT = "result"

    const val START = VIDEO_SELECTION
}
