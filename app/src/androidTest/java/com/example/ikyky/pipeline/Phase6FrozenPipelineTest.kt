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

    /** Device identification — recorded verbatim in the Phase 6 validation report. */
    private fun logDeviceInfo() {
        val rt = Runtime.getRuntime()
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        log("DEVICE")
        log("  model           : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        log("  device/product  : ${android.os.Build.DEVICE} / ${android.os.Build.PRODUCT}")
        log("  Android version : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        log("  fingerprint     : ${android.os.Build.FINGERPRINT}")
        log("  ABIs            : ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        log("  emulator?       : ${isProbablyEmulator()}")
        log("  heap limit      : ${am.memoryClass} MB (large: ${am.largeMemoryClass} MB)")
        log("  JVM maxMemory   : ${rt.maxMemory() / (1024 * 1024)} MB")
    }

    /** Best-effort — the report must state plainly whether this was real hardware. */
    private fun isProbablyEmulator(): Boolean =
        android.os.Build.FINGERPRINT.startsWith("generic") ||
            android.os.Build.FINGERPRINT.contains("vbox") ||
            android.os.Build.FINGERPRINT.contains("emulator") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.MODEL.contains("Android SDK built for") ||
            android.os.Build.PRODUCT.contains("sdk")

    /** Peak-ish memory: PSS at the moment of the call, plus JVM heap in use. */
    private fun logMemory(label: String) {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        log("  memory @$label: javaHeapInUse=${used} MB  totalPss=${info.totalPss / 1024} MB " +
            "(dalvik=${info.dalvikPss / 1024} native=${info.nativePss / 1024})")
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
        logDeviceInfo()
        log("=".repeat(78))
        log("  crops are written to: ${cropDir().absolutePath}")

        for (sample in listOf("sample_1", "sample_2", "sample_3")) {
            runOne(sample)
        }
    }

    /**
     * Where representative crops are exported for visual inspection.
     *
     * Prefers the runner's `additionalTestOutputDir` — `connectedAndroidTest`
     * copies that OFF the device before it uninstalls the app, so the files
     * survive. (The app's own external files dir does NOT survive: the run
     * uninstalls the package on teardown and takes that directory with it.)
     *
     * Falls back to a world-readable `/sdcard` path for a plain `am instrument`
     * invocation that omits the argument.
     */
    private fun cropDir(): File {
        val fromRunner = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")
            ?.let { File(it) }
        val dir = when {
            fromRunner != null -> File(fromRunner, "phase6_crops")
            else -> File("/sdcard/phase6_crops")
        }
        dir.mkdirs()
        return dir
    }

    /**
     * Writes the RECOGNITION crop (the exact 112x112 tensor input) for a handful
     * of observations per identity cluster, so orientation, framing and
     * mirroring can be judged by eye rather than inferred from residuals.
     *
     * Filenames encode `cluster / appearance / timestamp` so a wrong grouping is
     * visible from the file listing alone.
     */
    private suspend fun exportRepresentativeCrops(
        sample: String,
        uriString: String,
        metadata: com.example.ikyky.core.media.VideoMetadata,
        clusters: List<com.example.ikyky.features.people.domain.model.IdentityCluster>,
        appearances: List<com.example.ikyky.features.processing.domain.model.AppearanceCandidate>,
        extractor: MediaMetadataFrameExtractor,
    ) {
        val aligner = com.example.ikyky.core.ml.preprocessing.ArcFaceFivePointAligner()
        val byId = appearances.associateBy { it.id }
        val dir = File(cropDir(), sample).apply { mkdirs() }
        var written = 0

        for ((ci, cluster) in clusters.withIndex()) {
            // up to 3 appearances per cluster, first observation of each
            for (appId in cluster.appearanceIds.take(3)) {
                val app = byId[appId] ?: continue
                val ref = app.observations.minByOrNull { it.timestampMs } ?: continue
                val frame = extractor.decodeFrameAt(uriString, metadata, ref.timestampMs) ?: continue
                try {
                    val s = frame.geometry.scale
                    val faceInDecoded = com.example.ikyky.core.model.DetectedFace(
                        boundingBox = com.example.ikyky.core.model.BoundingBox(
                            left = (ref.canonicalBox.left * s).toInt(),
                            top = (ref.canonicalBox.top * s).toInt(),
                            right = (ref.canonicalBox.right * s).toInt(),
                            bottom = (ref.canonicalBox.bottom * s).toInt(),
                        ),
                        landmarks = ref.landmarks.map { it.copy(x = it.x * s, y = it.y * s) },
                    )
                    val res = aligner.alignDetailed(frame.bitmap, faceInDecoded, 112) ?: continue
                    val tag = if (res.alignedByLandmarks) "5pt" else "FALLBACK"
                    val out = File(
                        dir,
                        "c%02d_%s_t%06d_%s.png".format(ci, appId, ref.timestampMs, tag),
                    )
                    out.outputStream().use {
                        res.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    res.bitmap.recycle()
                    written++
                } finally {
                    if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                }
            }
        }
        log("  representative crops written : $written -> ${dir.absolutePath}")
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
        logMemory(sample)

        // ---- visual inspection material --------------------------------------
        exportRepresentativeCrops(
            sample = sample,
            uriString = uri,
            metadata = metadata,
            clusters = clusters.clusters,
            appearances = appearances,
            extractor = MediaMetadataFrameExtractor(context, dispatchers),
        )

        // ---- assertions: STRUCTURAL ONLY, never "== 5 people" ----------------
        assertTrue("$sample: must detect faces", diag.totalFaceObservations > 0)
        assertTrue("$sample: must find whip-pan transitions", diag.whipPanTransitions > 0)
        assertTrue("$sample: must produce appearances", appearances.isNotEmpty())
        assertTrue("$sample: must produce identity clusters", clusters.clusters.isNotEmpty())
        assertEquals("$sample: MNL violations must be ZERO", 0, violations)

        // The physical-device failure rule calls out failed alignment. The
        // 5-point path has a designed fallback for detections where ML Kit did
        // not return all five landmarks (extreme pose / blur near a shot edge),
        // exactly as the Python reference's `arcface_5pt_fallback` branch does.
        // What must NOT happen is SYSTEMATIC fallback — the 0/133 signature seen
        // when the landmark mapping was wrong. Require the overwhelming majority
        // to align by landmarks.
        val landmarkRate = embeddings.diagnostics.alignedByLandmarks.toFloat() /
            embeddings.diagnostics.embeddingCalls.coerceAtLeast(1)
        assertTrue(
            "$sample: 5-point alignment must be the norm, not the exception — " +
                "got ${embeddings.diagnostics.alignedByLandmarks}/" +
                "${embeddings.diagnostics.embeddingCalls} (${"%.1f".format(landmarkRate * 100)}%). " +
                "A low rate means the landmark mapping regressed.",
            landmarkRate >= 0.90f,
        )
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
