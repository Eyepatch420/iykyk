package com.example.ikyky.features.collage.domain.usecase

import com.example.ikyky.features.collage.domain.model.CollageImageAssignment
import com.example.ikyky.features.collage.domain.model.CollageImageSelection
import com.example.ikyky.features.collage.domain.model.SkippedPerson
import com.example.ikyky.features.people.domain.model.Person

/**
 * Resolves ONE source image per [Person] for a collage. Today that is always
 * [Person.representativeFrame] (Phase 7's one-crop-per-person selection) — this
 * type exists so a future strategy (user-picked appearance, best-of-N, multiple
 * images per person) is a NEW implementation of this interface, not a change to
 * the layout engines, the renderer, or recognition/clustering.
 *
 * Never crashes on a missing/blank representative frame: that person is
 * reported in [CollageImageSelection.skipped] with a reason, not silently
 * dropped and not a thrown exception. A duplicate [Person.representativeFrame]
 * URI across two people (e.g. a selection bug upstream) is passed through
 * as-is — the renderer treats each assignment independently by personId, so a
 * duplicate URI cannot corrupt layout, only put the same pixels in two slots.
 */
interface ChooseCollageImagesUseCase {
    fun choose(people: List<Person>): CollageImageSelection
}

class DefaultChooseCollageImagesUseCase : ChooseCollageImagesUseCase {

    override fun choose(people: List<Person>): CollageImageSelection {
        val assignments = ArrayList<CollageImageAssignment>(people.size)
        val skipped = ArrayList<SkippedPerson>()

        for (person in people) {
            val frame = person.representativeFrame
            val uri = frame?.presentationCropKey
            if (uri.isNullOrBlank()) {
                skipped += SkippedPerson(person.id, "no representative image available")
                continue
            }
            assignments += CollageImageAssignment(
                personId = person.id,
                imageUri = uri,
                qualityScore = frame.qualityScore,
            )
        }
        return CollageImageSelection(assignments, skipped)
    }
}
