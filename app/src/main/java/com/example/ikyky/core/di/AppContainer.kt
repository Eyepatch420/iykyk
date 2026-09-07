package com.example.ikyky.core.di

import android.content.Context
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.dispatcher.StandardDispatcherProvider
import com.example.ikyky.core.image.DefaultImageOps
import com.example.ikyky.core.image.ImageOps
import com.example.ikyky.core.logging.AndroidLogger
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.MediaMetadataFrameExtractor
import com.example.ikyky.core.media.MediaMetadataVideoReader
import com.example.ikyky.core.media.VideoFrameExtractor
import com.example.ikyky.core.media.VideoMetadataReader
import com.example.ikyky.core.ml.detector.FaceDetector
import com.example.ikyky.core.ml.detector.MlKitFaceDetector
import com.example.ikyky.core.ml.shots.DefaultShotScanner
import com.example.ikyky.core.ml.shots.ShotScanner
import com.example.ikyky.core.ml.tracking.ShotAwareFaceTracker
import com.example.ikyky.features.processing.data.TrackerGateEmbedder
import com.example.ikyky.core.ml.embedding.FaceEmbedder
import com.example.ikyky.core.ml.embedding.LiteRtFaceEmbedder
import com.example.ikyky.core.ml.model.EmbeddingModelLoader
import com.example.ikyky.core.ml.model.ModelSpec
import com.example.ikyky.core.ml.preprocessing.DefaultFacePreprocessor
import com.example.ikyky.core.ml.preprocessing.FacePreprocessor
import com.example.ikyky.core.storage.CollageStorage
import com.example.ikyky.core.storage.RepresentativeImageStorage
import com.example.ikyky.core.storage.ShareManager
import com.example.ikyky.core.storage.impl.CacheRepresentativeImageStorage
import com.example.ikyky.core.storage.impl.IntentShareManager
import com.example.ikyky.core.storage.impl.MediaStoreCollageStorage
import com.example.ikyky.features.collage.data.DefaultGenerateCollageUseCase
import com.example.ikyky.features.collage.data.render.CanvasCollageRenderer
import com.example.ikyky.features.collage.data.repository.InMemoryCollageResultRepository
import com.example.ikyky.features.collage.domain.engine.AsymmetricLayoutEngine
import com.example.ikyky.features.collage.domain.engine.CollageTemplateRegistry
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.engine.HeroLayoutEngine
import com.example.ikyky.features.collage.domain.engine.LayoutEngine
import com.example.ikyky.features.collage.domain.engine.MasonryLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MixedSizeLayoutEngine
import com.example.ikyky.features.collage.domain.engine.OverlapLayoutEngine
import com.example.ikyky.features.collage.domain.engine.ScrapbookLayoutEngine
import com.example.ikyky.features.collage.domain.engine.StaggeredLayoutEngine
import com.example.ikyky.features.collage.domain.render.CollageRenderer
import com.example.ikyky.features.collage.domain.repository.CollageResultRepository
import com.example.ikyky.features.collage.domain.usecase.ChooseCollageImagesUseCase
import com.example.ikyky.features.collage.domain.usecase.DefaultChooseCollageImagesUseCase
import com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase
import com.example.ikyky.features.collage.domain.usecase.GetCollageTemplatesUseCase
import com.example.ikyky.features.people.data.DefaultBuildIdentitiesUseCase
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.people.data.repository.InMemoryPeopleResultRepository
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import com.example.ikyky.features.people.domain.usecase.BuildIdentitiesUseCase
import com.example.ikyky.features.people.domain.usecase.PersonAppearancesUseCase
import com.example.ikyky.features.people.domain.usecase.SelectRepresentativeImagesUseCase
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.data.DefaultGenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.data.DefaultProcessVideoUseCase
import com.example.ikyky.features.processing.data.repository.InMemoryProcessingResultRepository
import com.example.ikyky.features.processing.domain.repository.ProcessingResultRepository
import com.example.ikyky.features.processing.domain.usecase.GenerateAppearanceEmbeddingsUseCase
import com.example.ikyky.features.processing.domain.usecase.ProcessVideoUseCase
import com.example.ikyky.features.result.domain.usecase.SaveCollageUseCase
import com.example.ikyky.features.result.domain.usecase.ShareCollageUseCase
import com.example.ikyky.features.video_selection.data.repository.MediaVideoRepository
import com.example.ikyky.features.video_selection.domain.repository.VideoRepository
import com.example.ikyky.features.video_selection.domain.usecase.SelectVideoUseCase

/**
 * Hand-rolled composition root.
 *
 * A full DI framework (Hilt) was intentionally dropped for this assignment
 * because its Gradle plugin is not yet stable on AGP 9. For a project this size
 * a single lazily-initialised container is simpler, has zero annotation
 * processing, and keeps dependency wiring in one readable place. Feature
 * ViewModels are built by [AppViewModelFactory] from this container.
 *
 * ML engine singletons ([faceDetector], [faceEmbedder], [embeddingModelLoader])
 * are created once here and reused for every face across a whole video.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // --- core ---
    val dispatchers: DispatcherProvider by lazy { StandardDispatcherProvider() }
    val logger: Logger by lazy { AndroidLogger() }
    val imageOps: ImageOps by lazy { DefaultImageOps() }

    // --- ML (singletons, model loaded once) ---
    val modelSpec: ModelSpec = ModelSpec.MOBILE_FACE_NET
    val embeddingModelLoader: EmbeddingModelLoader by lazy {
        EmbeddingModelLoader(appContext, modelSpec)
    }
    val faceDetector: FaceDetector by lazy { MlKitFaceDetector() }
    val faceEmbedder: FaceEmbedder by lazy { LiteRtFaceEmbedder(embeddingModelLoader, modelSpec) }
    val facePreprocessor: FacePreprocessor get() = DefaultFacePreprocessor()

    /**
     * The FROZEN Phase 5I tracker (Config K / Option A): absolute whip-pan
     * barriers + the embedding gate. A fresh instance is cheap — stateless
     * config only.
     *
     * [GreedyFaceTracker] remains in the source tree for the Phase-2 tests and
     * diagnostics that compare against it, but is no longer on the app's path.
     */
    val faceTracker: ShotAwareFaceTracker get() = ShotAwareFaceTracker()

    /** Every-frame whip-pan / shot-change scan feeding the tracker's barriers. */
    val shotScanner: ShotScanner get() = DefaultShotScanner()

    /**
     * Supplies the tracker's appearance-gate embeddings from the EXPANDED crop.
     * Distinct from [facePreprocessor], which produces the 5-point RECOGNITION
     * crop — the two must never be swapped (Phase 5I Option A vs B).
     */
    val trackerGateEmbedder: TrackerGateEmbedder by lazy { TrackerGateEmbedder(faceEmbedder) }

    // --- media ---
    val videoMetadataReader: VideoMetadataReader by lazy {
        MediaMetadataVideoReader(appContext, dispatchers)
    }
    val videoFrameExtractor: VideoFrameExtractor by lazy {
        MediaMetadataFrameExtractor(appContext, dispatchers)
    }

    // --- session result holders ---
    val processingResultRepository: ProcessingResultRepository by lazy {
        InMemoryProcessingResultRepository()
    }
    val peopleResultRepository: PeopleResultRepository by lazy { InMemoryPeopleResultRepository() }
    val collageResultRepository: CollageResultRepository by lazy { InMemoryCollageResultRepository() }

    // --- collage engine ---
    /**
     * Phase 8 — every visual style the app can produce, purely as data. Adding
     * a style is adding one more [LayoutEngine] here; nothing else (registry,
     * use case, renderer, ViewModel) branches on style or people count.
     */
    val collageEngines: List<LayoutEngine> by lazy {
        listOf(
            GridLayoutEngine(),
            HeroLayoutEngine(),
            AsymmetricLayoutEngine(),
            StaggeredLayoutEngine(),
            MasonryLayoutEngine(),
            OverlapLayoutEngine(),
            ScrapbookLayoutEngine(),
            MixedSizeLayoutEngine(),
        )
    }
    val collageTemplateRegistry: CollageTemplateRegistry by lazy { CollageTemplateRegistry(collageEngines) }
    val collageRenderer: CollageRenderer by lazy { CanvasCollageRenderer(dispatchers) }
    val chooseCollageImagesUseCase: ChooseCollageImagesUseCase by lazy { DefaultChooseCollageImagesUseCase() }
    val getCollageTemplatesUseCase: GetCollageTemplatesUseCase by lazy {
        GetCollageTemplatesUseCase(collageTemplateRegistry)
    }

    // --- storage / share ---
    val collageStorage: CollageStorage by lazy { MediaStoreCollageStorage(appContext, dispatchers) }
    val shareManager: ShareManager by lazy { IntentShareManager(appContext) }

    /**
     * App-private storage for per-person representative crops. Deliberately
     * NOT [collageStorage] — that writes to the public MediaStore gallery,
     * correct for the one collage a user explicitly saves, wrong for the
     * 5-10 incidental face crops produced just by viewing the People screen.
     */
    val representativeImageStorage: RepresentativeImageStorage by lazy {
        CacheRepresentativeImageStorage(appContext, dispatchers)
    }

    // --- feature repositories ---
    val videoRepository: VideoRepository by lazy { MediaVideoRepository(appContext, dispatchers) }

    // --- use cases ---
    val selectVideoUseCase: SelectVideoUseCase get() = SelectVideoUseCase(videoRepository)
    val processVideoUseCase: ProcessVideoUseCase by lazy {
        DefaultProcessVideoUseCase(
            metadataReader = videoMetadataReader,
            frameExtractor = videoFrameExtractor,
            faceDetector = faceDetector,
            resultRepository = processingResultRepository,
            dispatchers = dispatchers,
            logger = logger,
            shotScanner = shotScanner,
            tracker = faceTracker,
            gateEmbedder = trackerGateEmbedder,
        )
    }
    val generateAppearanceEmbeddingsUseCase: GenerateAppearanceEmbeddingsUseCase by lazy {
        DefaultGenerateAppearanceEmbeddingsUseCase(
            frameExtractor = videoFrameExtractor,
            preprocessor = facePreprocessor,
            embedder = faceEmbedder,
            modelLoader = embeddingModelLoader,
            dispatchers = dispatchers,
            logger = logger,
        )
    }
    /**
     * **Production identity clustering — the FROZEN Phase 5I / Phase 6 path.**
     *
     * [FrozenBuildIdentitiesUseCase] wraps the already-frozen
     * [AgglomerativeIdentityClusterer] at
     * [com.example.ikyky.core.common.constants.PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD]
     * (0.475) with [MustNotLinkBuilder] as hard constraints, and nothing else —
     * no intra-appearance split, no dense re-embedding, no unsupervised
     * threshold calibration.
     *
     * [DefaultBuildIdentitiesUseCase] (the old calibrator-based path, which
     * falls back to a 0.62 threshold on low calibration confidence) is NOT
     * wired here and must not be reachable from real processing. It remains in
     * the source tree only for the Phase 4/4.5 diagnostic screens and their
     * existing tests, which study what calibration/splitting would do.
     */
    val buildIdentitiesUseCase: BuildIdentitiesUseCase by lazy {
        FrozenBuildIdentitiesUseCase(dispatchers = dispatchers, logger = logger)
    }

    /**
     * Phase 7/8.1 — picks each [com.example.ikyky.features.people.domain.model.Person]'s
     * individual-person representative crop (best usable observation across all
     * of that person's appearances, per
     * [com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper]) and
     * persists it via [representativeImageStorage]. A pure presentation step;
     * never touches clustering or the frozen recognition path.
     */
    val selectRepresentativeImagesUseCase: SelectRepresentativeImagesUseCase by lazy {
        SelectRepresentativeImagesUseCase(
            frameExtractor = videoFrameExtractor,
            storage = representativeImageStorage,
            logger = logger,
        )
    }

    /** Joins a [Person]'s appearance ids back against the session's [AppearanceCandidate]s. */
    val personAppearancesUseCase: PersonAppearancesUseCase by lazy { PersonAppearancesUseCase() }

    val generateCollageUseCase: GenerateCollageUseCase by lazy {
        DefaultGenerateCollageUseCase(
            context = appContext,
            peopleRepository = peopleResultRepository,
            chooseImages = chooseCollageImagesUseCase,
            registry = collageTemplateRegistry,
            renderer = collageRenderer,
            dispatchers = dispatchers,
            logger = logger,
        )
    }
    val saveCollageUseCase: SaveCollageUseCase get() = SaveCollageUseCase(collageStorage)
    val shareCollageUseCase: ShareCollageUseCase get() = ShareCollageUseCase(collageStorage, shareManager)

    /** Releases native ML resources. Called from the Application's onTerminate. */
    fun shutdown() {
        runCatching { faceDetector.close() }
        runCatching { faceEmbedder.close() }
    }
}
