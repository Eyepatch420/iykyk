package com.example.ikyky.core.storage.impl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.storage.CollageStorage
import kotlinx.coroutines.withContext

/**
 * MediaStore-based gallery save. Implemented now because it's small, self-
 * contained, and lets the save button work end-to-end once a collage bitmap
 * exists. minSdk 26 → always uses the pre-Q insert path guarded by RELATIVE_PATH
 * where available.
 */
class MediaStoreCollageStorage constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : CollageStorage {

    override suspend fun saveToGallery(
        bitmap: Bitmap,
        displayName: String,
    ): AppResult<Uri> = withContext(dispatchers.io) {
        val resolver = context.contentResolver
        val fileName = if (displayName.endsWith(".jpg")) displayName else "$displayName.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Iykyk",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: return@withContext AppResult.Failure(
                AppError.Storage("MediaStore insert returned null")
            )

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    return@withContext AppResult.Failure(AppError.Storage("Bitmap.compress failed"))
                }
            } ?: return@withContext AppResult.Failure(AppError.Storage("Could not open output stream"))

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            AppResult.Success(uri)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            AppResult.Failure(AppError.Storage("Failed to write collage to gallery", t))
        }
    }
}
