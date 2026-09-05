package com.example.ikyky.people

import android.content.Context
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
import com.example.ikyky.core.ml.preprocessing.SimilarityTransformFaceAligner
import com.example.ikyky.core.storage.impl.CacheRepresentativeImageStorage
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import com.example.ikyky.features.people.domain.usecase.PersonAppearancesUseCase
import com.example.ikyky.features.people.domain.usecase.SelectRepresentativeImagesUseCase
import com.example.ikyky.features.people.data.repository.InMemoryPeopleResultRepository
import com.example.ikyky.features.people.presentation.viewmodel.PeopleViewModel
import com.example.ikyky.features.people.presentation.viewmodel.PersonDetailViewModel
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.TrackerGateEmbedder
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 7 — end-to-end: real frozen pipeline output (Phase 2-4, unchanged from
 * Phase 6) -> representative-image selection -> [PeopleViewModel] /
 * [PersonDetailViewModel] state, on the bundled sample_1 clip.
 *
 * Structural assertions only (Steps 12.2-12.15 of the Phase 7 brief). Does NOT
 * assert an exact person count — Phase 6.3 explicitly froze that as
 * out-of-scope for tuning.
 */
@RunWith(AndroidJUnit4::class)
class Phase7PeopleProductLayerTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyAsset(name: String): String {
        val instr = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        instr.assets.open(name).use { i -> out.outputStream().use { i.copyTo(it) } }
        return android.net.Uri.fromFile(out).toString()
    }

    @Test
    fun sample1_producesPeopleWithRepresentativeImages_andConsistentAppearanceJoin() = runBlocking {
        val sessionId = "phase7_s1"
        val uri = copyAsset("sample_1.mp4")
        val d = StandardDispatcherProvider()
        val logger = AndroidLogger()
        val detector = MlKitFaceDetector()
        val modelLoader = EmbeddingModelLoader(context, ModelSpec.MOBILE_FACE_NET)
        val embedder = LiteRtFaceEmbedder(modelLoader, ModelSpec.MOBILE_FACE_NET)
        val processingRepo: ProcessingResultRepository = InMemoryProcessingResultRepository()
        val peopleRepo: PeopleResultRepository = InMemoryPeopleResultRepository()
        val imageStorage = CacheRepresentativeImageStorage(context, d)

        try {
            // --- Phase 2: sample -> detect -> track (frozen, unmodified) ------
            val process = DefaultProcessVideoUseCase(
                metadataReader = MediaMetadataVideoReader(context, d),
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                faceDetector = detector,
                resultRepository = processingRepo,
                dispatchers = d,
                logger = logger,
                gateEmbedder = TrackerGateEmbedder(embedder),
            )
            val outcome = process(sessionId, uri) {}
            assertTrue(outcome is AppResult.Success)

            val candidates = processingRepo.getCandidates(sessionId)
            val metadata = (MediaMetadataVideoReader(context, d).read(uri) as AppResult.Success).value

            // --- Phase 3: embeddings (frozen) --------------------------------
            val embedUseCase = DefaultGenerateAppearanceEmbeddingsUseCase(
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                preprocessor = DefaultFacePreprocessor(),
                embedder = embedder,
                modelLoader = modelLoader,
                dispatchers = d,
                logger = logger,
            )
            val embResult = embedUseCase(uri, metadata, candidates) { _, _ -> }
            assertTrue(embResult is AppResult.Success)
            val emb = (embResult as AppResult.Success).value
            processingRepo.setEmbeddings(sessionId, emb.appearanceEmbeddings, emb.perObservation, emb.diagnostics)

            // --- Phase 4: frozen clustering -----------------------------------
            val buildIdentities = FrozenBuildIdentitiesUseCase(dispatchers = d, logger = logger)
            val identityResult = buildIdentities(candidates, emb.perObservation, uri, metadata, null)
            assertTrue(identityResult is AppResult.Success)
            val rawPeople = (identityResult as AppResult.Success).value.people
            assertTrue("expected at least one person", rawPeople.isNotEmpty())

            // ---- STEP 12.1: no duplicate appearance ids across people --------
            val allAppearanceIds = rawPeople.flatMap { it.appearanceIds }
            assertEquals(
                "clustering must partition appearances -- no id may belong to two people",
                allAppearanceIds.distinct().size, allAppearanceIds.size,
            )

            // --- Phase 7: representative image selection ----------------------
            val selectImages = SelectRepresentativeImagesUseCase(
                frameExtractor = MediaMetadataFrameExtractor(context, d),
                aligner = SimilarityTransformFaceAligner(),
                storage = imageStorage,
                logger = logger,
            )
            val peopleWithImages = selectImages.select(sessionId, uri, metadata, rawPeople, candidates)
            peopleRepo.setPeople(sessionId, peopleWithImages, (identityResult).value.diagnostics)

            // ---- STEP 12.3: representative selection produced SOMETHING ------
            val withImage = peopleWithImages.filter { it.representativeFrame != null }
            assertTrue(
                "at least one person should have gotten a representative image",
                withImage.isNotEmpty(),
            )
            for (p in withImage) {
                val uriStr = p.representativeFrame!!.presentationCropKey
                val file = File(Uri.parse(uriStr).path!!)
                assertTrue("representative image file must exist for ${p.id}", file.exists())
                assertTrue("representative image file must be non-empty for ${p.id}", file.length() > 0)
                // the crop must come from an appearance actually owned by this person
                assertTrue(
                    p.representativeFrame!!.sourceObservationId in p.appearanceIds,
                )
            }

            // ---- STEP 12.4/12.15: deterministic person/appearance ordering ---
            val personAppearances = PersonAppearancesUseCase()
            for (p in peopleWithImages) {
                val a1 = personAppearances.appearancesFor(p, candidates)
                val a2 = personAppearances.appearancesFor(p, candidates)
                assertEquals("appearance ordering must be deterministic", a1.map { it.id }, a2.map { it.id })
                // sorted by start timestamp
                assertEquals(a1.sortedBy { it.startTimestampMs }.map { it.id }, a1.map { it.id })
            }

            // ================== PeopleViewModel state ==========================
            val peopleViewModel = PeopleViewModel(
                peopleRepository = peopleRepo,
                processingRepository = processingRepo,
                metadataReader = MediaMetadataVideoReader(context, d),
                generateEmbeddings = embedUseCase,
                buildIdentities = buildIdentities,
                selectRepresentativeImages = selectImages,
            )
            // people already stored -> load() must short-circuit to Content,
            // not re-run the whole pipeline
            peopleViewModel.load(sessionId)
            awaitNotLoading { peopleViewModel.uiState.value.loading }
            val peopleState = peopleViewModel.uiState.value
            assertFalse("Content state must not be loading", peopleState.loading)
            assertTrue("Content state must carry people", peopleState.people.isNotEmpty())
            assertEquals(peopleWithImages.size, peopleState.people.size)

            // ================== PersonDetailViewModel state ====================
            val firstPersonId = peopleState.people.first().id
            val detailViewModel = PersonDetailViewModel(
                peopleRepository = peopleRepo,
                processingRepository = processingRepo,
                personAppearances = personAppearances,
            )
            detailViewModel.load(sessionId, firstPersonId)
            awaitNotLoading { detailViewModel.uiState.value.loading }
            val detailState = detailViewModel.uiState.value
            assertFalse(detailState.loading)
            assertNotNull(detailState.person)
            assertEquals(firstPersonId, detailState.person!!.id)
            assertEquals(
                detailState.person!!.appearanceCount,
                detailState.appearances.size,
            )

            // ---- Error state: unknown person id -------------------------------
            val missingViewModel = PersonDetailViewModel(
                peopleRepository = peopleRepo,
                processingRepository = processingRepo,
                personAppearances = personAppearances,
            )
            missingViewModel.load(sessionId, "person_does_not_exist")
            awaitNotLoading { missingViewModel.uiState.value.loading }
            val missingState = missingViewModel.uiState.value
            assertFalse(missingState.loading)
            assertNotNull(missingState.error)
        } finally {
            runCatching { detector.close() }
            runCatching { embedder.close() }
            runCatching { kotlinx.coroutines.runBlocking { imageStorage.clear("phase7_s1") } }
        }
    }

    /**
     * Polls until [isLoading] returns false, or fails after a generous timeout.
     * `viewModelScope.launch` does NOT run on `runTest`'s virtual-time
     * scheduler, so a fixed real-world delay is a race, not a synchronization
     * point — this replaces that with an actual wait-for-completion.
     */
    private suspend fun awaitNotLoading(isLoading: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (isLoading() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }
        assertFalse("state never left loading within the timeout", isLoading())
    }
}
