package com.example.ikyky.audit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.logging.AndroidLogger
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.MediaMetadataVideoReader
import com.example.ikyky.core.media.SeekOption
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.ml.preprocessing.DefaultFacePreprocessor
import com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.storage.impl.CacheRepresentativeImageStorage
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.SelectRepresentativeImagesUseCase
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.TrackerGateEmbedder
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PHASE 8.2 — face-asset quality audit. **Diagnostic only, no assertions on the
 * pipeline's behavior** (it always passes). Runs the frozen pipeline on
 * sample_1/2/3, then for every `Person.representativeFrame`:
 *
 *  - traces it back to person / appearance / observation / timestamp / box /
 *    crop rectangle;
 *  - re-decodes the source frame and re-runs ML Kit on it to count faces in
 *    that frame and how many intersect the proposed crop;
 *  - re-runs ML Kit on the persisted crop bitmap;
 *  - classifies the crop: single-face / multi-face / zero-face / partial (box
 *    against frame edge or tiny) / possible-back-of-head (no eye landmarks).
 *
 * Everything is written to `additionalTestOutputDir/phase82_audit/<sample>.txt`
 * and echoed to logcat under tag AUDIT82. Read those files for the report.
 */
@RunWith(AndroidJUnit4::class)
class Phase82FaceAssetAuditTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): String {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instr.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(out).toString()
    }

    private fun outDir(): File {
        val fromRunner = InstrumentationRegistry.getArguments()
            .getString("additionalTestOutputDir")?.let { File(it) }
        val dir = File(fromRunner ?: File(context.cacheDir, "phase82_audit"), "phase82_audit")
        dir.mkdirs()
        return dir
    }

    @Test
    fun auditSample1() = audit("sample_1.mp4")

    @Test
    fun auditSample2() = audit("sample_2.mp4")

    @Test
    fun auditSample3() = audit("sample_3.mp4")

    private fun audit(assetName: String) = runBlocking {
        val tag = assetName.removeSuffix(".mp4")
        val sessionId = "audit82_$tag"
        val uri = copyAsset(assetName)
        val d = StandardDispatcherProvider()
        val logger = AndroidLogger()
        val detector = MlKitFaceDetector()
        val modelLoader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(modelLoader, ModelSpec.MOBILE_FACE_NET)
        val processingRepo = InMemoryProcessingResultRepository()
        val imageStorage = CacheRepresentativeImageStorage(context, d)
        val frameExtractor = MediaMetadataFrameExtractor(context, d)
        val sb = StringBuilder()
        fun line(s: String) { sb.appendLine(s); logger.i(TAG, "[$tag] $s") }

        try {
            line("================ AUDIT $assetName ================")

            // ---- frozen pipeline -------------------------------------------
            val process = DefaultProcessVideoUseCase(
                metadataReader = MediaMetadataVideoReader(context, d),
                frameExtractor = frameExtractor,
                faceDetector = detector,
                resultRepository = processingRepo,
                dispatchers = d,
                logger = logger,
                gateEmbedder = TrackerGateEmbedder(embedder),
            )
            assertTrue(process(sessionId, uri) {} is AppResult.Success)
            val candidates = processingRepo.getCandidates(sessionId)
            val diag = processingRepo.getDiagnostics(sessionId)
            val metadata = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value

            line("frame ${metadata.displayWidth}x${metadata.displayHeight} dur=${metadata.durationMs}ms fps=${metadata.frameRate}")
            line("PIPELINE DIAG: $diag")
            line("appearance candidates: ${candidates.size}")
            for (c in candidates) {
                line("  ${c.id} tracklet=${c.trackletId} obs=${c.observationCount} " +
                    "span=[${c.startTimestampMs}..${c.endTimestampMs}]ms bestQ=${"%.3f".format(c.bestQuality)} " +
                    "bestFrame=${c.bestFrameIndex}@${c.bestFrameTimestampMs}ms bestBox=${c.bestFrameBox.short()}")
            }

            val embUseCase = DefaultGenerateAppearanceEmbeddingsUseCase(
                frameExtractor = frameExtractor,
                preprocessor = DefaultFacePreprocessor(),
                embedder = embedder,
                modelLoader = modelLoader,
                dispatchers = d,
                logger = logger,
            )
            val emb = (embUseCase(uri, metadata, candidates) { _, _ -> } as AppResult.Success).value
            processingRepo.setEmbeddings(sessionId, emb.appearanceEmbeddings, emb.perObservation, emb.diagnostics)

            val buildIdentities = FrozenBuildIdentitiesUseCase(dispatchers = d, logger = logger)
            val people = (buildIdentities(candidates, emb.perObservation, uri, metadata, null) as AppResult.Success)
                .value.people
            line("")
            line("IDENTITIES (frozen clustering): ${people.size}")
            for (p in people) line("  ${p.id} appearances=${p.appearanceIds}")

            // ---- PER-OBSERVATION GEOMETRY DISTRIBUTION (for C1/C2 thresholds) ----
            line("")
            line("---- PER-OBSERVATION GEOMETRY (all observations across all candidates) ----")
            val shortFrame = minOf(metadata.displayWidth, metadata.displayHeight)
            val frameArea = metadata.displayWidth.toLong() * metadata.displayHeight
            data class G(val ffMax: Float, val areaFrac: Float, val minEdge: Int, val cornerEdges: Int, val nLm: Int, val lmSpanFrac: Float)
            val gAll = ArrayList<G>()
            for (c in candidates) for (o in c.observations) {
                val b = o.canonicalBox
                val ffMax = maxOf(b.width, b.height).toFloat() / shortFrame
                val areaFrac = (b.width.toLong() * b.height).toFloat() / frameArea
                val edges = intArrayOf(b.left, b.top, metadata.displayWidth - b.right, metadata.displayHeight - b.bottom)
                val minEdge = edges.min()
                val cornerEdges = edges.count { it <= 48 }
                val lm = o.landmarks
                val lmSpanFrac = if (lm.size >= 2) {
                    val xs = lm.map { it.x }; val ys = lm.map { it.y }
                    val sx = (xs.max() - xs.min()) / b.width.coerceAtLeast(1)
                    val sy = (ys.max() - ys.min()) / b.height.coerceAtLeast(1)
                    minOf(sx, sy)
                } else 0f
                gAll += G(ffMax, areaFrac, minEdge, cornerEdges, lm.size, lmSpanFrac)
            }
            fun pct(xs: List<Float>, p: Double) = xs.sorted().let { it[(it.size * p).toInt().coerceIn(0, it.size - 1)] }
            val ffs = gAll.map { it.ffMax }
            val afs = gAll.map { it.areaFrac }
            line("n observations = ${gAll.size}")
            line("ffMax  p10=${"%.2f".format(pct(ffs,0.1))} p25=${"%.2f".format(pct(ffs,0.25))} p50=${"%.2f".format(pct(ffs,0.5))} p75=${"%.2f".format(pct(ffs,0.75))} p90=${"%.2f".format(pct(ffs,0.9))} p95=${"%.2f".format(pct(ffs,0.95))} max=${"%.2f".format(ffs.max())}")
            line("areaFr p10=${"%.2f".format(pct(afs,0.1))} p25=${"%.2f".format(pct(afs,0.25))} p50=${"%.2f".format(pct(afs,0.5))} p75=${"%.2f".format(pct(afs,0.75))} p90=${"%.2f".format(pct(afs,0.9))} p95=${"%.2f".format(pct(afs,0.95))} max=${"%.2f".format(afs.max())}")
            line("observations with ffMax>1.05 (C1 gate): ${gAll.count { it.ffMax > 1.05f }}")
            line("observations with areaFrac>0.62 (C1 gate): ${gAll.count { it.areaFrac > 0.62f }}")
            line("observations with ffMax>0.80 : ${gAll.count { it.ffMax > 0.80f }}")
            line("observations pinned to a corner (>=2 edges<=40px): ${gAll.count { it.cornerEdges >= 2 }}")
            line("  ...AND small (ffMax<0.55) AND lmSpan<0.45 (C2 gate): ${gAll.count { it.cornerEdges >= 2 && it.ffMax < 0.55f && it.lmSpanFrac < 0.45f }}")
            line("observations with <5 landmarks: ${gAll.count { it.nLm < 5 }}")
            line("observations with 0 landmarks : ${gAll.count { it.nLm == 0 }}")
            // blur distribution
            val blurs = candidates.flatMap { it.observations }.map { it.blurVariance }.filter { !it.isNaN() }.sorted()
            if (blurs.isNotEmpty()) {
                fun bp(p: Double) = blurs[(blurs.size * p).toInt().coerceIn(0, blurs.size - 1)]
                line("blurVariance (n=${blurs.size}) p05=${"%.1f".format(bp(0.05))} p10=${"%.1f".format(bp(0.10))} p25=${"%.1f".format(bp(0.25))} p50=${"%.1f".format(bp(0.50))} p75=${"%.1f".format(bp(0.75))} p90=${"%.1f".format(bp(0.90))} max=${"%.1f".format(blurs.max())}")
                line("  observations with blur<15 (C3 gate): ${blurs.count { it < 15.0 }}")
            } else line("blurVariance: none measured")
            // head pose distribution
            val yaws = candidates.flatMap { it.observations }.mapNotNull { it.headPose?.eulerY?.let { y -> kotlin.math.abs(y) } }.sorted()
            if (yaws.isNotEmpty()) {
                fun yp(p: Double) = yaws[(yaws.size * p).toInt().coerceIn(0, yaws.size - 1)]
                line("|yaw|deg (n=${yaws.size}) p50=${"%.1f".format(yp(0.50))} p75=${"%.1f".format(yp(0.75))} p90=${"%.1f".format(yp(0.90))} max=${"%.1f".format(yaws.max())}")
                line("  observations with |yaw|>32 (C3 gate): ${yaws.count { it > 32f }}")
            }

            // ---- MULTI-FACE FRAME AUDIT ----------------------------------
            // Rebuild the per-frame observation view from the candidates' refs.
            line("")
            line("---- MULTI-FACE FRAME AUDIT (frames with >= 2 observations across all candidates) ----")
            val obsByFrame = HashMap<Int, MutableList<Triple<String, String, BoundingBox>>>() // frame -> (appId, obsId, box)
            for (c in candidates) for (o in c.observations) {
                obsByFrame.getOrPut(o.frameIndex) { mutableListOf() }
                    .add(Triple(c.id, o.observationId, o.canonicalBox))
            }
            val multi = obsByFrame.filterValues { it.size >= 2 }.toSortedMap()
            line("multi-face frames: ${multi.size} (of ${obsByFrame.size} frames with any observation)")
            for ((frameIdx, list) in multi) {
                line("  frame $frameIdx: ${list.size} obs")
                for ((appId, obsId, box) in list) {
                    line("     appId=$appId obsId=$obsId box=${box.short()} area=${box.area}")
                }
            }

            // ---- REPRESENTATIVE CROP AUDIT (this + selection + re-detect) ----
            val selectImages = SelectRepresentativeImagesUseCase(
                frameExtractor = frameExtractor,
                storage = imageStorage,
                logger = logger,
            )
            val peopleWithImages = selectImages.select(sessionId, uri, metadata, people, candidates)

            line("")
            line("---- PHASE 8.2 EVALUATOR DIAGNOSTICS ----")
            val ed = selectImages.lastDiagnostics
            line("  peopleConsidered           = ${ed.peopleConsidered}")
            line("  observationsConsidered     = ${ed.observationsConsidered}")
            line("  rejectedByReason           = ${ed.rejectedByReason.mapKeys { it.key.name }}")
            line("  acceptedCandidates         = ${ed.acceptedCandidates}")
            line("  landmarkTightCrops         = ${ed.landmarkTightCrops}")
            line("  detectionBoxFallbacks      = ${ed.detectionBoxFallbacks}")
            line("  representativesUnavailable  = ${ed.representativesUnavailable}")
            line("  representativesPersisted    = ${ed.representativesPersisted}")

            line("")
            line("---- REPRESENTATIVE CROP AUDIT ----")
            var single = 0; var multiFace = 0; var zeroFace = 0; var partial = 0; var backOfHead = 0
            var total = 0
            val auditDir = File(outDir(), tag).apply { mkdirs() }
            for (p in peopleWithImages) {
                val rf = p.representativeFrame
                if (rf == null) { line("  ${p.id}: NO representativeFrame"); continue }
                total++
                val appId = rf.sourceObservationId
                val ts = rf.timestampMs
                val cand = candidates.firstOrNull { it.id == appId }
                // which observation did selection actually use? match on frameIndex.
                val obs = cand?.observations?.firstOrNull { it.frameIndex == rf.frameIndex }
                val obsBox = obs?.canonicalBox ?: cand?.bestFrameBox
                val obsLandmarks = obs?.landmarks ?: emptyList()

                // re-decode source frame at that timestamp (full-res working edge)
                val frame = frameExtractor.decodeFrameAt(uri, metadata, ts, 1080, SeekOption.CLOSEST)
                val srcFacesCanon: List<BoundingBox>
                val facesInFrame: Int
                if (frame != null) {
                    val det = (detector.detect(frame.bitmap) as AppResult.Success).value
                    facesInFrame = det.size
                    srcFacesCanon = det.map { frame.geometry.toCanonical(it.boundingBox) }
                    frame.bitmap.recycle()
                } else {
                    facesInFrame = -1
                    srcFacesCanon = emptyList()
                }

                // proposed crop rect (canonical), recomputed the way the use case does
                val cropRectCanon: BoundingBox? = obsBox?.let { box ->
                    val siblings = obsByFrame[rf.frameIndex].orEmpty()
                        .map { it.third }.filter { it != box }
                    val refW = (listOf(box) + siblings).maxOf { it.right } + 4096
                    val refH = (listOf(box) + siblings).maxOf { it.bottom } + 4096
                    PresentationFaceCropper.rectFor(box, refW, refH, siblings)
                }
                val facesIntersectingCrop = cropRectCanon?.let { rc ->
                    srcFacesCanon.count { it.iou(rc) > 0.02f || overlapFrac(it, rc) > 0.15f }
                } ?: -1

                // persisted crop bitmap: re-detect
                val cropFile = File(Uri.parse(rf.presentationCropKey).path!!)
                val cropBmp = BitmapFactory.decodeFile(cropFile.path)
                val cropFaces = if (cropBmp != null) (detector.detect(cropBmp) as AppResult.Success).value else emptyList()
                val cropW = cropBmp?.width ?: -1
                val cropH = cropBmp?.height ?: -1
                val cropArea = cropW.toLong() * cropH
                val srcArea = metadata.displayWidth.toLong() * metadata.displayHeight
                val cropFrac = if (srcArea > 0) cropArea.toDouble() / srcArea else -1.0
                val biggestFaceFracOfCrop = if (cropBmp != null && cropFaces.isNotEmpty())
                    cropFaces.maxOf { it.boundingBox.area.toDouble() } / cropArea else -1.0

                // classification
                val isFrameSized = cropFrac > 0.85
                val nearEdge = obsBox != null && (
                    obsBox.left <= 2 || obsBox.top <= 2 ||
                        obsBox.right >= metadata.displayWidth - 2 ||
                        obsBox.bottom >= metadata.displayHeight - 2)
                val faceFracOfFrame = obsBox?.let {
                    maxOf(it.width, it.height).toDouble() / minOf(metadata.displayWidth, metadata.displayHeight)
                } ?: -1.0
                val hasEyeLandmarks = obsLandmarks.any {
                    it.type.name == "LEFT_EYE" || it.type.name == "RIGHT_EYE"
                }
                val classifications = buildList {
                    when {
                        cropFaces.size >= 2 -> { add("MULTI_FACE"); multiFace++ }
                        cropFaces.isEmpty() -> { add("ZERO_FACE_ON_CROP"); zeroFace++ }
                        else -> { add("SINGLE_FACE"); single++ }
                    }
                    if (isFrameSized) add("FRAME_SIZED")
                    if (nearEdge) { add("BOX_AT_FRAME_EDGE"); partial++ }
                    if (faceFracOfFrame in 0.0..0.10) { add("TINY_FACE(<10%)"); if ("BOX_AT_FRAME_EDGE" !in this) partial++ }
                    if (obsLandmarks.isNotEmpty() && !hasEyeLandmarks) { add("NO_EYE_LANDMARKS(back-of-head?)"); backOfHead++ }
                    if (biggestFaceFracOfCrop in 0.0..0.08) add("FACE_TINY_IN_CROP")
                }

                line("")
                line("  PERSON ${p.id}")
                line("    appearanceId(sourceObservationId) = $appId   appearanceIds=${p.appearanceIds}")
                line("    representative frameIndex=${rf.frameIndex} timestamp=${ts}ms qualityScore=${"%.3f".format(rf.qualityScore)}")
                line("    observation box (canonical) = ${obsBox?.short()}  w=${obsBox?.width} h=${obsBox?.height}")
                line("    observation qualityScore=${obs?.qualityScore?.let { "%.3f".format(it) }} usable=${obs?.usable}")
                line("    landmarks present=${obsLandmarks.map { it.type.name }}")
                line("    face fraction of frame (longEdge/shortFrame) = ${"%.3f".format(faceFracOfFrame)}")
                line("    box at frame edge = $nearEdge")
                line("    SOURCE FRAME: faces detected = $facesInFrame ; boxes=${srcFacesCanon.map { it.short() }}")
                line("    proposed crop rect (canonical) = ${cropRectCanon?.short()}")
                line("    source faces intersecting crop rect = $facesIntersectingCrop")
                line("    PERSISTED CROP: ${cropW}x${cropH}  (= ${"%.1f".format(cropFrac * 100)}% of source frame area)")
                line("    re-detect on persisted crop: ${cropFaces.size} face(s) ; " +
                    "biggest face = ${"%.1f".format(biggestFaceFracOfCrop * 100)}% of crop area")
                line("    >>> CLASSIFICATION: $classifications")

                // save the crop next to the report for eyeballing
                if (cropBmp != null) {
                    File(auditDir, "${p.id}_${classifications.first()}.png").outputStream().use {
                        cropBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    cropBmp.recycle()
                }
            }

            line("")
            line("---- SUMMARY for $assetName ----")
            line("  total candidate crops      = $total")
            line("  single-face crops          = $single")
            line("  multi-face-containing crops= $multiFace")
            line("  zero-face crops            = $zeroFace")
            line("  partial-face crops (edge/tiny) = $partial")
            line("  possible back-of-head crops    = $backOfHead")
            line("  identities (frozen)           = ${people.size}")
            line("  multi-face frames in pipeline = ${multi.size}")
        } finally {
            File(outDir(), "$tag.txt").writeText(sb.toString())
            runCatching { detector.close() }
            runCatching { embedder.close() }
            runCatching { imageStorage.clear(sessionId) }
        }
    }

    private fun BoundingBox.short() = "[$left,$top,$right,$bottom]"

    private fun overlapFrac(a: BoundingBox, b: BoundingBox): Float {
        val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val inter = ix.toLong() * iy
        val aArea = a.area.toLong().coerceAtLeast(1)
        return inter.toFloat() / aArea
    }

    private companion object { const val TAG = "AUDIT82" }
}
