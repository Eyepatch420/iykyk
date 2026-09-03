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
import com.example.ikyky.features.people.data.DefaultBuildIdentitiesUseCase
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4.5 — full pipeline (2 → 3 → 4/4.5) on Samples 1, 2 and 3, dumping:
 *  - chronological appearance timeline (before/after temporal-split refinement)
 *  - suspicious-tracklet reports
 *  - dense change-point analysis (neighbour cosines, candidates, accepted cuts)
 *  - naive-vs-dense splitter comparison + timing
 *  - calibrated threshold + sensitivity
 *  - must-not-link edges
 *  - people + appearance counts
 *
 * Sample 1 ground truth (5 people / 20 appearances) is reported as VALIDATION
 * only — no threshold or split is tuned to hit it. Loose assertions.
 * logcat tag: "Phase45".
 */
@RunWith(AndroidJUnit4::class)
class SampleIdentityRefinementTest {

    private val TAG = "Phase45"
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): File {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instr.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    @Test fun sample1() = runOne("sample_1.mp4", expectPeople = 5, expectAppearances = 20)
    @Test fun sample2() = runOne("sample_2.mp4", expectPeople = null, expectAppearances = null)
    @Test fun sample3() = runOne("sample_3.mp4", expectPeople = null, expectAppearances = null)

    private fun runOne(asset: String, expectPeople: Int?, expectAppearances: Int?) = runBlocking {
        val file = copyAsset(asset)
        val uri = android.net.Uri.fromFile(file).toString()
        val dispatchers = StandardDispatcherProvider()
        val logger = AndroidLogger()
        Log.i(TAG, "════════ $asset ════════")

        // Phase 2
        val detector = MlKitFaceDetector()
        val repo = InMemoryProcessingResultRepository()
        val metaReader = MediaMetadataVideoReader(context, dispatchers)
        val extractor = MediaMetadataFrameExtractor(context, dispatchers)
        val phase2 = DefaultProcessVideoUseCase(
            metadataReader = metaReader, frameExtractor = extractor, faceDetector = detector,
            resultRepository = repo, dispatchers = dispatchers, logger = logger,
        )
        assertTrue(phase2("s", uri) {} is AppResult.Success)
        detector.close()
        val appearances = repo.getCandidates("s")
        val meta = (metaReader.read(uri) as AppResult.Success).value
        Log.i(TAG, "Phase 2: ${appearances.size} appearance candidates; video ${meta.displayWidth}x${meta.displayHeight} ${meta.durationMs}ms")

        // Phase 3
        val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
        val phase3 = DefaultGenerateAppearanceEmbeddingsUseCase(
            frameExtractor = extractor, preprocessor = DefaultFacePreprocessor(),
            embedder = embedder, modelLoader = loader, dispatchers = dispatchers, logger = logger,
        )
        val p3 = (phase3(uri, meta, appearances) { _, _ -> } as AppResult.Success).value
        Log.i(TAG, "Phase 3: ${p3.appearanceEmbeddings.size} appearance embeddings, ${p3.perObservation.size} per-obs (normal embedding calls)")

        // Phase 4 + 4.5
        val phase4 = DefaultBuildIdentitiesUseCase(dispatchers = dispatchers, logger = logger)
        val p4 = (phase4(appearances, p3.perObservation, uri, meta, phase3) as AppResult.Success).value
        val d = p4.diagnostics
        embedder.close()

        // --- A. chronological timeline (corrected appearances) ---
        Log.i(TAG, "--- A. appearance timeline (after refinement), chronological ---")
        Log.i(TAG, "appId | tid | start | end | durMs | obs | personCluster")
        val personOf = HashMap<String, String>()
        p4.people.forEach { p -> p.appearanceIds.forEach { personOf[it] = p.label } }
        p4.correctedAppearances.sortedBy { it.startTimestampMs }.forEach { a ->
            Log.i(
                TAG,
                "${a.id} | tid=${a.trackletId} | ${a.startTimestampMs} | ${a.endTimestampMs} | " +
                    "${a.endTimestampMs - a.startTimestampMs} | ${a.observationCount} | ${personOf[a.id] ?: "?"}",
            )
        }

        // --- C. suspicious tracklets ---
        Log.i(TAG, "--- C. suspicion reports ---")
        d.suspicionReports.forEach { s ->
            Log.i(
                TAG,
                "${s.appearanceId} tid=${s.trackletId} dur=${s.durationMs}ms compact=${"%.2f".format(s.globalCompactness)} " +
                    "drop=${"%.2f".format(s.maxNeighborDrop)} suspicious=${s.suspicious} ${s.reasons}",
            )
        }

        // --- D + E + F. dense analysis + proposed/accepted boundaries ---
        Log.i(TAG, "--- D/E/F. dense change-point analysis ---")
        d.denseAnalyses.forEach { da ->
            Log.i(TAG, "  ${da.appearanceId} tid=${da.trackletId} denseSamples=${da.denseSampleCount} @ ${da.denseSampleTimestampsMs}")
            Log.i(TAG, "    neighborCos = ${da.neighborCosines.map { "%.2f".format(it) }}")
            if (da.whipPanCutsMs.isNotEmpty()) Log.i(TAG, "    whip-pan cuts = ${da.whipPanCutsMs}")
            da.candidates.forEach { c ->
                Log.i(
                    TAG,
                    "    cut@${c.cutTimestampMs} L=${"%.2f".format(c.leftCompactness)} R=${"%.2f".format(c.rightCompactness)} " +
                        "X=${"%.2f".format(c.crossSimilarity)} drop=${"%.2f".format(c.drop)} " +
                        (if (c.accepted) "ACCEPTED" else "rej: ${c.rejectReason}"),
                )
            }
            Log.i(TAG, "    → accepted cuts ${da.acceptedCutsMs} → fragments ${da.resultAppearanceIds}")
        }

        // --- G. before/after count ---
        d.splitterComparison?.let { c ->
            Log.i(TAG, "--- G. naive vs dense splitter ---")
            Log.i(TAG, "  naive: ${c.naiveAppearanceCount} appearances, ${c.naiveSplits} splits (${c.naiveMs}ms)")
            Log.i(TAG, "  dense: ${c.denseAppearanceCount} appearances, ${c.denseSplits} splits (${c.denseMs}ms)")
            Log.i(TAG, "  only dense split: ${c.onlyDenseSplit} | only naive split: ${c.onlyNaiveSplit}")
        }

        // --- L. threshold ---
        val cal = d.calibration!!
        Log.i(
            TAG,
            "--- L. calibrated threshold = ${"%.3f".format(cal.threshold)} " +
                (if (cal.fallbackUsed) "(FALLBACK)" else "(gap: low ${"%.2f".format(cal.lowPeakCosine ?: 0f)}, high ${"%.2f".format(cal.highPeakCosine ?: 0f)}, depth ${"%.2f".format(cal.confidence)})") +
                (if (cal.lowConfidence) " [LOW CONFIDENCE]" else ""),
        )
        Log.i(TAG, "  sensitivity: " + cal.sensitivity.joinToString { "t=${"%.2f".format(it.threshold)}→${it.personCount}p" })

        Log.i(TAG, "--- must-not-link edges (${d.mustNotLinkEdges.size}) ---")
        d.mustNotLinkEdges.forEach { Log.i(TAG, "  ${it.appearanceIdA}<->${it.appearanceIdB} @f${it.frameIndex} IoU=${"%.2f".format(it.iou)}") }

        // --- H/I. people + appearance count ---
        Log.i(TAG, "--- H/I. PEOPLE: ${d.personCount} | appearance instances: ${d.totalAppearancesGrouped} ---")
        p4.people.forEach { p -> Log.i(TAG, "  ${p.label}: ${p.appearanceCount} = ${p.appearanceIds}") }

        // --- M. performance ---
        Log.i(
            TAG,
            "--- M. perf: normal embedding calls=${d.normalEmbeddingCalls}, dense embedding calls=${d.denseEmbeddingCalls}, " +
                "dense analysis ${d.denseAnalysisMs}ms, whole Phase-4 stage ${d.stageMs}ms",
        )
        if (expectPeople != null) {
            Log.i(TAG, "VALIDATION REFERENCE (not a target): $asset expected $expectPeople people / $expectAppearances appearances")
        }

        // loose sanity — never assert a specific count
        assertTrue("no people", p4.people.isNotEmpty())
        assertTrue(
            "every appearance instance grouped exactly once",
            d.totalAppearancesGrouped == d.appearancesAfterSplit,
        )
        val allIds = p4.correctedAppearances.map { it.id }.sorted()
        val grouped = p4.people.flatMap { it.appearanceIds }.sorted()
        assertTrue("no appearance lost/duplicated", allIds == grouped)
        for (e in d.mustNotLinkEdges) {
            val pa = p4.people.first { e.appearanceIdA in it.appearanceIds }
            val pb = p4.people.first { e.appearanceIdB in it.appearanceIds }
            assertTrue("must-not-link violated ${e.appearanceIdA}/${e.appearanceIdB}", pa.id != pb.id)
        }
        // dense path must NOT run for every appearance
        assertTrue(
            "dense embeddings should be far fewer than a full per-frame embed",
            d.denseEmbeddingCalls <= d.suspicionReports.count { it.suspicious } * 16,
        )
        // determinism
        val p4b = (phase4(appearances, p3.perObservation, uri, meta, phase3) as AppResult.Success).value
        assertTrue(
            "Phase 4/4.5 not deterministic",
            p4.people.map { it.appearanceIds.sorted() }.sortedBy { it.first() } ==
                p4b.people.map { it.appearanceIds.sorted() }.sortedBy { it.first() },
        )
        Unit
    }
}
