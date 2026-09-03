package com.example.ikyky.identity

import com.example.ikyky.features.people.domain.usecase.DenseTemporalChangePointAnalyzer
import com.example.ikyky.features.people.domain.usecase.SuspiciousAppearanceSelector
import com.example.ikyky.features.people.domain.usecase.WhipPanGapAnalyzer
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.5 — dense temporal-split refinement. All pure-JVM.
 * Covers brief step 20 scenarios 1–12.
 */
class DenseSplitTest {

    private val thetaA = 0.0
    private val thetaB = 1.3   // ~74° — clearly different identity

    private fun dense(
        appearanceId: String,
        thetas: List<Double>,
        quals: List<Float> = List(thetas.size) { 0.9f },
        startMs: Long = 0L,
        stepMs: Long = 500L,
    ): List<EmbeddedFaceObservation> = thetas.mapIndexed { i, th ->
        TestEmbeddings.embedded(
            appearanceId = appearanceId,
            obsId = "d$i",
            tsMs = startMs + i * stepMs,
            embedding = TestEmbeddings.vec(th, jitter = 0.01f, seed = i),
            q = quals[i],
        )
    }

    // 2. clear two-person temporal transition → one split
    @Test
    fun clearTwoPersonTransition_splitsOnce() {
        val a = DenseTemporalChangePointAnalyzer().analyze(
            dense("x", List(6) { thetaA } + List(6) { thetaB }),
        )
        assertEquals(1, a.acceptedCutsMs.size)
        assertTrue("cut near the A→B boundary (t≈3000ms)", a.acceptedCutsMs.single() in 2500L..3500L)
        // the recursion picks the strongest accepted candidate
        val best = a.candidates.filter { it.accepted }.maxByOrNull { it.drop }!!
        assertTrue("both windows internally coherent", best.leftCompactness >= 0.55f && best.rightCompactness >= 0.55f)
        assertTrue("cross similarity substantially lower than both sides", best.drop >= 0.20f)
        assertTrue("at the true boundary the cross drops hard", best.crossSimilarity < 0.4f)
    }

    // 3. clear three-segment transition → two splits (multiple change points)
    @Test
    fun threeSegments_splitTwice() {
        val thetaC = 2.6
        val a = DenseTemporalChangePointAnalyzer().analyze(
            dense("x", List(5) { thetaA } + List(5) { thetaB } + List(5) { thetaC }),
        )
        assertEquals("two genuine boundaries", 2, a.acceptedCutsMs.size)
        assertTrue(a.acceptedCutsMs[0] < a.acceptedCutsMs[1])
    }

    // 3b. a long tracklet where NO single 2-way cut has both sides internally
    // tight (4 identities back-to-back) — recursion must still recover 3 cuts by
    // isolating one coherent segment at a time.
    @Test
    fun fourIdentitySegments_recursionRecoversThreeCuts() {
        val thetaC = 2.4
        val thetaD = -1.2
        val a = DenseTemporalChangePointAnalyzer().analyze(
            dense("x", List(4) { thetaA } + List(4) { thetaB } + List(4) { thetaC } + List(4) { thetaD }),
        )
        assertEquals("three genuine boundaries", 3, a.acceptedCutsMs.size)
        assertEquals(a.acceptedCutsMs.sorted(), a.acceptedCutsMs)
    }

    // 4. gradual pose change → no split
    @Test
    fun gradualDrift_doesNotSplit() {
        // slow rotation: total ~0.44 rad over 12 samples. Neighbour cosine ~0.999,
        // any window-vs-window cross similarity stays close to each side's own
        // compactness → margins never reach DENSE_SPLIT_MIN_DROP.
        val thetas = (0 until 12).map { it * 0.04 }
        val a = DenseTemporalChangePointAnalyzer().analyze(dense("x", thetas))
        assertTrue("gradual drift must not create a boundary", a.acceptedCutsMs.isEmpty())
        assertTrue(a.candidates.none { it.accepted })
    }

    // 5. one bad observation → no split
    @Test
    fun oneBadObservation_doesNotSplit() {
        val thetas = List(11) { thetaA }.toMutableList()
        thetas[5] = thetaB // a single off-identity frame (bad crop)
        val quals = List(11) { 0.9f }.toMutableList()
        quals[5] = 0.2f    // …and it's low quality
        val a = DenseTemporalChangePointAnalyzer().analyze(dense("x", thetas, quals))
        assertTrue("a single low-quality outlier must not split", a.acceptedCutsMs.isEmpty())
    }

    // 6. sustained identity change → splits
    @Test
    fun sustainedIdentityChange_splits() {
        val a = DenseTemporalChangePointAnalyzer().analyze(
            dense("x", List(7) { thetaA } + List(7) { thetaB }),
        )
        assertEquals(1, a.acceptedCutsMs.size)
    }

    // 7. multiple change points already covered by threeSegments; also assert depth-bounding
    @Test
    fun manyAlternations_areBounded() {
        val thetas = (0 until 20).map { if ((it / 3) % 2 == 0) thetaA else thetaB }
        val a = DenseTemporalChangePointAnalyzer(maxDepth = 3).analyze(dense("x", thetas))
        assertTrue("recursion stays bounded", a.acceptedCutsMs.size <= 7)
    }

    // 8. short appearance (below dense sample floor) → analyzer returns nothing
    @Test
    fun tooFewSamples_noCandidates() {
        val a = DenseTemporalChangePointAnalyzer(minSideSamples = 3).analyze(
            dense("x", listOf(thetaA, thetaB, thetaA, thetaB)),
        )
        assertTrue(a.candidates.isEmpty())
        assertTrue(a.acceptedCutsMs.isEmpty())
    }

    // 9. whip-pan / gap closes appearance
    @Test
    fun whipPanGap_betweenBlurredFrames_isACut() {
        val obs = listOf(
            TestEmbeddings.ref("o0", 0, q = 0.9f),
            TestEmbeddings.ref("o1", 250, q = 0.9f),
            TestEmbeddings.ref("o2", 500, q = 0.20f),   // going blurry
            // 900ms gap of nothing (whip-pan)
            TestEmbeddings.ref("o3", 1400, q = 0.20f),  // still blurry coming out
            TestEmbeddings.ref("o4", 1650, q = 0.9f),
        )
        val cuts = WhipPanGapAnalyzer(gapMs = 700, maxQuality = 0.35f).findWhipPanCuts(obs)
        assertEquals(1, cuts.size)
        assertEquals(1400L, cuts.single().afterTimestampMs)
    }

    @Test
    fun gapBetweenSharpFrames_isNotAWhipPan() {
        val obs = listOf(
            TestEmbeddings.ref("o0", 0, q = 0.9f),
            TestEmbeddings.ref("o1", 1200, q = 0.9f), // long gap but both sharp
        )
        assertTrue(WhipPanGapAnalyzer(gapMs = 700).findWhipPanCuts(obs).isEmpty())
    }

    // 10. deterministic
    @Test
    fun deterministic() {
        val d = dense("x", List(6) { thetaA } + List(6) { thetaB })
        val a = DenseTemporalChangePointAnalyzer().analyze(d)
        val b = DenseTemporalChangePointAnalyzer().analyze(d.shuffled())
        assertEquals(a.acceptedCutsMs, b.acceptedCutsMs)
    }

    // 1 & 12. suspicion selection: dense analysis only when warranted
    @Test
    fun suspicionSelector_flagsLongOrIncoherent_notShortCoherent() {
        val sel = SuspiciousAppearanceSelector()

        val shortCoherent = TestEmbeddings.candidate(
            "s", 1L, (0..3).map { TestEmbeddings.ref("s$it", it * 250L) },
        )
        val shortEmb = dense("s", List(4) { thetaA }, startMs = 0, stepMs = 250)

        val longCoherent = TestEmbeddings.candidate(
            "l", 2L, (0..40).map { TestEmbeddings.ref("l$it", it * 250L) },
        )
        val longEmb = dense("l", List(5) { thetaA }, startMs = 0, stepMs = 2500)

        val shortIncoherent = TestEmbeddings.candidate(
            "i", 3L, (0..6).map { TestEmbeddings.ref("i$it", it * 250L) },
        )
        val incEmb = dense("i", listOf(thetaA, thetaA, thetaB, thetaB, thetaA), startMs = 0, stepMs = 250)

        val reports = sel.evaluate(
            listOf(shortCoherent, longCoherent, shortIncoherent),
            mapOf("s" to shortEmb, "l" to longEmb, "i" to incEmb),
        )
        assertFalse("short + coherent → not suspicious", reports.first { it.appearanceId == "s" }.suspicious)
        assertTrue("long (>4s) → suspicious (analysis trigger, not a split)", reports.first { it.appearanceId == "l" }.suspicious)
        assertTrue("incoherent embeddings → suspicious", reports.first { it.appearanceId == "i" }.suspicious)
    }

    // 11. normal appearances still use only the normal embedding budget — asserted
    // structurally: the selector reports non-suspicious, so the refiner never
    // calls denseEmbed for them (covered end-to-end in the instrumentation test).
    @Test
    fun normalAppearance_isNotFlagged_soDensePathIsSkipped() {
        val ap = TestEmbeddings.candidate("n", 1L, (0..12).map { TestEmbeddings.ref("n$it", it * 250L) })
        val emb = dense("n", List(5) { thetaA }, startMs = 0, stepMs = 750)
        val report = SuspiciousAppearanceSelector().evaluate(listOf(ap), mapOf("n" to emb)).single()
        assertFalse(report.suspicious)
        assertTrue(report.reasons.isEmpty())
    }
}
