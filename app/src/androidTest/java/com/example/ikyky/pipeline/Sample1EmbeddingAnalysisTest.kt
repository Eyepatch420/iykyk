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
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 3 steps 21 & 22 — run BOTH stages on the bundled Sample 1 clip and dump:
 *  - per-appearance: obs count, embeddings generated, best quality, dim
 *  - full pairwise cosine matrix between appearance embeddings
 *  - for the long tracklets: time → cosine(member, appearance centroid)
 *
 * Nothing here is turned into a global cluster (that's Phase 4). Loose
 * assertions only; the value is the logcat dump (tag "Phase3").
 */
@RunWith(AndroidJUnit4::class)
class Sample1EmbeddingAnalysisTest {

    private val TAG = "Phase3"
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): File {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instr.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return out
    }

    @Test
    fun sample1_embeddings_analysis() = runBlocking {
        val file = copyAsset("sample_1.mp4")
        val uri = android.net.Uri.fromFile(file).toString()
        val dispatchers = StandardDispatcherProvider()

        // --- Phase 2 ---
        val detector = MlKitFaceDetector()
        val repo = InMemoryProcessingResultRepository()
        val metaReader = MediaMetadataVideoReader(context, dispatchers)
        val extractor = MediaMetadataFrameExtractor(context, dispatchers)

        val phase2 = DefaultProcessVideoUseCase(
            metadataReader = metaReader,
            frameExtractor = extractor,
            faceDetector = detector,
            faceTracker = GreedyFaceTracker(),
            resultRepository = repo,
            dispatchers = dispatchers,
            logger = AndroidLogger(),
        )
        val p2 = phase2("s1", uri) {}
        detector.close()
        assertTrue("phase 2 failed: $p2", p2 is AppResult.Success)

        val appearances = repo.getCandidates("s1")
        val meta = (metaReader.read(uri) as AppResult.Success).value
        Log.i(TAG, "video: ${meta.displayWidth}x${meta.displayHeight} rot=${meta.rotationDegrees} dur=${meta.durationMs}ms")
        Log.i(TAG, "appearances from Phase 2: ${appearances.size}")

        // --- Phase 3 ---
        val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
        val phase3 = DefaultGenerateAppearanceEmbeddingsUseCase(
            frameExtractor = extractor,
            preprocessor = DefaultFacePreprocessor(),
            embedder = embedder,
            modelLoader = loader,
            dispatchers = dispatchers,
            logger = AndroidLogger(),
        )
        val p3 = phase3(uri, meta, appearances) { _, _ -> }
        assertTrue("phase 3 failed: $p3", p3 is AppResult.Success)
        val result = (p3 as AppResult.Success).value
        val d = result.diagnostics

        Log.i(TAG, "=== Phase 3 diagnostics ===")
        Log.i(TAG, "appearancesProcessed=${d.appearancesProcessed} embedded=${d.appearancesEmbedded} withoutEmbedding=${d.appearancesWithoutEmbedding}")
        Log.i(TAG, "observationsSelected=${d.observationsSelected} embeddingCalls=${d.embeddingCalls} failures=${d.embeddingFailures}")
        Log.i(TAG, "framesRedecoded=${d.framesRedecoded} alignedByLandmarks=${d.alignedByLandmarks} boxFallback=${d.alignedByBoxFallback} outliersRejected=${d.outliersRejected}")
        Log.i(TAG, "modelLoadMs=${d.modelLoadMs} avgInferenceMs=${"%.1f".format(d.avgInferenceMs)} avgRedecodeMs=${"%.1f".format(d.avgRedecodeMs)}")
        Log.i(TAG, "totalStageMs=${d.totalStageMs} (align+preproc=${d.totalPreprocessMs} inference=${d.totalInferenceMs} redecode=${d.totalRedecodeMs})")

        val appEmb = result.appearanceEmbeddings
        Log.i(TAG, "--- per appearance ---")
        appEmb.forEach { e ->
            val src = appearances.first { it.id == e.appearanceId }
            Log.i(
                TAG,
                "${e.appearanceId} tid=${e.trackletId} obs=${src.observationCount} " +
                    "members=${e.memberCount} rejected=${e.rejectedOutliers} dim=${e.dimension} " +
                    "bestQ=${"%.2f".format(e.bestQuality)} selfSim=${"%.3f".format(e.meanMemberSimilarity)} " +
                    "span=${src.startTimestampMs}->${src.endTimestampMs}ms",
            )
        }

        // --- pairwise cosine matrix ---
        Log.i(TAG, "--- pairwise cosine (appearance embeddings) ---")
        for (i in appEmb.indices) {
            val row = StringBuilder("${appEmb[i].appearanceId.padEnd(10)} ")
            for (j in appEmb.indices) {
                val c = if (i == j) 1f else appEmb[i].cosineSimilarity(appEmb[j])
                row.append("%+.2f ".format(c))
            }
            Log.i(TAG, row.toString())
        }

        // --- long tracklets: time -> similarity to that appearance's centroid ---
        Log.i(TAG, "--- intra-appearance drift (member -> centroid cosine over time) ---")
        val byApp = result.perObservation.groupBy { it.appearanceId }
        appEmb.sortedByDescending { e -> appearances.first { it.id == e.appearanceId }.observationCount }
            .take(4)
            .forEach { e ->
                val members = byApp[e.appearanceId].orEmpty().sortedBy { it.timestampMs }
                val line = members.joinToString("  ") { m ->
                    "${m.timestampMs}ms=${"%.2f".format(m.embedding.cosineSimilarity(e.embedding))}"
                }
                val minSim = members.minOfOrNull { it.embedding.cosineSimilarity(e.embedding) } ?: 1f
                val flag = if (minSim < 0.6f) "  <-- POTENTIAL IDENTITY/TRACK MERGE" else ""
                Log.i(TAG, "${e.appearanceId} (tid=${e.trackletId}): $line$flag")
            }

        embedder.close()

        // loose sanity
        assertTrue("no appearance embeddings produced", appEmb.isNotEmpty())
        assertTrue("every appearance embedding must be 192-d", appEmb.all { it.dimension == 192 })
        assertTrue("every appearance embedding must be finite", appEmb.all { it.embedding.isFinite() })
        assertTrue(
            "an appearance's members should mostly agree with their own centroid",
            appEmb.count { it.meanMemberSimilarity >= 0.5f } >= appEmb.size / 2,
        )
        // diagnostic-only reference to FaceEmbedding.cosineSimilarity companion
        val selfCos = FaceEmbedding.cosineSimilarity(appEmb.first().embedding, appEmb.first().embedding)
        assertTrue("companion cosine sanity", selfCos > 0.999f)
    }
}
