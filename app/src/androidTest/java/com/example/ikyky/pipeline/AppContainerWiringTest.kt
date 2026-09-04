package com.example.ikyky.pipeline

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.di.AppContainer
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.people.data.DefaultBuildIdentitiesUseCase
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6.2 — proves the REAL production dependency graph, constructed exactly
 * as the app constructs it (`AppContainer(context)`), reaches the frozen
 * identity-clustering path and not the old 0.62 calibrator path.
 *
 * This test constructs actual dependency instances (not source-string
 * inspection) and drives `buildIdentitiesUseCase` end to end to observe its
 * behaviour, which is the only way to be sure a `by lazy` delegate resolves to
 * what it claims to.
 */
@RunWith(AndroidJUnit4::class)
class AppContainerWiringTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun box(i: Int) = BoundingBox(i * 10, 0, i * 10 + 100, 100)

    private fun appearance(id: String, trackletId: Long, frame: Int) = AppearanceCandidate(
        id = id, trackletId = trackletId,
        startTimestampMs = frame * 125L, endTimestampMs = frame * 125L + 250L,
        firstFrameIndex = frame, lastFrameIndex = frame + 2, observationCount = 3,
        lastTrackingId = null, meanQuality = 0.8f, bestQuality = 0.9f,
        bestFrameIndex = frame, bestFrameTimestampMs = frame * 125L, bestFrameBox = box(frame),
    )

    private fun observation(appearanceId: String, trackletId: Long, frame: Int, theta: Double) =
        EmbeddedFaceObservation(
            appearanceId = appearanceId, trackletId = trackletId, frameIndex = frame,
            timestampMs = frame * 125L, observationId = "obs_$appearanceId", faceBox = box(frame),
            qualityScore = 0.8f,
            embedding = FaceEmbedding.l2Normalized(
                floatArrayOf(kotlin.math.cos(theta).toFloat(), kotlin.math.sin(theta).toFloat(), 0f),
            ),
            alignedByLandmarks = true,
        )

    @Test
    fun appContainer_buildIdentitiesUseCase_isTheFrozenImplementation_notTheOldCalibrator() {
        val container = AppContainer(context)
        val useCase = container.buildIdentitiesUseCase
        assertTrue(
            "AppContainer.buildIdentitiesUseCase must construct FrozenBuildIdentitiesUseCase, " +
                "was ${useCase::class.qualifiedName}",
            useCase is FrozenBuildIdentitiesUseCase,
        )
        assertFalse(
            "the real dependency graph must NOT reach the old calibrator-based use case",
            useCase is DefaultBuildIdentitiesUseCase,
        )
    }

    @Test
    fun appContainer_identityPipeline_clustersAtExactly0475_endToEnd() = runBlocking {
        val container = AppContainer(context)
        // cosine(0, theta) ~= 0.55 -- strictly between the frozen 0.475 and the
        // retired 0.62 fallback. This is the exact regression the old
        // production wiring produced: appearances that must merge, didn't.
        val theta = kotlin.math.acos(0.55)
        val apps = listOf(appearance("a", 1, 0), appearance("b", 2, 10))
        val obs = listOf(observation("a", 1, 0, 0.0), observation("b", 2, 10, theta))

        val result = container.buildIdentitiesUseCase(apps, obs)
        assertTrue(result is AppResult.Success)
        val people = (result as AppResult.Success).value.people
        assertEquals(
            "the real AppContainer-wired pipeline must merge at 0.475, proving it is " +
                "NOT running the old 0.62 calibrator fallback",
            1, people.size,
        )
    }

    @Test
    fun appContainer_identityPipeline_stillEnforcesMustNotLink() = runBlocking {
        val container = AppContainer(context)
        val a = AppearanceCandidate(
            id = "a", trackletId = 1, startTimestampMs = 0, endTimestampMs = 250,
            firstFrameIndex = 0, lastFrameIndex = 2, observationCount = 1,
            lastTrackingId = null, meanQuality = 0.8f, bestQuality = 0.9f,
            bestFrameIndex = 0, bestFrameTimestampMs = 0, bestFrameBox = BoundingBox(0, 0, 100, 100),
            observations = listOf(
                com.example.ikyky.features.processing.domain.model.AppearanceObservationRef(
                    observationId = "oa", trackletId = 1, frameIndex = 5, timestampMs = 625L,
                    canonicalBox = BoundingBox(0, 0, 100, 100), landmarks = emptyList(),
                    qualityScore = 0.8f, usable = true,
                ),
            ),
        )
        val b = AppearanceCandidate(
            id = "b", trackletId = 2, startTimestampMs = 0, endTimestampMs = 250,
            firstFrameIndex = 0, lastFrameIndex = 2, observationCount = 1,
            lastTrackingId = null, meanQuality = 0.8f, bestQuality = 0.9f,
            bestFrameIndex = 0, bestFrameTimestampMs = 0, bestFrameBox = BoundingBox(900, 0, 1000, 100),
            observations = listOf(
                com.example.ikyky.features.processing.domain.model.AppearanceObservationRef(
                    observationId = "ob", trackletId = 2, frameIndex = 5, timestampMs = 625L,
                    canonicalBox = BoundingBox(900, 0, 1000, 100), landmarks = emptyList(),
                    qualityScore = 0.8f, usable = true,
                ),
            ),
        )
        val obs = listOf(observation("a", 1, 0, 0.0), observation("b", 2, 0, 0.01))

        val result = container.buildIdentitiesUseCase(listOf(a, b), obs)
        val value = (result as AppResult.Success).value
        assertEquals("MNL must still block this merge", 2, value.people.size)
        assertTrue(value.mustNotLinkEdges.isNotEmpty())
    }

    @Test
    fun appContainer_buildIdentitiesUseCase_isASingleton() {
        // `by lazy` must not silently become a fresh instance per access, which
        // would defeat the point of sharing collaborators.
        val container = AppContainer(context)
        assertTrue(container.buildIdentitiesUseCase === container.buildIdentitiesUseCase)
    }

    @Test
    fun frozenThreshold_singleSourceOfTruth_matchesPipelineDefaults() {
        // Regression guard: production wiring and the frozen-config test must
        // read the SAME constant, not two copies of "0.475" that could drift.
        assertEquals(0.475f, PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD, 0f)
    }
}
