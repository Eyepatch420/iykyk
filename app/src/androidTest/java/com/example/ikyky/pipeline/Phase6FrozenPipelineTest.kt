package com.example.ikyky.pipeline

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.constants.PipelineDefaults
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
import com.example.ikyky.features.people.domain.usecase.AgglomerativeIdentityClusterer
import com.example.ikyky.features.people.domain.usecase.MustNotLinkBuilder
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.TrackerGateEmbedder
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Phase 6 validation** — runs the FROZEN Phase 5I pipeline end-to-end on all
 * three bundled sample clips and prints the diagnostics the Phase 6 brief
 * requires, so Android's numbers can be compared against the Python reference.
 *
 * ## What this test is NOT
 *
 * It does **not** assert that any sample yields 5 people. The Python reference
 * over-splits (S1 = 8, S2 = 6, S3 = 6 against a true 5) and that is *known,
 * documented residual behaviour*. The purpose of Phase 6 is PORT FIDELITY, not
 * tuning Android until it prints the expected answer. Assertions here are
 * therefore structural: the pipeline runs, finds transitions, produces
 * tracklets and appearances, and commits **zero must-not-link violations**.
 *
 * Read the logged `PHASE6-DIAG` lines for the actual comparison.
 */
@RunWith(AndroidJUnit4::class)
class Phase6FrozenPipelineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAssetToCache(name: String): File {
        val instrCtx = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instrCtx.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("$TAG $line")
    }

    @Test
    fun allThreeSamples_runTheFrozenPipeline_andReportDiagnostics() = runBlocking {
        log("=".repeat(78))
        log("PHASE 6 — FROZEN PIPELINE (Phase 5I Option A) ON DEVICE")
        log("  fps=${PipelineDefaults.FRAME_SAMPLE_FPS}  decode<=${PipelineDefaults.FRAME_DECODE_MAX_EDGE_PX}px" +
            "  seek=${PipelineDefaults.FRAME_SEEK_OPTION_NAME}")
        log("  gate: minCos=${PipelineDefaults.SHOT_AWARE_APPEARANCE_MIN_COS}" +
            " history=${PipelineDefaults.SHOT_AWARE_APPEARANCE_HISTORY} crop=EXPANDED")
        log("  recognition crop = arcface_5pt · aggregation = MEAN")
        log("  cluster threshold = ${PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD}")
        log("=".repeat(78))

        for (sample in listOf("sample_1", "sample_2", "sample_3")) {
            runOne(sample)
        }
    }

    private suspend fun runOne(sample: String) {
        val file = copyAssetToCache("$sample.mp4")
        val uri = android.net.Uri.fromFile(file).toString()

        val dispatchers = StandardDispatcherProvider()
        val logger = AndroidLogger()
        val detector = MlKitFaceDetector()
        val repo = InMemoryProcessingResultRepository()
        val modelLoader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(modelLoader, ModelSpec.MOBILE_FACE_NET)

        val wallStart = System.currentTimeMillis()

        // ---- Phase 6B/6C/6D: sample -> detect -> gate-embed -> shot-aware track
        val process = DefaultProcessVideoUseCase(
            metadataReader = MediaMetadataVideoReader(context, dispatchers),
            frameExtractor = MediaMetadataFrameExtractor(context, dispatchers),
            faceDetector = detector,
            resultRepository = repo,
            dispatchers = dispatchers,
            logger = logger,
            gateEmbedder = TrackerGateEmbedder(embedder),
        )
        val outcome = process(sample, uri) {}
        assertTrue("$sample: processing must succeed", outcome is AppResult.Success)
        detector.close()

        val diag = repo.getDiagnostics(sample)!!
        val appearances = repo.getCandidates(sample)

        // ---- Phase 6E/6F/6G: 5-point crop -> MobileFaceNet -> mean aggregation
        val embedStart = System.currentTimeMillis()
        val embedUseCase = DefaultGenerateAppearanceEmbeddingsUseCase(
            frameExtractor = MediaMetadataFrameExtractor(context, dispatchers),
            preprocessor = DefaultFacePreprocessor(),
            embedder = embedder,
            modelLoader = modelLoader,
            dispatchers = dispatchers,
            logger = logger,
        )
        val metadata = (MediaMetadataVideoReader(context, dispatchers).read(uri)
            as AppResult.Success).value
        val embedResult = embedUseCase(uri, metadata, appearances) { _, _ -> }
        assertTrue("$sample: embedding must succeed", embedResult is AppResult.Success)
        val embeddings = (embedResult as AppResult.Success).value
        val embedMs = System.currentTimeMillis() - embedStart

        // ---- Phase 6H/6I: must-not-link -> agglomerative clustering
        val clusterStart = System.currentTimeMillis()
        val mnl = MustNotLinkBuilder().build(appearances)
        val clusters = AgglomerativeIdentityClusterer().cluster(
            embeddings.appearanceEmbeddings,
            PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD,
            mnl,
        )
        val clusterMs = System.currentTimeMillis() - clusterStart
        val totalMs = System.currentTimeMillis() - wallStart

        // ---- must-not-link violations: MUST be zero -------------------------
        val mnlKeys = mnl.map { it.key }.toSet()
        var violations = 0
        for (c in clusters.clusters) {
            for (i in c.appearanceIds.indices) {
                for (j in i + 1 until c.appearanceIds.size) {
                    val a = c.appearanceIds[i]
                    val b = c.appearanceIds[j]
                    val key = if (a <= b) a to b else b to a
                    if (key in mnlKeys) violations++
                }
            }
        }

        // ---- required Phase 6 diagnostics -----------------------------------
        log("")
        log("-".repeat(78))
        log("PHASE6-DIAG  $sample")
        log("-".repeat(78))
        log("  decoded frames (sampled @8fps) : ${diag.framesDecodedOk} / ${diag.framesPlanned}")
        log("  shot-scan frames analysed      : ${diag.shotScanFramesAnalysed}")
        log("  shot boundary spikes           : ${diag.shotBoundaries}")
        log("  whip-pan transitions (barriers): ${diag.whipPanTransitions}")
        log("  detected faces                 : ${diag.totalFaceObservations}")
        log("  frames with faces              : ${diag.framesWithFaces}")
        log("  multi-face frames              : ${diag.multiFaceFrames}")
        log("  max faces in any frame         : ${diag.maxFacesInAnyFrame}")
        log("  raw tracklets                  : ${diag.rawTracklets}")
        log("  appearances (>=2 obs)          : ${appearances.size}")
        log("  tracker gate embeddings        : ${diag.trackerGateEmbeddings}")
        log("  recognition inferences         : ${embeddings.diagnostics.embeddingCalls}")
        log("    aligned by 5 landmarks       : ${embeddings.diagnostics.alignedByLandmarks}")
        log("    box-crop fallback            : ${embeddings.diagnostics.alignedByBoxFallback}")
        log("  appearance embeddings          : ${embeddings.appearanceEmbeddings.size}")
        log("  embedding dimension            : ${embeddings.appearanceEmbeddings.firstOrNull()?.embedding?.dimension}")
        log("  must-not-link edges            : ${mnl.size}")
        log("  IDENTITY CLUSTERS (people)     : ${clusters.clusters.size}")
        log("  cluster size signature         : ${clusters.clusters.map { it.appearanceIds.size }.sortedDescending()}")
        log("  merges blocked by MNL          : ${clusters.mergesBlockedByMustNotLink}")
        log("  MNL VIOLATIONS                 : $violations")
        log("  --- timing ---")
        log("  shot scan                      : ${diag.shotScanMs} ms")
        log("  gate embedding                 : ${diag.trackerGateEmbeddingMs} ms")
        log("  tracking                       : ${diag.trackingMs} ms")
        log("  detection + sampling pass      : ${diag.totalProcessingMs} ms (incl. above)")
        log("  recognition embedding stage    : $embedMs ms")
        log("    model load                   : ${embeddings.diagnostics.modelLoadMs} ms")
        log("    inference only               : ${embeddings.diagnostics.totalInferenceMs} ms")
        log("    frame re-decode              : ${embeddings.diagnostics.totalRedecodeMs} ms")
        log("  clustering                     : $clusterMs ms")
        log("  TOTAL                          : $totalMs ms")

        // ---- embedding norms (contract check) --------------------------------
        val norms = embeddings.appearanceEmbeddings.map { e ->
            var n = 0f
            for (v in e.embedding.vector) n += v * v
            kotlin.math.sqrt(n)
        }
        if (norms.isNotEmpty()) {
            log("  embedding L2 norms: min=${"%.6f".format(norms.min())} " +
                "max=${"%.6f".format(norms.max())}")
        }

        // ---- assertions: STRUCTURAL ONLY, never "== 5 people" ----------------
        assertTrue("$sample: must detect faces", diag.totalFaceObservations > 0)
        assertTrue("$sample: must find whip-pan transitions", diag.whipPanTransitions > 0)
        assertTrue("$sample: must produce appearances", appearances.isNotEmpty())
        assertTrue("$sample: must produce identity clusters", clusters.clusters.isNotEmpty())
        assertEquals("$sample: MNL violations must be ZERO", 0, violations)
        for (e in embeddings.appearanceEmbeddings) {
            assertEquals("$sample: embedding must be 192-d", 192, e.embedding.dimension)
        }
        for (n in norms) {
            assertEquals("$sample: embeddings must be L2-normalized", 1.0f, n, 1e-3f)
        }
    }

    private companion object {
        const val TAG = "PHASE6"
    }
}
