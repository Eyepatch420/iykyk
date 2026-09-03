package com.example.ikyky.pipeline

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.ml.detector.DetectorTuning
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.quality.FaceQuality
import com.example.ikyky.core.ml.tracking.FaceObservation
import com.example.ikyky.core.ml.tracking.GreedyFaceTracker
import com.example.ikyky.core.ml.tracking.TrackerTrace
import com.example.ikyky.core.logging.AndroidLogger
import com.example.ikyky.core.media.FrameSamplingRequest
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.MediaMetadataVideoReader
import com.example.ikyky.core.media.SeekOption
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import com.example.ikyky.features.processing.domain.usecase.ProcessVideoUseCase
import com.example.ikyky.core.model.DetectedFace
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2.5 debugging harness — NOT a pass/fail correctness test (it asserts
 * only that it ran). It sweeps detector settings over the bundled Sample 1 clip
 * and dumps evidence:
 *
 *  - per (config): framesWithFaces / totalObs / multiFaceFrames / maxFaces /
 *    detect time, plus the same counters restricted to the two known overlap
 *    windows 10.1–11.5 s and 20.2–21.6 s;
 *  - a per-frame detection CSV for the baseline config, pushed to
 *    /sdcard/Android/data/<pkg>/files/phase25/ and echoed to logcat;
 *  - a tracker decision trace for the 15.0–22.5 s region (why the long
 *    tracklet stays merged), for both bridge=on and bridge=off.
 *
 * Read results with:  adb logcat -s Phase25:I
 */
@RunWith(AndroidJUnit4::class)
class Sample1DebugSweepTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = StandardDispatcherProvider()

    private val WIN_A = 10_100L..11_500L
    private val WIN_B = 20_200L..21_600L
    private val LONG_TRACKLET_REGION = 15_000L..22_500L

    private fun sampleFile(): File {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(ctx.cacheDir, "sample_1.mp4")
        if (!out.exists() || out.length() == 0L) {
            instr.assets.open("sample_1.mp4").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }

    private fun log(s: String) = s.lineSequence().forEach { android.util.Log.i("Phase25", it) }

    /** One detected face captured for analysis. */
    private data class FrameRec(
        val tsMs: Long,
        val faceIdx: Int,
        val faceCount: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val w: Int, val h: Int,
        val faceFraction: Float,
        val trackingId: Int?,
        val quality: Float,
        val blurVar: Double,
        val usable: Boolean,
    )

    /** running count of frames whose downscaled pixels were byte-identical to the previous frame */
    private var frozenPairs = 0
    private var emitted = 0

    private suspend fun detectRun(
        uri: String,
        tuning: DetectorTuning,
        maxEdge: Int,
        seek: SeekOption,
    ): Triple<List<FrameRec>, Long, String> {
        val reader = MediaMetadataVideoReader(ctx, dispatchers)
        val meta = (reader.read(uri) as AppResult.Success).value
        val extractor = MediaMetadataFrameExtractor(ctx, dispatchers)
        val detector = MlKitFaceDetector(tuning)
        val recs = ArrayList<FrameRec>()
        frozenPairs = 0
        emitted = 0
        var prevHash: Long = 0
        val started = System.currentTimeMillis()
        try {
            extractor.extractFrames(
                FrameSamplingRequest(uri, meta, fps = 4f, maxEdgePx = maxEdge, seekOption = seek)
            ).collect { frame ->
                emitted++
                val h = quickHash(frame.bitmap)
                if (emitted > 1 && h == prevHash) frozenPairs++
                prevHash = h
                val faces: List<DetectedFace> =
                    when (val r = detector.detect(frame.bitmap, 0)) {
                        is AppResult.Success -> r.value
                        is AppResult.Failure -> emptyList()
                    }
                faces.forEachIndexed { i, f ->
                    val cb = frame.geometry.toCanonical(f.boundingBox)
                    val cf = f.copy(boundingBox = cb)
                    val patch = cropPatch(frame.bitmap, f)
                    val q = FaceQuality.evaluate(cf, frame.geometry.uprightWidth, frame.geometry.uprightHeight, patch)
                    patch?.recycle()
                    recs += FrameRec(
                        tsMs = frame.timestampMs,
                        faceIdx = i,
                        faceCount = faces.size,
                        left = cb.left, top = cb.top, right = cb.right, bottom = cb.bottom,
                        w = cb.width, h = cb.height,
                        faceFraction = q.faceFraction,
                        trackingId = f.trackingId,
                        quality = q.score,
                        blurVar = q.blurVariance,
                        usable = q.isUsable,
                    )
                }
                frame.bitmap.recycle()
            }
        } finally {
            detector.close()
        }
        val note = "emitted=$emitted frozenConsecutivePairs=$frozenPairs"
        return Triple(recs, System.currentTimeMillis() - started, note)
    }

    /** cheap sampled pixel hash to spot byte-identical (frozen / keyframe-collapsed) frames */
    private fun quickHash(b: Bitmap): Long {
        var h = 1125899906842597L
        val stepX = (b.width / 16).coerceAtLeast(1)
        val stepY = (b.height / 16).coerceAtLeast(1)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                h = 31 * h + b.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return h
    }

    private fun cropPatch(bmp: Bitmap, f: DetectedFace): Bitmap? {
        val b = f.boundingBox
        val l = b.left.coerceIn(0, bmp.width - 1)
        val t = b.top.coerceIn(0, bmp.height - 1)
        val r = b.right.coerceIn(l + 1, bmp.width)
        val bo = b.bottom.coerceIn(t + 1, bmp.height)
        if (r - l < 8 || bo - t < 8) return null
        return runCatching {
            val c = Bitmap.createBitmap(bmp, l, t, r - l, bo - t)
            val me = 64
            if (maxOf(c.width, c.height) <= me) c
            else Bitmap.createScaledBitmap(c, me, me, true).also { if (it !== c) c.recycle() }
        }.getOrNull()
    }

    private fun summary(tag: String, recs: List<FrameRec>, detectMs: Long, note: String) {
        val byFrame = recs.groupBy { it.tsMs }
        val framesWithFaces = byFrame.count { it.value.isNotEmpty() }
        val totalObs = recs.size
        val multi = byFrame.count { it.value.size >= 2 }
        val maxFaces = byFrame.values.maxOfOrNull { it.size } ?: 0
        val degenerate = recs.count { it.w < 24 || it.h < 24 }
        log("[$tag] $note framesWithFaces=$framesWithFaces totalObs=$totalObs multiFaceFrames=$multi maxFaces=$maxFaces degenerateBoxes=$degenerate detectMs=$detectMs")
        for ((name, win) in listOf("WIN_A(10.1-11.5)" to WIN_A, "WIN_B(20.2-21.6)" to WIN_B)) {
            val w = recs.filter { it.tsMs in win }
            val wf = w.groupBy { it.tsMs }
            log("    $name: frames=${wf.size} obs=${w.size} multi=${wf.count { it.value.size >= 2 }} maxFaces=${wf.values.maxOfOrNull { it.size } ?: 0}")
            wf.toSortedMap().forEach { (ts, fs) ->
                val desc = fs.joinToString(" | ") {
                    "box(${it.left},${it.top},${it.right},${it.bottom}) ${it.w}x${it.h} ff=${"%.3f".format(it.faceFraction)} tid=${it.trackingId} q=${"%.2f".format(it.quality)} blur=${"%.1f".format(it.blurVar)}"
                }
                log("      t=$ts n=${fs.size}  $desc")
            }
        }
    }

    private fun dumpTimeline(name: String, recs: List<FrameRec>) {
        val dir = runCatching { File(ctx.getExternalFilesDir(null), "phase25").apply { mkdirs() } }.getOrNull()
        if (dir != null) {
            runCatching {
                File(dir, "$name.csv").printWriter().use { pw ->
                    pw.println("tsMs,faceIdx,faceCount,left,top,right,bottom,w,h,faceFraction,trackingId,quality,blurVar,usable")
                    recs.forEach {
                        pw.println("${it.tsMs},${it.faceIdx},${it.faceCount},${it.left},${it.top},${it.right},${it.bottom},${it.w},${it.h},${"%.4f".format(it.faceFraction)},${it.trackingId ?: ""},${"%.4f".format(it.quality)},${"%.2f".format(it.blurVar)},${it.usable}")
                    }
                }
            }
        }
        // full timeline to logcat (survives uninstall)
        log("--- FULL PER-FRAME TIMELINE [$name] ---")
        recs.groupBy { it.tsMs }.toSortedMap().forEach { (ts, fs) ->
            val d = fs.joinToString(" | ") {
                "${it.w}x${it.h} ff=${"%.2f".format(it.faceFraction)} tid=${it.trackingId} q=${"%.2f".format(it.quality)}${if (it.usable) "" else " [lowQ]"}"
            }
            log("  t=${"%5d".format(ts)} n=${fs.size}  $d")
        }
    }

    private fun recsToObservations(recs: List<FrameRec>): List<FaceObservation> {
        var seq = 0
        // group per timestamp so frameIndex is stable & ordered
        val tsList = recs.map { it.tsMs }.distinct().sorted()
        val idxOf = tsList.withIndex().associate { (i, ts) -> ts to i }
        return recs.map { r ->
            FaceObservation(
                id = "o${seq++}",
                frameIndex = idxOf.getValue(r.tsMs),
                timestampMs = r.tsMs,
                face = DetectedFace(
                    boundingBox = com.example.ikyky.core.model.BoundingBox(r.left, r.top, r.right, r.bottom),
                    trackingId = r.trackingId,
                ),
                qualityScore = r.quality,
                usable = r.usable,
            )
        }
    }

    private class CollectingTrace(private val region: LongRange) : TrackerTrace {
        val lines = ArrayList<String>()
        override fun onMatch(e: TrackerTrace.MatchEvent) {
            if (e.frameTimestampMs !in region) return
            lines += "MATCH  t=${e.frameTimestampMs} trk=${e.trackletId} obs=${e.obsId} via=${e.method} " +
                "iou=${"%.3f".format(e.iou)} cDist=${"%.1f".format(e.centerDist)} sRatio=${"%.2f".format(e.sizeRatio)} " +
                "prevTid=${e.prevTrackingId} newTid=${e.newTrackingId} idChanged=${e.trackingIdChanged} " +
                "prevBox=${e.prevBox.left},${e.prevBox.top},${e.prevBox.right},${e.prevBox.bottom} " +
                "newBox=${e.newBox.left},${e.newBox.top},${e.newBox.right},${e.newBox.bottom}"
        }
        override fun onOpen(e: TrackerTrace.OpenEvent) {
            if (e.frameTimestampMs !in region) return
            lines += "OPEN   t=${e.frameTimestampMs} trk=${e.trackletId} obs=${e.obsId} tid=${e.trackingId} reason='${e.reason}'"
        }
        override fun onClose(e: TrackerTrace.CloseEvent) {
            if (e.lastTimestampMs !in region && e.frameTimestampMs !in region) return
            lines += "CLOSE  t=${e.frameTimestampMs} trk=${e.trackletId} lastT=${e.lastTimestampMs} gap=${e.gapMs} obs=${e.observationCount}"
        }
    }

    @Test
    fun sweep() = runBlocking {
        val uri = android.net.Uri.fromFile(sampleFile()).toString()

        // report the container facts
        val meta = (MediaMetadataVideoReader(ctx, dispatchers).read(uri) as AppResult.Success).value
        log("=== Sample 1 metadata (as MediaMetadataRetriever sees it) ===")
        log("durationMs=${meta.durationMs} raw=${meta.rawWidth}x${meta.rawHeight} rotation=${meta.rotationDegrees} " +
            "display=${meta.displayWidth}x${meta.displayHeight} portrait=${meta.isPortrait}")

        data class Cfg(val name: String, val tuning: DetectorTuning, val maxEdge: Int, val seek: SeekOption)
        val acc06 = DetectorTuning(accurateMode = true, minFaceFraction = 0.06f, enableTracking = true)
        val acc04 = DetectorTuning(accurateMode = true, minFaceFraction = 0.04f, enableTracking = true)
        val configs = listOf(
            // seek option is the prime suspect — compare it head to head
            Cfg("720_mf06_SYNC", acc06, 720, SeekOption.CLOSEST_SYNC),
            Cfg("720_mf06_CLOSEST", acc06, 720, SeekOption.CLOSEST),
            Cfg("720_mf04_CLOSEST", acc04, 720, SeekOption.CLOSEST),
            Cfg("1080_mf06_CLOSEST", acc06, 1080, SeekOption.CLOSEST),
            Cfg("1080_mf04_CLOSEST", acc04, 1080, SeekOption.CLOSEST),
            // repeat baseline to expose run-to-run nondeterminism
            Cfg("720_mf06_CLOSEST_rep2", acc06, 720, SeekOption.CLOSEST),
        )

        val results = LinkedHashMap<String, Triple<List<FrameRec>, Long, String>>()
        log("=== DETECTION SWEEP ===")
        for (c in configs) {
            val r = detectRun(uri, c.tuning, c.maxEdge, c.seek)
            results[c.name] = r
            summary(c.name, r.first, r.second, r.third)
        }

        // full per-frame timelines to logcat (+ CSV if external dir is writable)
        dumpTimeline("720_mf06_SYNC", results.getValue("720_mf06_SYNC").first)
        dumpTimeline("720_mf06_CLOSEST", results.getValue("720_mf06_CLOSEST").first)
        results["1080_mf04_CLOSEST"]?.let { dumpTimeline("1080_mf04_CLOSEST", it.first) }

        // Tracker trace over the long-tracklet region, using real (CLOSEST) data.
        val traceCfgName = "1080_mf04_CLOSEST"
        log("=== TRACKER TRACE ($LONG_TRACKLET_REGION ms) using detections from '$traceCfgName' ===")
        val obs = recsToObservations(results.getValue(traceCfgName).first)

        for (bridge in listOf(true, false)) {
            val trace = CollectingTrace(LONG_TRACKLET_REGION)
            val tracker = GreedyFaceTracker(bridgeAcrossTrackingIds = bridge, trace = trace)
            val tracklets = tracker.buildTracklets(obs)
            log("--- bridgeAcrossTrackingIds=$bridge  → ${tracklets.size} tracklets ---")
            trace.lines.forEach { log("  $it") }
            log("  tracklets spanning the region:")
            tracklets.filter { it.startTimestampMs <= LONG_TRACKLET_REGION.last && it.endTimestampMs >= LONG_TRACKLET_REGION.first }
                .forEach {
                    val tids = it.observations.mapNotNull { o -> o.trackingId }.distinct()
                    log("    trk=${it.id} ${it.startTimestampMs}→${it.endTimestampMs} obs=${it.frameCount} distinctTrackingIds=$tids")
                }
        }

        // ---- FULL REAL PIPELINE with the Phase-2.5 production defaults ----
        log("=== FULL PIPELINE (production defaults: 1080px / CLOSEST / mf006 / bridge=off) ===")
        val detector = MlKitFaceDetector()
        val resultRepo = InMemoryProcessingResultRepository()
        val useCase: ProcessVideoUseCase = DefaultProcessVideoUseCase(
            metadataReader = MediaMetadataVideoReader(ctx, dispatchers),
            frameExtractor = MediaMetadataFrameExtractor(ctx, dispatchers),
            faceDetector = detector,
                        resultRepository = resultRepo,
            dispatchers = dispatchers,
            logger = AndroidLogger(),
        )
        val r2 = useCase("phase25", uri) { }
        detector.close()
        val d = (r2 as AppResult.Success).value.diagnostics
        log("framesPlanned=${d.framesPlanned} framesSampled=${d.framesSampled} framesDecodedOk=${d.framesDecodedOk}")
        log("framesWithFaces=${d.framesWithFaces} totalObs=${d.totalFaceObservations} lowQ=${d.observationsLowQuality}")
        log("multiFaceFrames=${d.multiFaceFrames} maxFaces=${d.maxFacesInAnyFrame}")
        log("rawTracklets=${d.rawTracklets} filteredOut=${d.trackletsFilteredOut} afterFilter=${d.trackletsAfterFilter} appearances=${d.appearancesDetected}")
        log("totalMs=${d.totalProcessingMs} avgFrameMs=${"%.1f".format(d.avgFrameProcessingMs)}")
        log("--- appearance candidates ---")
        resultRepo.getCandidates("phase25").forEach {
            log("  ${it.id}: ${it.startTimestampMs}→${it.endTimestampMs}ms obs=${it.observationCount} tid=${it.lastTrackingId} meanQ=${"%.2f".format(it.meanQuality)}")
        }
        log("VALIDATION TARGET: 5 people / 20 appearances")

        assertTrue(results.isNotEmpty())
    }
}
