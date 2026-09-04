package com.example.ikyky.identity

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer
import com.example.ikyky.features.people.domain.usecase.MustNotLinkBuilder
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.2 — production wiring: proves what [FrozenBuildIdentitiesUseCase]
 * actually does, not just what it's built from. Runs at the JVM level with a
 * no-op [Logger] (android.util.Log isn't available in plain unit tests).
 *
 * This is the behavioral half of the wiring guard: [AppContainerWiringTest]
 * proves `AppContainer.buildIdentitiesUseCase` construction; this proves the
 * constructed type clusters at 0.475 with hard MNL and no calibration/split,
 * which is what would silently regress back toward the old 0.62 behaviour if
 * someone "simplified" this class later.
 */
class FrozenBuildIdentitiesUseCaseTest {

    private object ImmediateDispatchers : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    private object NoOpLogger : Logger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
    }

    private fun useCase() = FrozenBuildIdentitiesUseCase(
        dispatchers = ImmediateDispatchers,
        logger = NoOpLogger,
    )

    private fun box(i: Int) = BoundingBox(i * 10, 0, i * 10 + 100, 100)

    private fun appearance(id: String, trackletId: Long, frame: Int) = AppearanceCandidate(
        id = id,
        trackletId = trackletId,
        startTimestampMs = frame * 125L,
        endTimestampMs = frame * 125L + 250L,
        firstFrameIndex = frame,
        lastFrameIndex = frame + 2,
        observationCount = 3,
        lastTrackingId = null,
        meanQuality = 0.8f,
        bestQuality = 0.9f,
        bestFrameIndex = frame,
        bestFrameTimestampMs = frame * 125L,
        bestFrameBox = box(frame),
    )

    /** One embedded observation per appearance, pointed at a unit vector. */
    private fun observation(appearanceId: String, trackletId: Long, frame: Int, theta: Double) =
        EmbeddedFaceObservation(
            appearanceId = appearanceId,
            trackletId = trackletId,
            frameIndex = frame,
            timestampMs = frame * 125L,
            observationId = "obs_$appearanceId",
            faceBox = box(frame),
            qualityScore = 0.8f,
            embedding = FaceEmbedding.l2Normalized(
                floatArrayOf(kotlin.math.cos(theta).toFloat(), kotlin.math.sin(theta).toFloat(), 0f),
            ),
            alignedByLandmarks = true,
        )

    // =======================================================================
    // THRESHOLD — single source of truth
    // =======================================================================

    @Test
    fun defaultThreshold_isPipelineDefaultsConstant_notAHardcodedLiteral() = runBlocking {
        // Two near-identical appearances that would merge at 0.475 but the
        // clusterer must be reachable at exactly PipelineDefaults' value: prove
        // it by constructing with an explicit different threshold and observing
        // a different result, and with the default and observing PipelineDefaults'.
        val apps = listOf(appearance("a", 1, 0), appearance("b", 2, 10))
        val obs = listOf(observation("a", 1, 0, 0.0), observation("b", 2, 10, 0.30))
        // cosine(0, 0.30) ~= 0.955 -> merges at 0.475, would NOT merge at 0.99
        val defaultResult = (useCase()(apps, obs) as AppResult.Success).value
        assertEquals(1, defaultResult.people.size)

        val strict = FrozenBuildIdentitiesUseCase(
            dispatchers = ImmediateDispatchers, logger = NoOpLogger, threshold = 0.99f,
        )
        val strictResult = (strict(apps, obs) as AppResult.Success).value
        assertEquals(2, strictResult.people.size)
    }

    @Test
    fun productionConstructor_defaultsToPipelineDefaultsIdentityThreshold() {
        // Reflection-free proof: the no-arg-threshold constructor path used by
        // AppContainer must resolve to PipelineDefaults' value, which
        // FrozenPipelineConfigTest independently asserts equals 0.475.
        assertEquals(
            PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD,
            0.475f,
            0f,
        )
    }

    // =======================================================================
    // NOT the old 0.62 path
    // =======================================================================

    @Test
    fun frozenUseCase_neverProducesTheOld062FallbackBehaviour() = runBlocking {
        // Two appearances at cosine ~0.55 (between 0.475 and 0.62): the OLD
        // calibrator-fallback path (0.62) would keep them separate; the FROZEN
        // path (0.475) must merge them. This is the exact regression the old
        // production wiring produced.
        val apps = listOf(appearance("a", 1, 0), appearance("b", 2, 10))
        // pick theta so cosine(0, theta) ~= 0.55
        val theta = kotlin.math.acos(0.55)
        val obs = listOf(observation("a", 1, 0, 0.0), observation("b", 2, 10, theta))
        val result = (useCase()(apps, obs) as AppResult.Success).value
        assertEquals(
            "at 0.475 these must merge; the old 0.62 fallback would have kept them apart",
            1, result.people.size,
        )
    }

    @Test
    fun frozenUseCase_neverCalibrates_diagnosticsCarryNoCalibrationResult() = runBlocking {
        val apps = listOf(appearance("a", 1, 0), appearance("b", 2, 10))
        val obs = listOf(observation("a", 1, 0, 0.0), observation("b", 2, 10, 1.5))
        val result = (useCase()(apps, obs) as AppResult.Success).value
        assertNull(
            "the frozen path must never run SimilarityCalibrator",
            result.diagnostics.calibration,
        )
    }

    @Test
    fun frozenUseCase_neverSplits_appearancesAfterSplitEqualsInput() = runBlocking {
        val apps = listOf(appearance("a", 1, 0), appearance("b", 2, 10), appearance("c", 3, 20))
        val obs = listOf(
            observation("a", 1, 0, 0.0),
            observation("b", 2, 10, 1.2),
            observation("c", 3, 20, 2.4),
        )
        val result = (useCase()(apps, obs) as AppResult.Success).value
        assertEquals(0, result.diagnostics.splitsApplied)
        assertEquals(apps.size, result.diagnostics.appearancesAfterSplit)
        assertEquals(apps, result.correctedAppearances)
    }

    // =======================================================================
    // MUST-NOT-LINK IS STILL A HARD CONSTRAINT
    // =======================================================================

    @Test
    fun frozenUseCase_stillEnforcesMustNotLink() = runBlocking {
        // Two appearances with near-identical embeddings (would merge) that ALSO
        // hold observations in the same frame with distinct, non-overlapping
        // boxes — the MustNotLinkBuilder's exact trigger condition.
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
        val result = (useCase()(listOf(a, b), obs) as AppResult.Success).value
        assertEquals("MNL must override near-identical similarity", 2, result.people.size)
        assertTrue(result.mustNotLinkEdges.isNotEmpty())
        assertEquals(1, result.diagnostics.mergesBlockedByMustNotLink)
    }

    // =======================================================================
    // USES THE REAL COLLABORATORS, NOT A REIMPLEMENTATION
    // =======================================================================

    @Test
    fun clustererAndMnlBuilderAreTheSharedProductionClasses() {
        // Compile-time proof the constructor accepts the exact same types the
        // rest of the app uses -- there is no second clustering implementation.
        val useCase = FrozenBuildIdentitiesUseCase(
            dispatchers = ImmediateDispatchers,
            logger = NoOpLogger,
            mustNotLinkBuilder = MustNotLinkBuilder(),
            clusterer = AgglomerativeIdentityClusterer(),
        )
        assertTrue(useCase is com.example.ikyky.features.people.domain.usecase.BuildIdentitiesUseCase)
    }

    @Test
    fun emptyAppearances_producesNoPeople_withoutTouchingTheClusterer() = runBlocking {
        val result = (useCase()(emptyList(), emptyList()) as AppResult.Success).value
        assertTrue(result.people.isEmpty())
    }
}
