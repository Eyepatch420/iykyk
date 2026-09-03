package com.example.ikyky.core.storage.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.storage.ShareManager

/**
 * Fires the Android share sheet for an already-saved MediaStore image URI.
 * Uses `FLAG_ACTIVITY_NEW_TASK` since it's launched from a non-Activity context.
 */
class IntentShareManager constructor(
    private val context: Context,
) : ShareManager {

    override fun shareImage(contentUri: Uri, mimeType: String): AppResult<Unit> = try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        AppResult.Success(Unit)
    } catch (t: Throwable) {
        AppResult.Failure(AppError.Storage("Could not launch share sheet", t))
    }
}
