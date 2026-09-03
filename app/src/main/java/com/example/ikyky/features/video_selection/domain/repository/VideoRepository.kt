package com.example.ikyky.features.video_selection.domain.repository

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.video_selection.domain.model.SelectedVideo

/**
 * Validates a picked video URI and produces a [SelectedVideo]. Backed by a data
 * source that reads metadata via `MediaMetadataRetriever` (later phase).
 */
interface VideoRepository {
    suspend fun validateAndDescribe(uriString: String): AppResult<SelectedVideo>
}
