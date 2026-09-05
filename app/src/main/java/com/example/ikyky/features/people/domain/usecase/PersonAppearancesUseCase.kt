package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.features.people.domain.model.Appearance
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate

/**
 * Turns one Phase-2 [AppearanceCandidate] into its product-facing [Appearance]
 * projection. Pure mapping — every field already exists on the candidate.
 */
fun AppearanceCandidate.toAppearance(personId: String): Appearance = Appearance(
    id = id,
    personId = personId,
    startTimestampMs = startTimestampMs,
    endTimestampMs = endTimestampMs,
    bestFrameIndex = bestFrameIndex,
    bestFrameTimestampMs = bestFrameTimestampMs,
    bestFrameBox = bestFrameBox,
    bestQuality = bestQuality,
    meanQuality = meanQuality,
    observationCount = observationCount,
)

/**
 * Resolves a [Person]'s [Appearance]s by joining [Person.appearanceIds] against
 * the session's [AppearanceCandidate]s (which the identity stage discards once
 * clustering is done — [Person] itself only keeps the id strings).
 *
 * Deterministic: appearances are returned ordered by [Appearance.startTimestampMs]
 * then id, matching the order the frozen pipeline detected them in.
 */
class PersonAppearancesUseCase {

    fun appearancesFor(person: Person, candidates: List<AppearanceCandidate>): List<Appearance> {
        val byId = candidates.associateBy { it.id }
        return person.appearanceIds
            .mapNotNull { byId[it] }
            .map { it.toAppearance(person.id) }
            .sortedWith(compareBy({ it.startTimestampMs }, { it.id }))
    }
}
