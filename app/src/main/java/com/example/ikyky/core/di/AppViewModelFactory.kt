package com.example.ikyky.core.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.ikyky.features.collage.presentation.viewmodel.CollageViewModel
import com.example.ikyky.features.people.presentation.viewmodel.PeopleViewModel
import com.example.ikyky.features.people.presentation.viewmodel.PersonDetailViewModel
import com.example.ikyky.features.processing.presentation.viewmodel.ProcessingViewModel
import com.example.ikyky.features.result.presentation.viewmodel.ResultViewModel
import com.example.ikyky.features.video_selection.presentation.viewmodel.VideoSelectionViewModel

/**
 * Builds feature ViewModels from the [AppContainer]. One small factory instead of
 * per-feature Hilt modules. `@Composable` screens obtain it via
 * [LocalAppViewModelFactory].
 *
 * [sessionId] is the current processing session. Screens that are scoped to a
 * session (processing / people / collage / result) build their own factory with
 * [forSession] so the id reaches the ViewModel constructor without a DI
 * framework's assisted-injection machinery.
 */
class AppViewModelFactory(
    private val container: AppContainer,
    private val sessionId: String = "default",
) : ViewModelProvider.Factory {

    fun forSession(sessionId: String): AppViewModelFactory =
        AppViewModelFactory(container, sessionId)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(VideoSelectionViewModel::class.java) ->
            VideoSelectionViewModel(container.selectVideoUseCase) as T

        modelClass.isAssignableFrom(ProcessingViewModel::class.java) ->
            ProcessingViewModel(container.processVideoUseCase, sessionId) as T

        modelClass.isAssignableFrom(PeopleViewModel::class.java) ->
            PeopleViewModel(
                peopleRepository = container.peopleResultRepository,
                processingRepository = container.processingResultRepository,
                metadataReader = container.videoMetadataReader,
                generateEmbeddings = container.generateAppearanceEmbeddingsUseCase,
                buildIdentities = container.buildIdentitiesUseCase,
                selectRepresentativeImages = container.selectRepresentativeImagesUseCase,
            ) as T

        modelClass.isAssignableFrom(PersonDetailViewModel::class.java) ->
            PersonDetailViewModel(
                peopleRepository = container.peopleResultRepository,
                processingRepository = container.processingResultRepository,
                personAppearances = container.personAppearancesUseCase,
            ) as T

        modelClass.isAssignableFrom(CollageViewModel::class.java) ->
            CollageViewModel(
                container.generateCollageUseCase,
                container.collageResultRepository,
                container.dispatchers,
            ) as T

        modelClass.isAssignableFrom(ResultViewModel::class.java) ->
            ResultViewModel(
                container.peopleResultRepository,
                container.collageResultRepository,
                container.saveCollageUseCase,
                container.shareCollageUseCase,
            ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
