package com.example.ikyky.features.collage.domain.model

/**
 * One person's chosen source image for a collage, decoupled from HOW that
 * image was picked. Today [ChooseCollageImagesUseCase][com.example.ikyky.features.collage.domain.usecase.ChooseCollageImagesUseCase]
 * always resolves this to a [Person.representativeFrame][com.example.ikyky.features.people.domain.model.Person],
 * but nothing downstream (the layout engines, the renderer) knows that —
 * a future version could resolve it to a user-picked appearance, the
 * highest-quality of several appearances, or more than one image per person,
 * without touching recognition, clustering, or the renderer.
 */
data class CollageImageAssignment(
    val personId: String,
    val imageUri: String,
    /** The appearance/quality metadata behind [imageUri], if known — informs priority. */
    val qualityScore: Float = 1f,
)

/** One assignment attempt that failed, with why — surfaced to diagnostics/UI, never silently dropped. */
data class SkippedPerson(
    val personId: String,
    val reason: String,
)

/** The full result of resolving images for a set of people: who got one, and who didn't and why. */
data class CollageImageSelection(
    val assignments: List<CollageImageAssignment>,
    val skipped: List<SkippedPerson>,
)
