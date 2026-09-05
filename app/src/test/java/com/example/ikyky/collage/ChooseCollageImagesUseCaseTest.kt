package com.example.ikyky.collage

import com.example.ikyky.features.collage.domain.usecase.DefaultChooseCollageImagesUseCase
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.model.RepresentativeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChooseCollageImagesUseCaseTest {

    private val useCase = DefaultChooseCollageImagesUseCase()

    private fun personWithImage(id: String, uri: String, quality: Float = 0.9f) = Person(
        id = id, label = "Person", appearanceIds = listOf("app_0"),
        representativeFrame = RepresentativeFrame(
            personId = id, sourceObservationId = "app_0", frameIndex = 0,
            timestampMs = 0, presentationCropKey = uri, qualityScore = quality,
        ),
    )

    private fun personWithoutImage(id: String) = Person(
        id = id, label = "Person", appearanceIds = listOf("app_0"), representativeFrame = null,
    )

    @Test
    fun onePersonOneImage_isAssigned() {
        val selection = useCase.choose(listOf(personWithImage("p0", "file:///a.jpg")))
        assertEquals(1, selection.assignments.size)
        assertEquals("file:///a.jpg", selection.assignments.first().imageUri)
        assertTrue(selection.skipped.isEmpty())
    }

    @Test
    fun missingRepresentativeFrame_isSkippedNotCrashed() {
        val selection = useCase.choose(listOf(personWithoutImage("p0")))
        assertTrue(selection.assignments.isEmpty())
        assertEquals(1, selection.skipped.size)
        assertEquals("p0", selection.skipped.first().personId)
    }

    @Test
    fun blankUri_isTreatedAsMissing() {
        val blank = personWithImage("p0", "").copy(
            representativeFrame = RepresentativeFrame("p0", "app_0", 0, 0, "   ", 0.9f),
        )
        val selection = useCase.choose(listOf(blank))
        assertTrue(selection.assignments.isEmpty())
        assertEquals(1, selection.skipped.size)
    }

    @Test
    fun mixOfPresentAndMissing_reportsBoth() {
        val people = listOf(
            personWithImage("p0", "file:///a.jpg"),
            personWithoutImage("p1"),
            personWithImage("p2", "file:///b.jpg"),
        )
        val selection = useCase.choose(people)
        assertEquals(2, selection.assignments.size)
        assertEquals(listOf("p1"), selection.skipped.map { it.personId })
    }

    @Test
    fun zeroPeople_returnsEmptySelection_doesNotCrash() {
        val selection = useCase.choose(emptyList())
        assertTrue(selection.assignments.isEmpty())
        assertTrue(selection.skipped.isEmpty())
    }

    @Test
    fun duplicateImageUriAcrossTwoPeople_bothStillAssignedIndependently() {
        // A duplicate URI (e.g. an upstream selection bug) must not make the
        // use case drop or merge people -- each person is resolved independently.
        val people = listOf(personWithImage("p0", "file:///same.jpg"), personWithImage("p1", "file:///same.jpg"))
        val selection = useCase.choose(people)
        assertEquals(2, selection.assignments.size)
        assertEquals(setOf("p0", "p1"), selection.assignments.map { it.personId }.toSet())
    }

    @Test
    fun qualityScoreIsCarriedThroughFromTheRepresentativeFrame() {
        val selection = useCase.choose(listOf(personWithImage("p0", "file:///a.jpg", quality = 0.42f)))
        assertEquals(0.42f, selection.assignments.first().qualityScore, 1e-6f)
    }
}
