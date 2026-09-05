package com.example.ikyky.features.collage.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.repository.CollageResultRepository
import com.example.ikyky.features.collage.domain.usecase.GenerateCollageUseCase
import com.example.ikyky.features.collage.domain.usecase.GetCollageTemplatesUseCase
import com.example.ikyky.features.collage.presentation.state.CollageUiState
import com.example.ikyky.features.people.domain.repository.PeopleResultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the collage stage for one session.
 *
 * [load] looks up which templates fit the session's people (via
 * [GetCollageTemplatesUseCase] — this ViewModel never computes layout itself)
 * and generates the collage for the first one. [selectTemplate] re-generates
 * for a different template the user picked, without re-deriving the template
 * list. No layout/rendering logic lives here — only orchestration + state.
 */
class CollageViewModel(
    private val peopleRepository: PeopleResultRepository,
    private val getTemplates: GetCollageTemplatesUseCase,
    private val generateCollage: GenerateCollageUseCase,
    private val collageRepository: CollageResultRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollageUiState())
    val uiState: StateFlow<CollageUiState> = _uiState.asStateFlow()

    private var currentSessionId: String? = null

    fun load(sessionId: String, outputWidth: Int, outputHeight: Int) {
        currentSessionId = sessionId
        val people = peopleRepository.getPeople(sessionId)
        _uiState.update { it.copy(loading = true, personCount = people.size, error = null) }

        if (people.isEmpty()) {
            _uiState.update {
                it.copy(loading = false, error = com.example.ikyky.core.common.error.AppError.NoPeopleDetected())
            }
            return
        }

        val templates = getTemplates(people.size, outputWidth, outputHeight)
        if (templates.isEmpty()) {
            _uiState.update {
                it.copy(
                    loading = false,
                    error = com.example.ikyky.core.common.error.AppError.Unexpected(
                        "No collage template supports ${people.size} people",
                    ),
                )
            }
            return
        }

        _uiState.update { it.copy(loading = false, availableTemplates = templates) }
        generate(sessionId, templates.first().style, outputWidth, outputHeight)
    }

    fun selectTemplate(style: LayoutStyle, outputWidth: Int, outputHeight: Int) {
        val sessionId = currentSessionId ?: return
        generate(sessionId, style, outputWidth, outputHeight)
    }

    private fun generate(sessionId: String, style: LayoutStyle, width: Int, height: Int) {
        _uiState.update { it.copy(generating = true, error = null) }
        viewModelScope.launch {
            val result = withContext(dispatchers.default) {
                generateCollage(sessionId, width, height, style)
            }
            _uiState.update { current ->
                when (result) {
                    is AppResult.Success -> {
                        collageRepository.setCollage(sessionId, result.value)
                        val selected = current.availableTemplates.firstOrNull { it.style == style }
                        current.copy(
                            generating = false,
                            collage = result.value,
                            selectedTemplateId = selected?.id ?: current.selectedTemplateId,
                        )
                    }
                    is AppResult.Failure -> current.copy(generating = false, error = result.error)
                }
            }
        }
    }
}
