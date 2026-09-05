package com.example.ikyky.people

import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.usecase.PersonAppearancesUseCase
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 — proves the [Person] <-> [com.example.ikyky.features.processing.domain.model.AppearanceCandidate]
 * join: every field on [com.example.ikyky.features.people.domain.model.Appearance]
 * comes straight from the candidate, ordering is deterministic, and a
 * dangling appearance id (shouldn't happen, but the join must not crash on
 * it) is simply skipped rather than producing a broken entry.
 */
class PersonAppearancesUseCaseTest {

    private fun candidate(id: String, trackletId: Long, startMs: Long) = AppearanceCandidate(
        id = id, trackletId = trackletId,
        startTimestampMs = startMs, endTimestampMs = startMs + 500,
        firstFrameIndex = 0, lastFrameIndex = 4, observationCount = 5,
        lastTrackingId = null, meanQuality = 0.7f, bestQuality = 0.9f,
        bestFrameIndex = 2, bestFrameTimestampMs = startMs + 250,
        bestFrameBox = BoundingBox(10, 10, 110, 110),
    )

    private val useCase = PersonAppearancesUseCase()

    @Test
    fun mapsEveryFieldFromTheCandidate() {
        val c = candidate("app_1", 7, 1000)
        val person = Person("person_0", "Person 1", listOf("app_1"), null)
        val result = useCase.appearancesFor(person, listOf(c))
        assertEquals(1, result.size)
        val a = result.first()
        assertEquals("app_1", a.id)
        assertEquals("person_0", a.personId)
        assertEquals(1000L, a.startTimestampMs)
        assertEquals(1500L, a.endTimestampMs)
        assertEquals(2, a.bestFrameIndex)
        assertEquals(1250L, a.bestFrameTimestampMs)
        assertEquals(BoundingBox(10, 10, 110, 110), a.bestFrameBox)
        assertEquals(0.9f, a.bestQuality, 0f)
        assertEquals(0.7f, a.meanQuality, 0f)
        assertEquals(5, a.observationCount)
        assertEquals(500L, a.durationMs)
    }

    @Test
    fun ordersByStartTimestampThenId_regardlessOfInputOrder() {
        val candidates = listOf(
            candidate("app_c", 3, 3000),
            candidate("app_a", 1, 1000),
            candidate("app_b", 2, 2000),
        )
        val person = Person("person_0", "Person 1", listOf("app_c", "app_a", "app_b"), null)
        val result = useCase.appearancesFor(person, candidates)
        assertEquals(listOf("app_a", "app_b", "app_c"), result.map { it.id })
    }

    @Test
    fun tieBreaksOnIdWhenTimestampsAreEqual() {
        val candidates = listOf(candidate("app_z", 1, 1000), candidate("app_a", 2, 1000))
        val person = Person("person_0", "Person 1", listOf("app_z", "app_a"), null)
        val result = useCase.appearancesFor(person, candidates)
        assertEquals(listOf("app_a", "app_z"), result.map { it.id })
    }

    @Test
    fun aDanglingAppearanceId_isSkippedNotCrashed() {
        val c = candidate("app_1", 1, 1000)
        val person = Person("person_0", "Person 1", listOf("app_1", "app_missing"), null)
        val result = useCase.appearancesFor(person, listOf(c))
        assertEquals(listOf("app_1"), result.map { it.id })
    }

    @Test
    fun noAppearances_returnsEmptyList() {
        val person = Person("person_0", "Person 1", emptyList(), null)
        assertTrue(useCase.appearancesFor(person, emptyList()).isEmpty())
    }

    @Test
    fun noDuplicateAppearanceIdsAcrossMultiplePeople() {
        // Different people never share an appearance id in a valid clustering
        // result (the frozen clusterer partitions appearances). Verify the
        // join itself introduces no duplication when called per-person.
        val candidates = listOf(candidate("app_1", 1, 1000), candidate("app_2", 2, 2000))
        val p1 = Person("person_0", "Person 1", listOf("app_1"), null)
        val p2 = Person("person_1", "Person 2", listOf("app_2"), null)
        val ids = (useCase.appearancesFor(p1, candidates) + useCase.appearancesFor(p2, candidates))
            .map { it.id }
        assertEquals(ids.distinct(), ids)
    }
}
