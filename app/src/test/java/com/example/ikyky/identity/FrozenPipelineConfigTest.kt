package com.example.ikyky.identity

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 — guards on the FROZEN Phase 5I configuration.
 *
 * These are not behavioural tests; they exist so that a future change to a
 * frozen constant fails loudly and has to be justified against the Phase 5I
 * validation rather than slipping through as "tuning".
 */
class FrozenPipelineConfigTest {

    // =======================================================================
    // FROZEN CONSTANTS
    // =======================================================================

    @Test
    fun samplingIsEightFps() {
        assertEquals(8f, PipelineDefaults.FRAME_SAMPLE_FPS, 0f)
    }

    @Test
    fun decodeEdgeIs1080AndSeekIsClosestNotClosestSync() {
        assertEquals(1080, PipelineDefaults.FRAME_DECODE_MAX_EDGE_PX)
        // CLOSEST_SYNC collapses many timestamps onto the same keyframe.
        assertEquals("CLOSEST", PipelineDefaults.FRAME_SEEK_OPTION_NAME)
    }

    @Test
    fun identityMergeThresholdIsExactly0475() {
        // Chosen by a rule fixed in advance (zero MNL violations -> max mean
        // pair-F1 -> stability -> lower threshold) that never consults the
        // expected person count. Sits mid-plateau: [0.450, 0.575] gives
        // identical membership on all three samples.
        assertEquals(0.475f, PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD, 0f)
    }

    @Test
    fun appearanceGateIsTwoObservationsAndNoDurationFilter() {
        assertEquals(2, PipelineDefaults.MIN_APPEARANCE_OBSERVATIONS)
        assertEquals(0L, PipelineDefaults.MIN_APPEARANCE_DURATION_MS)
    }

    @Test
    fun trackerGateCropMarginsAreFrozen() {
        assertEquals(0.30f, PipelineDefaults.FACE_CROP_MARGIN_HORIZONTAL, 1e-6f)
        assertEquals(0.40f, PipelineDefaults.FACE_CROP_MARGIN_VERTICAL, 1e-6f)
        assertEquals(24, PipelineDefaults.MIN_RECOGNITION_CROP_PX)
    }

    @Test
    fun shotAwareTrackerGatesAreFrozen() {
        assertEquals(400L, PipelineDefaults.SHOT_AWARE_MAX_GAP_MS)
        assertEquals(0.20f, PipelineDefaults.SHOT_AWARE_MIN_IOU, 1e-6f)
        assertEquals(0.18f, PipelineDefaults.SHOT_AWARE_MAX_CENTER_DIST_FRACTION, 1e-6f)
        assertEquals(2.2f, PipelineDefaults.SHOT_AWARE_MAX_SIZE_RATIO, 1e-6f)
        assertEquals(0.50f, PipelineDefaults.SHOT_AWARE_APPEARANCE_MIN_COS, 1e-6f)
        assertEquals(5, PipelineDefaults.SHOT_AWARE_APPEARANCE_HISTORY)
    }

    @Test
    fun shotDetectorParametersAreFrozen() {
        assertEquals(64, PipelineDefaults.SHOT_THUMB_EDGE_PX)
        assertEquals(256, PipelineDefaults.SHOT_SHARP_EDGE_PX)
        assertEquals(32, PipelineDefaults.SHOT_HIST_BINS)
        assertEquals(2.5, PipelineDefaults.SHOT_Z_THRESHOLD, 1e-9)
        assertEquals(0.55, PipelineDefaults.SHOT_ABS_FLOOR, 1e-9)
        assertEquals(14, PipelineDefaults.SHOT_PAIR_MAX_GAP_FRAMES)
        assertEquals(0.45, PipelineDefaults.SHOT_BLUR_RATIO, 1e-9)
    }

    @Test
    fun mustNotLinkUsesObservationLevelIouOnly() {
        // Tolerance 0 == the SAME sampled frame. Never a time-range overlap.
        assertEquals(0, PipelineDefaults.MUST_NOT_LINK_FRAME_TOLERANCE)
        assertEquals(0.30f, PipelineDefaults.MUST_NOT_LINK_MAX_IOU, 1e-6f)
    }

    @Test
    fun modelContractMatchesTheFrozenSpecification() {
        val spec = ModelSpec.MOBILE_FACE_NET
        assertEquals(112, PipelineDefaults.EMBEDDING_INPUT_SIZE)
        assertEquals(192, PipelineDefaults.EMBEDDING_DIMENSION)
        assertEquals(112, spec.inputWidth)
        assertEquals(112, spec.inputHeight)
        assertEquals(3, spec.inputChannels)
        assertEquals(192, spec.outputDimension)
        assertEquals(ModelSpec.Normalization.MINUS_ONE_TO_ONE, spec.normalization)
    }

    // =======================================================================
    // CLUSTERING AT THE FROZEN THRESHOLD
    // =======================================================================

    private fun unit(theta: Double, id: String): AppearanceEmbedding {
        val v = FloatArray(8)
        v[0] = kotlin.math.cos(theta).toFloat()
        v[1] = kotlin.math.sin(theta).toFloat()
        return AppearanceEmbedding(
            appearanceId = id,
            trackletId = id.hashCode().toLong(),
            embedding = FaceEmbedding.l2Normalized(v),
            memberCount = 3,
            rejectedOutliers = 0,
            meanMemberSimilarity = 0.9f,
            bestQuality = 0.8f,
        )
    }

    private val threshold = PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD

    /** A must-not-link edge; the frame/timestamp/iou fields are evidence only. */
    private fun mnl(a: String, b: String) =
        MustNotLinkEdge(a, b, frameIndex = 0, timestampMs = 0L, iou = 0f)

    @Test
    fun similarAppearancesMergeAtTheFrozenThreshold() {
        // cosine(0, 0.2 rad) = 0.980, comfortably above 0.475
        val out = AgglomerativeIdentityClusterer().cluster(
            listOf(unit(0.0, "a"), unit(0.2, "b")), threshold, emptyList(),
        )
        assertEquals(1, out.clusters.size)
    }

    @Test
    fun dissimilarAppearancesStaySeparateAtTheFrozenThreshold() {
        // cosine(0, 1.5 rad) = 0.071, far below 0.475
        val out = AgglomerativeIdentityClusterer().cluster(
            listOf(unit(0.0, "a"), unit(1.5, "b")), threshold, emptyList(),
        )
        assertEquals(2, out.clusters.size)
    }

    @Test
    fun thresholdSitsInsideTheValidatedPlateau() {
        // Phase 5I: [0.450, 0.575] gave identical membership on all three
        // samples. Verify the frozen value is inside that band, so a small
        // numerical difference on device cannot flip the result.
        assertTrue(threshold >= 0.450f)
        assertTrue(threshold <= 0.575f)
    }

    // =======================================================================
    // MUST-NOT-LINK IS A HARD OVERRIDE
    // =======================================================================

    @Test
    fun mustNotLinkBlocksAMergeThatSimilarityWouldOtherwiseMake() {
        // near-identical vectors: similarity says "merge", MNL says "never"
        val out = AgglomerativeIdentityClusterer().cluster(
            listOf(unit(0.0, "a"), unit(0.01, "b")),
            threshold,
            listOf(mnl("a", "b")),
        )
        assertEquals("MNL must override similarity", 2, out.clusters.size)
        assertTrue(out.mergesBlockedByMustNotLink >= 1)
    }

    @Test
    fun mustNotLinkIsEnforcedTransitively() {
        // a~b~c all near-identical, but a and c may not share a cluster. No
        // resulting cluster may contain both.
        val out = AgglomerativeIdentityClusterer().cluster(
            listOf(unit(0.0, "a"), unit(0.01, "b"), unit(0.02, "c")),
            threshold,
            listOf(mnl("a", "c")),
        )
        for (c in out.clusters) {
            assertTrue(
                "no cluster may hold both a and c",
                !(c.appearanceIds.contains("a") && c.appearanceIds.contains("c")),
            )
        }
    }

    @Test
    fun zeroMustNotLinkViolationsAtEveryThresholdInTheSweptRange() {
        // Phase 5I reported zero violations at every swept threshold on every
        // sample. The clusterer must make that structurally impossible.
        val embs = listOf(unit(0.0, "a"), unit(0.01, "b"), unit(0.02, "c"), unit(0.03, "d"))
        val mnl = listOf(mnl("a", "b"), mnl("c", "d"))
        var t = 0.400f
        while (t <= 0.600f + 1e-6f) {
            val out = AgglomerativeIdentityClusterer().cluster(embs, t, mnl)
            for (c in out.clusters) {
                assertTrue(!(c.appearanceIds.contains("a") && c.appearanceIds.contains("b")))
                assertTrue(!(c.appearanceIds.contains("c") && c.appearanceIds.contains("d")))
            }
            t += 0.025f
        }
    }

    // =======================================================================
    // DETERMINISM
    // =======================================================================

    @Test
    fun clusteringIsDeterministicRegardlessOfInputOrder() {
        val embs = listOf(
            unit(0.00, "e"), unit(0.10, "c"), unit(1.60, "a"),
            unit(1.65, "d"), unit(0.05, "b"),
        )
        val expected = AgglomerativeIdentityClusterer()
            .cluster(embs, threshold, emptyList())
            .clusters.map { it.appearanceIds }

        for (perm in listOf(embs.reversed(), embs.sortedBy { it.appearanceId }, embs.shuffled())) {
            val got = AgglomerativeIdentityClusterer()
                .cluster(perm, threshold, emptyList())
                .clusters.map { it.appearanceIds }
            assertEquals("input order must not change the outcome", expected, got)
        }
    }

    @Test
    fun clusterMembershipIsAPartitionOfTheInput() {
        val embs = (0 until 8).map { unit(it * 0.25, "app_$it") }
        val out = AgglomerativeIdentityClusterer().cluster(embs, threshold, emptyList())
        val all = out.clusters.flatMap { it.appearanceIds }.sorted()
        assertEquals(embs.map { it.appearanceId }.sorted(), all)
    }
}
