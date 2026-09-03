package com.example.ikyky.features.video_selection.domain.usecase

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.video_selection.domain.model.SelectedVideo
import com.example.ikyky.features.video_selection.domain.repository.VideoRepository

/**
 * Validates the user's picked video and returns a [SelectedVideo] the
 * processing feature can consume. Business rules (min duration, portrait check,
 * codec support) will live here.
 */
class SelectVideoUseCase constructor(
    private val repository: VideoRepository,
) {
    suspend operator fun invoke(uriString: String): AppResult<SelectedVideo> =
        repository.validateAndDescribe(uriString)
}
