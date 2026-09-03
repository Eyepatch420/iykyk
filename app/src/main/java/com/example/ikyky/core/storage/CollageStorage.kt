package com.example.ikyky.core.storage

import android.graphics.Bitmap
import android.net.Uri
import com.example.ikyky.core.common.result.AppResult

/**
 * Persists a finished collage bitmap to the device gallery (MediaStore) and
 * returns the resulting content URI, which the share layer can then hand to
 * ACTION_SEND. Implementation is deferred to a later phase.
 */
interface CollageStorage {
    suspend fun saveToGallery(
        bitmap: Bitmap,
        displayName: String,
    ): AppResult<Uri>
}
