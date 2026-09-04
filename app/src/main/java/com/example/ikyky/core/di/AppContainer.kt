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
import com.example.ikyky.core.storage.ShareManager
import com.example.ikyky.core.storage.impl.IntentShareManager
import com.example.ikyky.core.storage.impl.MediaStoreCollageStorage
import com.example.ikyky.features.collage.data.GenerateCollagePhase1Stub
import com.example.ikyky.features.collage.data.repository.InMemoryCollageResultRepository
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.engine.LayoutEngine
import com.example.ikyky.features.collage.domain.repository.CollageResultRepository
import com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase
import com.example.ikyky.features.people.data.DefaultBuildIdentitiesUseCase
import com.example.ikyky.features.people.data.FrozenBuildIdentitiesUseCase
import com.example.ikyky.features.people.data.repository.InMemoryPeopleResultRepository
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import com.example.ikyky.features.people.domain.usecase.BuildIdentitiesUseCase
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
    val layoutEngine: LayoutEngine by lazy { GridLayoutEngine() }

    // --- storage / share ---
    val collageStorage: CollageStorage by lazy { MediaStoreCollageStorage(appContext, dispatchers) }
    val shareManager: ShareManager by lazy { IntentShareManager(appContext) }

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
    val generateCollageUseCase: GenerateCollageUseCase by lazy { GenerateCollagePhase1Stub() }
    val saveCollageUseCase: SaveCollageUseCase get() = SaveCollageUseCase(collageStorage)
    val shareCollageUseCase: ShareCollageUseCase get() = ShareCollageUseCase(collageStorage, shareManager)

    /** Releases native ML resources. Called from the Application's onTerminate. */
    fun shutdown() {
        runCatching { faceDetector.close() }
        runCatching { faceEmbedder.close() }
    }
}
