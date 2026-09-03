package com.example.ikyky.pipeline

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.logging.AndroidLogger
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.MediaMetadataVideoReader
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.ml.preprocessing.DefaultFacePreprocessor
import com.example.ikyky.core.ml.tracking.GreedyFaceTracker
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.features.people.data.DefaultBuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.diagnostic.IdentityCalibrationDiagnostic
import com.example.ikyky.features.people.domain.diagnostic.IdentityThresholdSweep
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PHASE 4.6 — DIAGNOSTIC ONLY.
 *
 * Runs the real Phase 2 → 3 → 4-split pipeline on the bundled Samples 1, 2, 3,
 * then feeds the EXACT corrected appearance embeddings + production must-not-link
 * edges into a threshold sweep of the EXISTING agglomerative clusterer.
 *
 * Production behaviour is NOT changed. The production `people` result (at the
 * calibrated 0.62 fallback) is logged alongside the sweep for comparison.
 *
 * Sample 1's "5 people / 20 appearances" is used ONLY as an external validation
 * signal in the logs, never as an input.
 *
 * logcat tag: "Phase46".
 */
@RunWith(AndroidJUnit4::class)
class IdentityCalibrationSweepTest {

    private val TAG = "Phase46"
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private data class Prepared(
        val label: String,
        val productionPeople: Int,
        val correctedAppearances: Int,
        val embeddings: List<AppearanceEmbedding>,
        val mnl: List<MustNotLinkEdge>,
        val calibratedThreshold: Float,
        val calibrationFallback: Boolean,
    )

    private fun copyAsset(name: String): File {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instr.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    private fun prepare(asset: String): Prepared = runBlocking {
        val file = copyAsset(asset)
        val uri = android.net.Uri.fromFile(file).toString()
        val dispatchers = StandardDispatcherProvider()
        val logger = AndroidLogger()

        val detector = MlKitFaceDetector()
        val repo = InMemoryProcessingResultRepository()
        val metaReader = MediaMetadataVideoReader(context, dispatchers)
        val extractor = MediaMetadataFrameExtractor(context, dispatchers)
        val phase2 = DefaultProcessVideoUseCase(
            metadataReader = metaReader, frameExtractor = extractor, faceDetector = detector,
            faceTracker = GreedyFaceTracker(), resultRepository = repo, dispatchers = dispatchers, logger = logger,
        )
        assertTrue(phase2("s", uri) {} is AppResult.Success)
        detector.close()
        val appearances = repo.getCandidates("s")
        val meta = (metaReader.read(uri) as AppResult.Success).value

        val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
        val phase3 = DefaultGenerateAppearanceEmbeddingsUseCase(
            frameExtractor = extractor, preprocessor = DefaultFacePreprocessor(),
            embedder = embedder, modelLoader = loader, dispatchers = dispatchers, logger = logger,
        )
        val p3 = (phase3(uri, meta, appearances) { _, _ -> } as AppResult.Success).value

        val phase4 = DefaultBuildIdentitiesUseCase(dispatchers = dispatchers, logger = logger)
        val p4 = (phase4(appearances, p3.perObservation, uri, meta, phase3) as AppResult.Success).value
        embedder.close()

        Prepared(
            label = asset.removeSuffix(".mp4"),
            productionPeople = p4.people.size,
            correctedAppearances = p4.correctedAppearances.size,
            embeddings = p4.appearanceEmbeddings,
            mnl = p4.mustNotLinkEdges,
            calibratedThreshold = p4.diagnostics.calibration?.threshold ?: -1f,
            calibrationFallback = p4.diagnostics.calibration?.fallbackUsed ?: true,
        )
    }

    private fun dumpSweep(rep: IdentityCalibrationDiagnostic.SampleReport, prep: Prepared, expectedPeople: Int?) {
        val sw = rep.sweep
        Log.i(TAG, "════════ ${sw.sampleLabel} — threshold sweep ════════")
        Log.i(TAG, "appearanceEmbeddings=${sw.appearanceEmbeddingCount}  mustNotLinkEdges=${sw.mustNotLinkEdgeCount}")
        Log.i(TAG, "PRODUCTION: calibrated threshold ${"%.3f".format(prep.calibratedThreshold)}" +
            (if (prep.calibrationFallback) " (FALLBACK)" else "") +
            " → ${prep.productionPeople} people / ${prep.correctedAppearances} appearances")

        // D/E/F — the matrix
        Log.i(TAG, "threshold | people | appearances | size-signature | singletons | merges | meanCompact | minCompact | mnlViolations | mnlBlocked")
        sw.rows.forEach { r ->
            Log.i(TAG, "${"%.3f".format(r.threshold)} | ${r.personCount} | ${r.appearanceCount} | " +
                "${r.sizeSignature.joinToString(",")} | ${r.singletons} | ${r.merges} | " +
                "${"%.2f".format(r.meanClusterCompactness)} | ${"%.2f".format(r.minClusterCompactness)} | " +
                "${r.mustNotLinkViolations} | ${r.mergesBlockedByMustNotLink}")
        }

        // stable regions + transitions
        Log.i(TAG, "-- transitions (threshold: fromPeople→toPeople) --")
        sw.transitions().forEach { (t, from, to) -> Log.i(TAG, "  ${"%.3f".format(t)}: $from→$to") }
        if (expectedPeople != null) {
            val lo = sw.lowestThresholdForPeople(expectedPeople)
            val hi = sw.highestThresholdForPeople(expectedPeople)
            val range = sw.stableRangeForPeople(expectedPeople)
            Log.i(TAG, "-- VALIDATION SIGNAL (not an input): threshold region producing $expectedPeople people --")
            Log.i(TAG, "  lowest=${lo?.let { "%.3f".format(it) } ?: "none"}  highest=${hi?.let { "%.3f".format(it) } ?: "none"}  " +
                "stable-range=${range?.let { "${"%.3f".format(it.start)}..${"%.3f".format(it.endInclusive)}" } ?: "none"}")
        }

        // I/J — exact membership at every threshold where personCount changed, plus the production threshold
        val interesting = (sw.transitions().map { it.first } +
            sw.rows.minByOrNull { kotlin.math.abs(it.threshold - prep.calibratedThreshold) }!!.threshold)
            .distinct().sorted()
        interesting.forEach { thr ->
            val row = sw.rows.first { it.threshold == thr }
            Log.i(TAG, "-- membership @ threshold ${"%.3f".format(thr)} (${row.personCount} people) --")
            row.clusters.forEachIndexed { i, c ->
                Log.i(TAG, "  Cluster ${i + 1} [n=${c.size}] mean=${"%.2f".format(c.meanPairwise)} " +
                    "min=${"%.2f".format(c.minPairwise)} weakestCut=${"%.2f".format(c.weakestInternalLinkage)}: ${c.appearanceIds}")
            }
        }

        // G/H — distribution + calibrator's own histogram/valley
        val s = rep.stats
        Log.i(TAG, "-- pairwise cosine distribution (${s.pairCount} pairs) --")
        Log.i(TAG, "  min=${"%.2f".format(s.min)} p10=${"%.2f".format(s.p10)} p25=${"%.2f".format(s.p25)} " +
            "median=${"%.2f".format(s.median)} mean=${"%.2f".format(s.mean)} p75=${"%.2f".format(s.p75)} " +
            "p90=${"%.2f".format(s.p90)} max=${"%.2f".format(s.max)}")
        Log.i(TAG, "  calibrator: valley=${s.valleyCosine?.let { "%.2f".format(it) } ?: "none"} " +
            "depth=${s.valleyDepth?.let { "%.2f".format(it) } ?: "-"} " +
            "low=${s.lowPeakCosine?.let { "%.2f".format(it) } ?: "-"} high=${s.highPeakCosine?.let { "%.2f".format(it) } ?: "-"} " +
            "fallback=${s.fallbackUsed} → proposed ${"%.3f".format(s.proposedThreshold)}")
        val h = s.histogram
        for (i in 0 until h.binCount) {
            if (h.counts[i] == 0) continue
            Log.i(TAG, "    ${"%+.2f".format(h.binCenter(i))} | ${"%3d".format(h.counts[i])} ${"#".repeat(h.counts[i].coerceAtMost(60))}")
        }
        // K — structural evidence overlay
        Log.i(TAG, "  must-not-link pair cosines: n=${s.mustNotLinkPairCount} " +
            "mean=${s.mustNotLinkMeanCosine?.let { "%.2f".format(it) } ?: "-"} " +
            "max=${s.mustNotLinkMaxCosine?.let { "%.2f".format(it) } ?: "-"} " +
            "| other pairs mean=${s.otherMeanCosine?.let { "%.2f".format(it) } ?: "-"}")
    }

    @Test
    fun sweepAllThreeSamples_andPooled() {
        val diag = IdentityCalibrationDiagnostic()
        val preps = listOf("sample_1.mp4", "sample_2.mp4", "sample_3.mp4").map { prepare(it) }

        val reports = preps.map { p ->
            diag.analyzeSample(p.label, p.embeddings, p.mnl)
        }
        reports.forEachIndexed { i, r ->
            val expected = if (preps[i].label == "sample_1") 5 else null
            dumpSweep(r, preps[i], expected)
        }

        // --- G. pooled distribution (calibration estimate ONLY; no cross-video clustering) ---
        val pooled = diag.analyzePooled(preps.map { it.label to it.embeddings })
        val ps = pooled.stats
        Log.i(TAG, "════════ POOLED calibration distribution (${ps.label}) ════════")
        Log.i(TAG, "NOTE: within-sample pairs only — NO cross-video identity clustering. crossVideoClustering=${pooled.crossVideoClusteringPerformed}")
        Log.i(TAG, "  embeddings=${ps.embeddingCount} pairs=${ps.pairCount}")
        Log.i(TAG, "  min=${"%.2f".format(ps.min)} p10=${"%.2f".format(ps.p10)} p25=${"%.2f".format(ps.p25)} " +
            "median=${"%.2f".format(ps.median)} mean=${"%.2f".format(ps.mean)} p75=${"%.2f".format(ps.p75)} " +
            "p90=${"%.2f".format(ps.p90)} max=${"%.2f".format(ps.max)}")
        Log.i(TAG, "  calibrator: valley=${ps.valleyCosine?.let { "%.2f".format(it) } ?: "none"} " +
            "depth=${ps.valleyDepth?.let { "%.2f".format(it) } ?: "-"} fallback=${ps.fallbackUsed} → proposed ${"%.3f".format(ps.proposedThreshold)}")
        val ph = ps.histogram
        for (i in 0 until ph.binCount) {
            if (ph.counts[i] == 0) continue
            Log.i(TAG, "    ${"%+.2f".format(ph.binCenter(i))} | ${"%3d".format(ph.counts[i])} ${"#".repeat(ph.counts[i].coerceAtMost(60))}")
        }

        // --- assertions (diagnostic invariants only) ---
        reports.forEach { r ->
            // monotonicity: ascending threshold ⇒ non-decreasing personCount
            val counts = r.sweep.rows.map { it.personCount }
            for (i in 1 until counts.size) {
                assertTrue("${r.label}: non-monotone at ${r.sweep.rows[i].threshold}", counts[i] >= counts[i - 1])
            }
            // must-not-link never violated at any threshold
            r.sweep.rows.forEach { assertEquals("${r.label}: MNL violated @ ${it.threshold}", 0, it.mustNotLinkViolations) }
            // all requested thresholds present
            assertEquals(IdentityThresholdSweep().defaultThresholds.size, r.sweep.rows.size)
        }
        // pooled did NOT do cross-video pairing
        val withinPairs = preps.sumOf { val n = it.embeddings.size; n * (n - 1) / 2 }
        assertEquals("pooled must be within-sample pairs only", withinPairs, ps.pairCount)

        // determinism: re-run the sweep for sample 1, identical membership
        val again = diag.analyzeSample(preps[0].label, preps[0].embeddings, preps[0].mnl)
        assertEquals(
            reports[0].sweep.rows.map { it.threshold to it.clusters.map { c -> c.appearanceIds } },
            again.sweep.rows.map { it.threshold to it.clusters.map { c -> c.appearanceIds } },
        )
        Log.i(TAG, "determinism: threshold sweep reproduced identically for ${preps[0].label}")
    }
}
