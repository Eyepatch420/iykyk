package com.example.ikyky.pipeline

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.logging.AndroidLogger
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.MediaMetadataVideoReader
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.tracking.GreedyFaceTracker
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import com.example.ikyky.features.processing.domain.model.ProcessingProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the REAL Phase-2 pipeline (metadata → sample → ML Kit detect → track →
 * appearance candidates) on the bundled Sample 1 clip on-device.
 *
 * Sample 1 validation targets (from the assignment — NOT hard-coded into the
 * pipeline, only asserted loosely here): 5 distinct people, 20 total
 * appearances, with A+B sharing frames ~10.1–11.5 s and C+D ~20.2–21.6 s.
 *
 * Phase 2 produces *appearance candidates*, not people. This test asserts the
 * pipeline runs end-to-end, finds multiple faces, detects multi-face frames,
 * and yields a plausible number of appearance candidates. It PRINTS the full
 * diagnostics so the candidate count can be compared against the target of 20
 * before Phase 3.
 */
@RunWith(AndroidJUnit4::class)
class Sample1PipelineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAssetToCache(name: String): File {
        val instrCtx = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instrCtx.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    @Test
    fun sample1_pipeline_runsEndToEnd_andReportsDiagnostics() = runBlocking {
        val file = copyAssetToCache("sample_1.mp4")
        val uri = android.net.Uri.fromFile(file).toString()

        val dispatchers = StandardDispatcherProvider()
        val detector = MlKitFaceDetector()
        val resultRepo = InMemoryProcessingResultRepository()

        val useCase = DefaultProcessVideoUseCase(
            metadataReader = MediaMetadataVideoReader(context, dispatchers),
            frameExtractor = MediaMetadataFrameExtractor(context, dispatchers),
            faceDetector = detector,
            faceTracker = GreedyFaceTracker(),
            resultRepository = resultRepo,
            dispatchers = dispatchers,
            logger = AndroidLogger(),
        )

        val ticks = ArrayList<ProcessingProgress>()
        val result = useCase("sample1-session", uri) { ticks += it }
        detector.close()

        assertTrue("pipeline failed: $result", result is AppResult.Success)
        val outcome = (result as AppResult.Success).value
        val d = outcome.diagnostics

        println("=== Sample 1 Phase-2 diagnostics ===")
        println("framesPlanned            = ${d.framesPlanned}")
        println("framesSampled            = ${d.framesSampled}")
        println("framesDecodedOk          = ${d.framesDecodedOk}")
        println("framesRejectedAsInvalid  = ${d.framesRejectedAsInvalid}")
        println("framesWithFaces          = ${d.framesWithFaces}")
        println("totalFaceObservations    = ${d.totalFaceObservations}")
        println("observationsLowQuality   = ${d.observationsLowQuality}")
        println("multiFaceFrames          = ${d.multiFaceFrames}")
        println("maxFacesInAnyFrame       = ${d.maxFacesInAnyFrame}")
        println("rawTracklets             = ${d.rawTracklets}")
        println("trackletsFilteredOut     = ${d.trackletsFilteredOut}")
        println("trackletsAfterFilter     = ${d.trackletsAfterFilter}")
        println("appearancesDetected      = ${d.appearancesDetected}")
        println("totalProcessingMs        = ${d.totalProcessingMs}")
        println("avgFrameProcessingMs     = ${"%.1f".format(d.avgFrameProcessingMs)}")
        println("--- appearance candidates (start→end ms, obs, meanQ) ---")
        resultRepo.getCandidates("sample1-session").forEach {
            println("  ${it.id}: ${it.startTimestampMs}→${it.endTimestampMs}  obs=${it.observationCount}  meanQ=${"%.2f".format(it.meanQuality)}  tid=${it.lastTrackingId}")
        }
        println("VALIDATION TARGET (assignment): 5 people / 20 appearances")
        println("====================================")

        // Loose, non-hard-coded sanity assertions:
        assertTrue("no frames sampled", d.framesSampled > 0)
        assertTrue("no faces detected at all", d.totalFaceObservations > 0)
        assertTrue("expected at least a few appearance candidates", d.appearancesDetected >= 3)
        assertTrue("progress never reached 1.0", ticks.any { it.fraction >= 0.999f })
    }
}
