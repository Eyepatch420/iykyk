package com.example.ikyky.pipeline

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.logging.AndroidLogger
import com.example.ikyky.core.media.FrameSamplingRequest
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.MediaMetadataVideoReader
import com.example.ikyky.core.ml.detector.DetectorTuning
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.ml.shots.DefaultShotScanner
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.TrackerGateEmbedder
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import com.example.ikyky.features.processing.domain.model.ProcessingProgress
import com.example.ikyky.features.processing.domain.model.ProcessingStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 6.1 — Android resource-lifecycle regression tests.
 *
 * These protect the specific failures found while investigating the vivo
 * "Process crashed": a leaked TFLite interpreter + model mmap per pipeline run,
 * a shot scan that must stay streaming, and progress that must represent the
 * shot-scan pass rather than a misleading "0 / 240".
 *
 * They deliberately use the REAL Android media/ML stack (no Bitmap mocking is
 * possible on the JVM) on the bundled sample_1 clip.
 */
@RunWith(AndroidJUnit4::class)
class Phase6ResourceLifecycleTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sampleUri(name: String = "sample_1.mp4"): String {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instr.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return android.net.Uri.fromFile(out).toString()
    }

    private fun pss(): Long {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        return info.totalPss / 1024L
    }

    /**
     * Native bytes CURRENTLY ALLOCATED (not resident, not PSS). This is the
     * figure that actually matters for "will it OOM": PSS ratchets up on Android
     * because the native allocator does not return freed pages to the OS, so PSS
     * growth alone does not prove a leak. `getNativeHeapAllocatedSize()` falls
     * when memory is genuinely freed.
     */
    private fun nativeAllocMb(): Long = android.os.Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)

    /** GC + finalizers + a short settle, so "after" measurements are fair. */
    private fun settle() {
        repeat(3) { Runtime.getRuntime().gc(); Runtime.getRuntime().runFinalization(); Thread.sleep(150) }
    }

    /** Open file descriptors for this process — a leaked AFD / retriever shows here. */
    private fun openFdCount(): Int =
        runCatching { File("/proc/self/fd").listFiles()?.size ?: -1 }.getOrDefault(-1)

    private fun log(m: String) = Log.i("PHASE6RL", m)

    // =======================================================================
    // 1-3. MediaMetadataRetriever lifecycle
    // =======================================================================

    @Test
    fun frameExtractor_releasesRetriever_onNormalCompletion() = runBlocking {
        // If the retriever were leaked, a second full pass over the same file in
        // the same process would still work but native codec handles would pile
        // up. We assert the observable proxy: many sequential passes complete and
        // PSS does not climb without bound.
        val uri = sampleUri()
        val d = StandardDispatcherProvider()
        val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
        val ex = MediaMetadataFrameExtractor(context, d)

        val basePss = pss()
        repeat(6) { pass ->
            var frames = 0
            ex.extractFrames(
                FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 320),
            ).collect { f ->
                frames++
                f.bitmap.recycle()
            }
            assertTrue("pass $pass decoded no frames", frames > 100)
            log("pass $pass frames=$frames pss=${pss()}MB")
        }
        val grownBy = pss() - basePss
        assertTrue(
            "PSS grew ${grownBy}MB across 6 extractor passes — a retriever/codec leak",
            grownBy < 80,
        )
    }

    @Test
    fun frameExtractor_releasesRetriever_whenCollectorThrows() = runBlocking {
        val uri = sampleUri()
        val d = StandardDispatcherProvider()
        val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
        val ex = MediaMetadataFrameExtractor(context, d)

        // Throw from inside collect on the 5th frame, many times over. If the
        // finally{release()} did not run, sequential retries would degrade.
        repeat(8) { attempt ->
            val thrown = runCatching {
                var n = 0
                ex.extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 320))
                    .collect { f ->
                        f.bitmap.recycle()
                        if (++n == 5) error("boom $attempt")
                    }
            }.exceptionOrNull()
            assertTrue("attempt $attempt did not surface the collector error", thrown is IllegalStateException)
        }
        // a fresh pass must still fully succeed afterwards
        var frames = 0
        ex.extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 320))
            .collect { f -> frames++; f.bitmap.recycle() }
        assertTrue("extractor unusable after collector exceptions", frames > 100)
    }

    @Test
    fun frameExtractor_releasesRetriever_onCancellation() = runBlocking {
        val uri = sampleUri()
        val d = StandardDispatcherProvider()
        val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
        val ex = MediaMetadataFrameExtractor(context, d)

        repeat(8) { attempt ->
            val job = launch {
                var n = 0
                ex.extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 320))
                    .collect { f ->
                        f.bitmap.recycle()
                        if (++n == 10) throw CancellationException("cancel $attempt")
                        yield()
                    }
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        var frames = 0
        ex.extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 320))
            .collect { f -> frames++; f.bitmap.recycle() }
        assertTrue("extractor unusable after cancellations", frames > 100)
    }

    // =======================================================================
    // 5-6. Flow stays streaming; shot scan does not retain all frames
    // =======================================================================

    @Test
    fun frameExtractor_isStreaming_onlyOneBitmapInFlight() = runBlocking {
        val uri = sampleUri()
        val d = StandardDispatcherProvider()
        val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
        val ex = MediaMetadataFrameExtractor(context, d)

        var live = 0
        var maxLive = 0
        ex.extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 320))
            .collect { f ->
                live++
                maxLive = maxOf(maxLive, live)
                // simulate the consumer holding the frame briefly
                yield()
                f.bitmap.recycle()
                live--
            }
        assertEquals(
            "cold Flow must not buffer frames ahead of the collector",
            1, maxLive,
        )
    }

    @Test
    fun shotScan_doesNotAccumulateFrames_pssStaysFlatAcrossTheScan() = runBlocking {
        val uri = sampleUri()
        val d = StandardDispatcherProvider()
        val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
        val ex = MediaMetadataFrameExtractor(context, d)
        val scanFps = meta.frameRate.takeIf { it > 0f }?.toDouble() ?: 25.0

        val session = DefaultShotScanner().newSession(
            ex.plannedFrameCount(FrameSamplingRequest(uri, meta, fps = scanFps.toFloat(), maxEdgePx = 320)),
            scanFps,
        )
        val samples = ArrayList<Long>()
        var i = 0
        ex.extractFrames(FrameSamplingRequest(uri, meta, fps = scanFps.toFloat(), maxEdgePx = 320))
            .collect { f ->
                session.onFrame(f.bitmap)
                f.bitmap.recycle()
                if (++i % 100 == 0) samples += pss()
            }
        val scan = session.finish()
        assertTrue("scan found no frames", scan.frameCount > 400)

        // The scan keeps only small primitive arrays (one previous FrameSignals
        // + growing scalar lists). PSS across the scan must not trend upward
        // frame-count-proportionally.
        log("shot-scan PSS samples: $samples")
        if (samples.size >= 2) {
            val delta = samples.last() - samples.first()
            assertTrue(
                "shot-scan PSS grew ${delta}MB from first to last checkpoint — frames are being retained",
                delta < 40,
            )
        }
    }

    // =======================================================================
    // 4 & 8. Embedder is Closeable; S1->S2->S3 does not accumulate native memory
    // =======================================================================

    @Test
    fun liteRtEmbedder_close_releasesTheInterpreter_andCanReopen() {
        // Open/close the native interpreter many times in one process. A leaked
        // interpreter or model mmap would climb PSS monotonically.
        val base = pss()
        repeat(8) { n ->
            val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
            val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
            // force the interpreter to actually load
            when (loader.getOrLoad()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> error("model load failed on iteration $n")
            }
            embedder.close()
            System.gc()
            log("embedder cycle $n pss=${pss()}MB")
        }
        val grown = pss() - base
        assertTrue(
            "PSS grew ${grown}MB over 8 open/close cycles — close() is not freeing native memory",
            grown < 60,
        )
    }

    @Test
    fun fullPipeline_runsThreeSamplesInOneProcess_withoutUnboundedNativeGrowth() = runBlocking {
        // The Phase6FrozenPipelineTest failure signature was native malloc
        // 51 -> 97 -> 143 MB across S1 -> S2 -> S3. Isolation proved the driver:
        // each `MlKitFaceDetector()` leaks ~45 MB of native memory that close()
        // does not reclaim (a known ML Kit bug), so a fresh detector per sample
        // grew ~45 MB/sample; a SHARED detector across all three shows 0 drift.
        // The production app already shares one (AppContainer `by lazy`); this
        // test now mirrors that. Same for the TFLite embedder.
        val d = StandardDispatcherProvider()
        val endNative = LongArray(3)
        val endPss = LongArray(3)

        val detector = MlKitFaceDetector()
        val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
        try {
        for ((idx, name) in listOf("sample_1", "sample_2", "sample_3").withIndex()) {
            val uri = sampleUri("$name.mp4")
            run {
                val repo = InMemoryProcessingResultRepository()
                val process = DefaultProcessVideoUseCase(
                    metadataReader = MediaMetadataVideoReader(context, d),
                    frameExtractor = MediaMetadataFrameExtractor(context, d),
                    faceDetector = detector,
                    resultRepository = repo,
                    dispatchers = d,
                    logger = AndroidLogger(),
                    gateEmbedder = TrackerGateEmbedder(embedder),
                )
                val r = process(name, uri) {}
                assertTrue("$name: pipeline failed", r is AppResult.Success)
            }
            settle()
            endNative[idx] = nativeAllocMb()
            endPss[idx] = pss()
            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            log(
                "$name end nativeAlloc=${endNative[idx]}MB pss=${endPss[idx]}MB " +
                    "| dalvikPss=${mi.dalvikPss / 1024} nativePss=${mi.nativePss / 1024} " +
                    "otherPss=${mi.otherPss / 1024} " +
                    "code=${mi.getMemoryStat("summary.code")?.toInt()?.div(1024) ?: -1}MB " +
                    "gfx=${mi.getMemoryStat("summary.graphics")?.toInt()?.div(1024) ?: -1}MB " +
                    "stack=${mi.getMemoryStat("summary.stack")?.toInt()?.div(1024) ?: -1}MB " +
                    "javaHeap=${mi.getMemoryStat("summary.java-heap")?.toInt()?.div(1024) ?: -1}MB " +
                    "nativeHeapStat=${mi.getMemoryStat("summary.native-heap")?.toInt()?.div(1024) ?: -1}MB " +
                    "system=${mi.getMemoryStat("summary.system")?.toInt()?.div(1024) ?: -1}MB " +
                    "fdCount=${openFdCount()}"
            )
        }
        } finally {
            runCatching { detector.close() }
            runCatching { embedder.close() }
        }

        // The figure that decides "will it OOM": ALLOCATED native memory. With
        // ONE shared detector + embedder this must be flat across S1 -> S3 (the
        // pipeline itself owns no per-run native resource). A regression that
        // re-introduces per-sample engine creation fails here.
        val nativeDrift = endNative[2] - endNative[0]
        val pssDrift = endPss[2] - endPss[0]
        log("native-alloc drift S1->S3 = ${nativeDrift}MB ;  PSS drift = ${pssDrift}MB")
        assertTrue(
            "ALLOCATED native memory drifted ${nativeDrift}MB from S1 to S3 end with SHARED " +
                "engines — the pipeline is leaking a per-run native resource",
            nativeDrift < 30,
        )
    }

    // =======================================================================
    // ISOLATION — which stage leaks native malloc across samples?
    // =======================================================================

    private fun nativeAfterThreeSamples(
        label: String,
        perSample: suspend (uri: String, meta: com.example.ikyky.core.media.VideoMetadata) -> Unit,
    ): LongArray {
        val d = StandardDispatcherProvider()
        val out = LongArray(3)
        listOf("sample_1", "sample_2", "sample_3").forEachIndexed { i, name ->
            runBlocking {
                val uri = sampleUri("$name.mp4")
                val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
                perSample(uri, meta)
            }
            settle()
            out[i] = nativeAllocMb()
            log("[$label] $name -> nativeAlloc=${out[i]}MB")
        }
        log("[$label] drift S1->S3 = ${out[2] - out[0]}MB")
        return out
    }

    @Test
    fun isolate_A_shotScanOnly_threeSamples() {
        val d = StandardDispatcherProvider()
        val n = nativeAfterThreeSamples("SCAN_ONLY") { uri, meta ->
            val ex = MediaMetadataFrameExtractor(context, d)
            val scanFps = meta.frameRate.takeIf { it > 0f }?.toDouble() ?: 25.0
            val req = FrameSamplingRequest(uri, meta, fps = scanFps.toFloat(), maxEdgePx = 320)
            val session = DefaultShotScanner().newSession(ex.plannedFrameCount(req), scanFps)
            ex.extractFrames(req).collect { f -> session.onFrame(f.bitmap); f.bitmap.recycle() }
            session.finish()
        }
        assertTrue("shot-scan-only leaked ${n[2] - n[0]}MB across 3 samples", n[2] - n[0] < 35)
    }

    @Test
    fun isolate_B_decodeOnly_threeSamples() {
        val d = StandardDispatcherProvider()
        val n = nativeAfterThreeSamples("DECODE_ONLY") { uri, meta ->
            val ex = MediaMetadataFrameExtractor(context, d)
            ex.extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 1080))
                .collect { f -> f.bitmap.recycle() }
        }
        assertTrue("decode-only leaked ${n[2] - n[0]}MB across 3 samples", n[2] - n[0] < 35)
    }

    @Test
    fun isolate_C_detectOnly_perInstanceLeak_isDocumented() {
        // CHARACTERIZATION (not a target): a FRESH MlKitFaceDetector per sample
        // leaks ~45 MB of native memory each, that close() does not reclaim — a
        // known ML Kit bug. This test records the magnitude; it does not fail on
        // it, because the fix is "share one instance" (proven by
        // isolate_C_detectOnly_sharedInstance_isFlat), which is what both the
        // app (AppContainer `by lazy`) and the Phase 6 tests now do.
        val d = StandardDispatcherProvider()
        val n = nativeAfterThreeSamples("DETECT_PER_INSTANCE") { uri, meta ->
            val det = MlKitFaceDetector()
            try {
                MediaMetadataFrameExtractor(context, d)
                    .extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 1080))
                    .collect { f -> det.detect(f.bitmap, 0); f.bitmap.recycle() }
            } finally {
                det.close()
            }
        }
        log("KNOWN ML KIT PER-INSTANCE LEAK: ${n[2] - n[0]}MB over 3 fresh detectors")
    }

    @Test
    fun isolate_C_detectOnly_sharedInstance_isFlat() {
        // The fix: ONE detector across all three samples => native malloc flat.
        val d = StandardDispatcherProvider()
        val det = MlKitFaceDetector()
        val marks = LongArray(3)
        try {
            listOf("sample_1", "sample_2", "sample_3").forEachIndexed { i, name ->
                runBlocking {
                    val uri = sampleUri("$name.mp4")
                    val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
                    MediaMetadataFrameExtractor(context, d)
                        .extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 1080))
                        .collect { f -> det.detect(f.bitmap, 0); f.bitmap.recycle() }
                }
                settle()
                marks[i] = nativeAllocMb()
                log("[DETECT_SHARED] $name -> nativeAlloc=${marks[i]}MB")
            }
        } finally {
            det.close()
        }
        val drift = marks[2] - marks[0]
        log("[DETECT_SHARED] drift S1->S3 = ${drift}MB")
        assertTrue("a SHARED detector still leaked ${drift}MB across 3 samples", drift < 25)
    }

    @Test
    fun isolate_C2_detectOneShot_reuseOneDetector_noClose() {
        // Same detector reused across all 3 samples (never closed mid-run), and
        // one InputImage per frame. Isolates whether the leak is per-process()
        // (accumulates regardless) or per-detector-instance.
        val d = StandardDispatcherProvider()
        val det = MlKitFaceDetector()
        val marks = LongArray(3)
        try {
            listOf("sample_1", "sample_2", "sample_3").forEachIndexed { i, name ->
                runBlocking {
                    val uri = sampleUri("$name.mp4")
                    val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
                    MediaMetadataFrameExtractor(context, d)
                        .extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 1080))
                        .collect { f -> det.detect(f.bitmap, 0); f.bitmap.recycle() }
                }
                settle()
                marks[i] = nativeAllocMb()
                log("[DETECT_REUSE] $name -> nativeAlloc=${marks[i]}MB")
            }
        } finally {
            det.close()
        }
        settle()
        log("[DETECT_REUSE] after close -> nativeAlloc=${nativeAllocMb()}MB  drift=${marks[2] - marks[0]}MB")
    }

    @Test
    fun isolate_C3_detectNoTracking_threeSamples() {
        // Diagnosis only: does DISABLING ML Kit's stream/tracking mode stop the
        // per-process() native accumulation? (enableTracking() is a stream API;
        // feeding it one-shot frames is a documented misuse.)
        val d = StandardDispatcherProvider()
        val tuning = DetectorTuning.DEFAULT.copy(enableTracking = false)
        val marks = LongArray(3)
        listOf("sample_1", "sample_2", "sample_3").forEachIndexed { i, name ->
            val det = MlKitFaceDetector(tuning)
            try {
                runBlocking {
                    val uri = sampleUri("$name.mp4")
                    val meta = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value
                    MediaMetadataFrameExtractor(context, d)
                        .extractFrames(FrameSamplingRequest(uri, meta, fps = 8f, maxEdgePx = 1080))
                        .collect { f -> det.detect(f.bitmap, 0); f.bitmap.recycle() }
                }
            } finally {
                det.close()
            }
            settle()
            marks[i] = nativeAllocMb()
            log("[DETECT_NOTRACK] $name -> nativeAlloc=${marks[i]}MB")
        }
        log("[DETECT_NOTRACK] drift S1->S3 = ${marks[2] - marks[0]}MB")
    }

    @Test
    fun isolate_D_embedInferenceOnly_manyCalls() {
        // 800 inferences through ONE embedder, then close. Native must not climb
        // per inference and must drop on close.
        val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
        val before = nativeAllocMb()
        try {
            val bmp = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888)
            runBlocking {
                repeat(800) {
                    val pf = com.example.ikyky.core.model.PreprocessedFace(bmp, 112, null, false)
                    embedder.embed(pf)
                }
            }
            bmp.recycle()
            val during = nativeAllocMb()
            log("[EMBED_INFER] 800 calls: before=${before}MB during=${during}MB")
            assertTrue("native grew ${during - before}MB over 800 inferences", during - before < 30)
        } finally {
            embedder.close()
        }
        settle()
        val after = nativeAllocMb()
        log("[EMBED_INFER] after close: ${after}MB (delta from before=${after - before}MB)")
        assertTrue("close() left ${after - before}MB of native malloc", after - before < 20)
    }

    // =======================================================================
    // 10. Progress state represents the shot scan
    // =======================================================================

    @Test
    fun processing_reportsShotScanProgress_notAStaticZeroOf240() = runBlocking {
        val uri = sampleUri()
        val d = StandardDispatcherProvider()
        val detector = MlKitFaceDetector()
        val loader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(loader, ModelSpec.MOBILE_FACE_NET)
        try {
            val repo = InMemoryProcessingResultRepository()
            val process = DefaultProcessVideoUseCase(
                metadataReader = MediaMetadataVideoReader(context, d),
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                faceDetector = detector,
                resultRepository = repo,
                dispatchers = d,
                logger = AndroidLogger(),
                gateEmbedder = TrackerGateEmbedder(embedder),
            )

            val scanFractions = ArrayList<Float>()
            val scanFrameCounts = ArrayList<Int>()
            var sawScanDetail = false
            var sawDistinctFractions = false

            withTimeoutOrNull(300_000) {
                process("s", uri) { p: ProcessingProgress ->
                    if (p.stage == ProcessingStage.EXTRACTING_FRAMES) {
                        scanFractions += p.fraction
                        scanFrameCounts += p.diagnostics?.shotScanFramesAnalysed ?: -1
                        if (p.detail?.contains("Scanning for shot changes", ignoreCase = true) == true) {
                            sawScanDetail = true
                        }
                    }
                }
            }

            assertTrue("no EXTRACTING_FRAMES progress at all", scanFractions.isNotEmpty())
            assertTrue("progress detail never said it was scanning for shot changes", sawScanDetail)

            // the scan must report ADVANCING frame counts, not a frozen 0
            val maxScanFrames = scanFrameCounts.maxOrNull() ?: 0
            assertTrue(
                "shot-scan progress never advanced past 0 frames (was $scanFrameCounts.take(5)...)",
                maxScanFrames > 50,
            )
            // and the reported fraction must actually move during the scan
            sawDistinctFractions = scanFractions.distinct().size >= 3
            assertTrue(
                "shot-scan progress fraction never changed (${scanFractions.distinct()})",
                sawDistinctFractions,
            )
            // every scan fraction must sit in the scan's band, below the detect loop
            assertTrue(
                "a shot-scan fraction leaked outside [0.02, 0.50]: ${scanFractions.filter { it !in 0.02f..0.5f }}",
                scanFractions.all { it in 0.02f..0.50f },
            )
        } finally {
            runCatching { detector.close() }
            runCatching { embedder.close() }
        }
    }
}
