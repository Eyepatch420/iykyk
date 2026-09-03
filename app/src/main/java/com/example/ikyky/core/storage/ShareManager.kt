package com.example.ikyky.core.storage

import android.net.Uri
import com.example.ikyky.core.common.result.AppResult

/**
 * Launches the Android share sheet (ACTION_SEND, image mime type) for a saved
 * collage URI. Implementation is deferred to a later phase.
 */
interface ShareManager {
    fun shareImage(contentUri: Uri, mimeType: String = "image/jpeg"): AppResult<Unit>
}
