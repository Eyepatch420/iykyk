package com.example.ikyky.tracking

import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.ml.tracking.ShotAwareFaceTracker
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.FaceEmbedding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6D — the frozen Config K / Option A tracker.
 *
 * The two behaviours that carry the whole Phase 5F result are:
 *  1. a confirmed transition is an ABSOLUTE barrier, and
 *  2. the association decision happens BEFORE the appearance state updates.
 *
 * Both have dedicated tests below; both were root causes of real defects.
 */
class ShotAwareFaceTrackerTest {

    private val diagonal = 2203f // ~1080x1920

    private fun obs(
        id: String,
        frame: Int,
        box: BoundingBox,
        timestampMs: Long = frame * 125L, // 8 FPS
    ) = FaceObservation(
        id = id,
        frameIndex = frame,
        timestampMs = timestampMs,
        face = DetectedFace(boundingBox = box),
    )

    /** A box of fixed size at a given centre. */
    private fun boxAt(cx: Int, cy: Int, size: Int = 200) =
        BoundingBox(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

    private fun emb(vararg v: Float) = FaceEmbedding.l2Normalized(v)

    private val noBarriers: (Int, Int) -> Boolean = { _, _ -> false }

    // =======================================================================
    // BASIC ASSOCIATION
    // =======================================================================

    @Test
    fun aStationaryFaceAcrossFrames_becomesOneTracklet() {
        val obs = (0..5).map { obs("o$it", it, boxAt(500, 900)) }
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        assertEquals(1, out.size)
        assertEquals(6, out[0].observations.size)
    }

    @Test
    fun twoSimultaneousFaces_becomeTwoTracklets() {
        val obs = (0..4).flatMap {
            listOf(obs("a$it", it, boxAt(300, 900)), obs("b$it", it, boxAt(800, 900)))
        }
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        assertEquals(2, out.size)
        assertTrue(out.all { it.observations.size == 5 })
    }

    @Test
    fun aTemporalGapLongerThanMaxGap_closesTheTracklet() {
        // frames 0,1 then a jump to frame 10 => 1125 ms gap > 400 ms
        val obs = listOf(
            obs("a", 0, boxAt(500, 900)),
            obs("b", 1, boxAt(500, 900)),
            obs("c", 10, boxAt(500, 900)),
        )
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        assertEquals(2, out.size)
    }

    // =======================================================================
    // THE ABSOLUTE SHOT BARRIER
    // =======================================================================

    @Test
    fun aTransitionBetweenFrames_isAnAbsoluteBarrier_evenForAPerfectlyStationaryFace() {
        // Identical box, adjacent frames, zero time gap issue: geometry says
        // "obviously the same face". The barrier must still split it — this is
        // exactly the whip-pan case where a DIFFERENT person occupies the same
        // screen position after the cut.
        val obs = (0..5).map { obs("o$it", it, boxAt(500, 900)) }
        val barrierBetween2And3: (Int, Int) -> Boolean = { a, b ->
            val lo = minOf(a, b); val hi = maxOf(a, b)
            lo < 3 && hi >= 3
        }
        val out = ShotAwareFaceTracker().track(obs, diagonal, barrierBetween2And3)
        assertEquals("a barrier must split the track", 2, out.size)
        assertEquals(3, out[0].observations.size)
        assertEquals(3, out[1].observations.size)
    }

    @Test
    fun theBarrierIsNotOverridableByAStrongAppearanceMatch() {
        // Same face, IDENTICAL embeddings — the strongest possible appearance
        // evidence. The barrier still wins, because it is absolute.
        val obs = (0..3).map { obs("o$it", it, boxAt(500, 900)) }
        val v = emb(1f, 0f, 0f)
        val gates = obs.associate { it.id to v }
        val barrier: (Int, Int) -> Boolean = { a, b ->
            minOf(a, b) < 2 && maxOf(a, b) >= 2
        }
        val out = ShotAwareFaceTracker().track(obs, diagonal, barrier, gates)
        assertEquals(2, out.size)
    }

    @Test
    fun withBarriersDisabled_theSameInputStaysOneTracklet() {
        // Control for the two tests above: without the barrier, geometry merges.
        val obs = (0..5).map { obs("o$it", it, boxAt(500, 900)) }
        val out = ShotAwareFaceTracker(useShotBoundaries = false).track(
            obs, diagonal, { a, b -> minOf(a, b) < 3 && maxOf(a, b) >= 3 },
        )
        assertEquals(1, out.size)
    }

    // =======================================================================
    // THE APPEARANCE GATE + ANTI-CONTAMINATION ORDERING
    // =======================================================================

    @Test
    fun anIncompatibleEmbedding_isHardRejected_evenWithPerfectGeometry() {
        // Frames 0-2 are person A; frame 3 is a different person at the same
        // screen position (cosine 0 to A, below the 0.50 gate).
        val obs = (0..3).map { obs("o$it", it, boxAt(500, 900)) }
        val a = emb(1f, 0f, 0f)
        val b = emb(0f, 1f, 0f)
        val gates = mapOf("o0" to a, "o1" to a, "o2" to a, "o3" to b)
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers, gates)
        assertEquals("incompatible appearance must open a new track", 2, out.size)
        assertEquals(3, out[0].observations.size)
        assertEquals(1, out[1].observations.size)
    }

    @Test
    fun aCompatibleEmbedding_isAccepted() {
        val obs = (0..3).map { obs("o$it", it, boxAt(500, 900)) }
        val a = emb(1f, 0f, 0f)
        val nearA = emb(0.95f, 0.31f, 0f) // cosine ~0.95, well above 0.50
        val gates = mapOf("o0" to a, "o1" to a, "o2" to a, "o3" to nearA)
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers, gates)
        assertEquals(1, out.size)
        assertEquals(4, out[0].observations.size)
    }

    @Test
    fun aRejectedObservation_neverMovesTheTracksRepresentation() {
        // THE ANTI-CONTAMINATION RULE. Frames 0-2 are person A. Frame 3 is
        // person B (rejected). Frame 4 is person A again and MUST still match
        // the original track — which only works if B never entered A's history.
        //
        // If the tracker updated state before deciding, A's representation would
        // have drifted toward B and frame 4 could fail the gate.
        val obs = listOf(
            obs("o0", 0, boxAt(500, 900)),
            obs("o1", 1, boxAt(500, 900)),
            obs("o2", 2, boxAt(500, 900)),
            obs("o3", 3, boxAt(500, 900)),
            obs("o4", 4, boxAt(500, 900)),
        )
        val a = emb(1f, 0f, 0f)
        val b = emb(0f, 1f, 0f)
        val gates = mapOf("o0" to a, "o1" to a, "o2" to a, "o3" to b, "o4" to a)
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers, gates)

        val trackWithA = out.maxByOrNull { it.observations.size }!!
        assertEquals(
            "person A's track must reclaim frame 4 after rejecting the intruder",
            listOf("o0", "o1", "o2", "o4"),
            trackWithA.observations.map { it.id },
        )
    }

    @Test
    fun missingGateEmbeddings_makeTheGateAbstain_ratherThanReject() {
        // A crop that collapsed against a frame edge yields no embedding. That
        // must not be read as "incompatible".
        val obs = (0..3).map { obs("o$it", it, boxAt(500, 900)) }
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers, emptyMap())
        assertEquals(1, out.size)
        assertEquals(4, out[0].observations.size)
    }

    // =======================================================================
    // GEOMETRY GATES
    // =======================================================================

    @Test
    fun aFaceJumpingAcrossTheFrame_isNotAssociated() {
        val obs = listOf(
            obs("a", 0, boxAt(150, 300)),
            obs("b", 1, boxAt(950, 1700)), // far away, no IoU
        )
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        assertEquals(2, out.size)
    }

    @Test
    fun aSuddenLargeSizeChange_isNotAssociated() {
        // area ratio well beyond 2.2
        val obs = listOf(
            obs("a", 0, boxAt(500, 900, size = 120)),
            obs("b", 1, boxAt(500, 900, size = 400)),
        )
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        assertEquals(2, out.size)
    }

    // =======================================================================
    // DETERMINISM
    // =======================================================================

    @Test
    fun outputIsDeterministic_acrossRepeatedRuns() {
        val obs = (0..8).flatMap {
            listOf(
                obs("a$it", it, boxAt(300 + it * 5, 900)),
                obs("b$it", it, boxAt(800 - it * 5, 900)),
            )
        }
        val first = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        repeat(5) {
            val again = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
            assertEquals(
                first.map { t -> t.id to t.observations.map { o -> o.id } },
                again.map { t -> t.id to t.observations.map { o -> o.id } },
            )
        }
    }

    @Test
    fun outputIsOrderedByStartTimeThenId() {
        val obs = (0..4).flatMap {
            listOf(obs("a$it", it, boxAt(300, 900)), obs("b$it", it, boxAt(800, 900)))
        }
        val out = ShotAwareFaceTracker().track(obs, diagonal, noBarriers)
        val starts = out.map { it.startTimestampMs }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun emptyInputProducesNoTracklets() {
        assertTrue(ShotAwareFaceTracker().track(emptyList(), diagonal, noBarriers).isEmpty())
    }
}
