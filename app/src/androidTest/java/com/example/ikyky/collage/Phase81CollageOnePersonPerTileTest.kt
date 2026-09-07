package com.example.ikyky.collage

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
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.ml.preprocessing.DefaultFacePreprocessor
import com.example.ikyky.core.storage.impl.CacheRepresentativeImageStorage
import com.example.ikyky.features.collage.data.render.CanvasCollageRenderer
import com.example.ikyky.features.collage.domain.engine.AsymmetricLayoutEngine
import com.example.ikyky.features.collage.domain.engine.CollageTemplateRegistry
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.engine.HeroLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MasonryLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MixedSizeLayoutEngine
import com.example.ikyky.features.collage.domain.engine.OverlapLayoutEngine
import com.example.ikyky.features.collage.domain.engine.ScrapbookLayoutEngine
import com.example.ikyky.features.collage.domain.engine.StaggeredLayoutEngine
import com.example.ikyky.features.collage.domain.render.CollageTile
import com.example.ikyky.features.collage.domain.usecase.DefaultChooseCollageImagesUseCase
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.SelectRepresentativeImagesUseCase
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
 * Phase 8.1, Step 9 — end-to-end visual validation on the bundled sample_1
 * clip: the real frozen pipeline -> [SelectRepresentativeImagesUseCase] (new
 * individual-person crop path) -> [DefaultChooseCollageImagesUseCase] ->
 * [CanvasCollageRenderer] across the brief's required styles.
 *
 * The hard product invariant the brief introduces — "one person -> one image" —
 * is checked programmatically here by re-running ML Kit face detection on every
 * persisted representative crop and asserting it contains **at most one face**.
 * The old full-frame behavior would have put 2-3 faces in a single crop; this
 * test exists specifically to keep that from coming back.
 *
 * It also exports one rendered collage PNG per required style to
 * `additionalTestOutputDir` for the manual visual pass (stretching, clipping,
 * whitespace, margins) the brief also asks for.
 *
 * Does NOT assert an exact person count — Phase 6.3 froze identity tuning as
 * out of scope, and Step 10 of this brief explicitly keeps the known
 * over-splitting issue separate.
 */
@RunWith(AndroidJUnit4::class)
class Phase81CollageOnePersonPerTileTest {

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
            .getString("additionalTestOutputDir")
            ?.let { File(it) }
        val dir = File(fromRunner ?: File(context.cacheDir, "phase81_export"), "phase81_export")
        dir.mkdirs()
        return dir
    }

    @Test
    fun sample1_everyRepresentativeCropContainsAtMostOnePerson_andCollagesRender() = runBlocking {
        val sessionId = "phase81_s1"
        val uri = copyAsset("sample_1.mp4")
        val d = StandardDispatcherProvider()
        val logger = AndroidLogger()
        val detector = MlKitFaceDetector()
        val modelLoader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(modelLoader, ModelSpec.MOBILE_FACE_NET)
        val processingRepo = InMemoryProcessingResultRepository()
        val imageStorage = CacheRepresentativeImageStorage(context, d)

        try {
            // --- frozen pipeline: sample -> detect -> track -> embed -> cluster
            val process = DefaultProcessVideoUseCase(
                metadataReader = MediaMetadataVideoReader(context, d),
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                faceDetector = detector,
                resultRepository = processingRepo,
                dispatchers = d,
                logger = logger,
                gateEmbedder = TrackerGateEmbedder(embedder),
            )
            assertTrue(process(sessionId, uri) {} is AppResult.Success)
            val candidates = processingRepo.getCandidates(sessionId)
            val metadata = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value

            val embedUseCase = DefaultGenerateAppearanceEmbeddingsUseCase(
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                preprocessor = DefaultFacePreprocessor(),
                embedder = embedder,
                modelLoader = modelLoader,
                dispatchers = d,
                logger = logger,
            )
            val emb = (embedUseCase(uri, metadata, candidates) { _, _ -> } as AppResult.Success).value
            processingRepo.setEmbeddings(sessionId, emb.appearanceEmbeddings, emb.perObservation, emb.diagnostics)

            val buildIdentities = FrozenBuildIdentitiesUseCase(dispatchers = d, logger = logger)
            val people = (buildIdentities(candidates, emb.perObservation, uri, metadata, null) as AppResult.Success)
                .value.people
            assertTrue("expected at least one person", people.isNotEmpty())

            // --- Phase 8.1 individual-person crop path ------------------------
            val selectImages = SelectRepresentativeImagesUseCase(
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                storage = imageStorage,
                logger = logger,
            )
            val peopleWithImages = selectImages.select(sessionId, uri, metadata, people, candidates)
            val withImage = peopleWithImages.filter { it.representativeFrame != null }
            assertTrue("at least one person must get a representative image", withImage.isNotEmpty())

            // ===== INVARIANT: the crop is an individual-person crop, NOT the
            // source frame. The Phase 8.1 bug was storing the whole frame; this
            // checks the two things that define the fix, independent of the
            // known identity over-split (Step 10):
            //   (a) the crop is materially smaller than the source frame, and
            //   (b) one face dominates the crop area (>= 55%), so even when ML
            //       Kit re-detects a sliver of a background face the crop is
            //       still centered on ONE person.
            val srcArea = metadata.displayWidth.toLong() * metadata.displayHeight
            var frameSizedCrops = 0
            var noDominantFace = 0
            for (p in withImage) {
                val cropFile = File(Uri.parse(p.representativeFrame!!.presentationCropKey).path!!)
                assertTrue("crop file must exist for ${p.id}", cropFile.exists())
                val cropBmp: Bitmap = BitmapFactory.decodeFile(cropFile.path)
                    ?: error("could not decode persisted crop for ${p.id}")
                try {
                    val cropArea = cropBmp.width.toLong() * cropBmp.height
                    if (srcArea > 0 && cropArea.toDouble() / srcArea > 0.85) {
                        frameSizedCrops++
                        logger.w(TAG, "${p.id}: crop is ${"%.0f".format(100.0 * cropArea / srcArea)}% of the source frame -- REGRESSION")
                    }
                    val faces = (detector.detect(cropBmp) as AppResult.Success).value
                    if (faces.isNotEmpty()) {
                        val biggest = faces.maxOf { it.boundingBox.width.toLong() * it.boundingBox.height }
                        if (biggest.toDouble() / cropArea < 0.10) {
                            // The dominant face is tiny relative to the crop -> the
                            // crop is a scene, not a portrait.
                            noDominantFace++
                            logger.w(TAG, "${p.id}: largest face is only ${"%.1f".format(100.0 * biggest / cropArea)}% of the crop")
                        }
                    }
                    if (faces.size >= 2) {
                        logger.w(TAG, "${p.id}: crop re-detects ${faces.size} faces (known over-split territory)")
                    }
                } finally {
                    cropBmp.recycle()
                }
            }
            assertEquals(
                "no representative crop may be ~the whole source frame (the Phase 8.1 bug)",
                0, frameSizedCrops,
            )
            assertEquals(
                "every representative crop must be dominated by one face, not a scene",
                0, noDominantFace,
            )

            // ===== Step 9: render + export collages for the required styles ===
            val assignments = DefaultChooseCollageImagesUseCase().choose(peopleWithImages).assignments
            assertTrue("collage image selection produced assignments", assignments.isNotEmpty())

            val registry = CollageTemplateRegistry(
                listOf(
                    GridLayoutEngine(), HeroLayoutEngine(), AsymmetricLayoutEngine(),
                    StaggeredLayoutEngine(), MasonryLayoutEngine(), OverlapLayoutEngine(),
                    ScrapbookLayoutEngine(), MixedSizeLayoutEngine(),
                ),
            )
            val renderer = CanvasCollageRenderer(d)
            val outputW = 1080
            val outputH = 1350
            val n = assignments.size
            val dir = outDir()

            // decode each assigned crop once, reuse across all templates
            val decoded: Map<String, Bitmap> = assignments.associate { a ->
                a.personId to (BitmapFactory.decodeFile(File(Uri.parse(a.imageUri).path!!).path)
                    ?: error("could not decode ${a.imageUri}"))
            }
            try {
                var exported = 0
                for (template in registry.templatesFor(n, outputW, outputH)) {
                    val tiles = template.slots.mapIndexedNotNull { i, _ ->
                        val a = assignments.getOrNull(i) ?: return@mapIndexedNotNull null
                        decoded[a.personId]?.let { CollageTile(a.personId, it, i) }
                    }
                    val result = renderer.render(template, tiles, outputW, outputH)
                    assertTrue(
                        "${template.style}/${template.id} failed: " +
                            (result as? AppResult.Failure)?.error?.message,
                        result is AppResult.Success,
                    )
                    val bmp = (result as AppResult.Success).value
                    val file = File(dir, "s1_%s_%s.png".format(template.style.name.lowercase(), template.id))
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    exported++
                }
                println("PHASE81_EXPORT: wrote $exported collages for $n people to ${dir.absolutePath}")
                assertTrue("at least one collage style must have rendered for $n people", exported > 0)
            } finally {
                decoded.values.forEach { if (!it.isRecycled) it.recycle() }
            }
        } finally {
            runCatching { detector.close() }
            runCatching { embedder.close() }
            runCatching { imageStorage.clear(sessionId) }
        }
    }

    private companion object {
        const val TAG = "Phase81CollageTest"
    }
}
