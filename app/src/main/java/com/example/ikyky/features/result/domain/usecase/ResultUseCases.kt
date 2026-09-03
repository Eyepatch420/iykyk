package com.example.ikyky.features.result.domain.usecase

import android.graphics.Bitmap
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.flatMap
import com.example.ikyky.core.storage.CollageStorage
import com.example.ikyky.core.storage.ShareManager

/**
 * Saves the finished collage to the device gallery via [CollageStorage].
 */
class SaveCollageUseCase constructor(
    private val storage: CollageStorage,
) {
    suspend operator fun invoke(bitmap: Bitmap, displayName: String): AppResult<android.net.Uri> =
        storage.saveToGallery(bitmap, displayName)
}

/**
 * Saves (if needed) then opens the Android share sheet for the collage.
 */
class ShareCollageUseCase constructor(
    private val storage: CollageStorage,
    private val shareManager: ShareManager,
) {
    suspend operator fun invoke(bitmap: Bitmap, displayName: String): AppResult<Unit> =
        storage.saveToGallery(bitmap, displayName).flatMap { uri ->
            shareManager.shareImage(uri)
        }
}
