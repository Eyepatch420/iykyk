package com.example.ikyky.core.storage.impl

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.core.storage.RepresentativeImageStorage
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes representative-person crops as JPEGs under
 * `cacheDir/representative_images/<sessionId>/<personId>.jpg` and returns a
 * `file://` [Uri]. Never touches MediaStore — see [RepresentativeImageStorage].
 */
class CacheRepresentativeImageStorage(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : RepresentativeImageStorage {

    override suspend fun save(
        sessionId: String,
        personId: String,
        bitmap: Bitmap,
    ): AppResult<Uri> = withContext(dispatchers.io) {
        try {
            val dir = sessionDir(sessionId).apply { mkdirs() }
            val file = File(dir, "$personId.jpg")
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                    return@withContext AppResult.Failure(
                        AppError.Storage("Bitmap.compress failed for $personId"),
                    )
                }
            }
            AppResult.Success(Uri.fromFile(file))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to save representative image for $personId", t))
        }
    }

    override suspend fun clear(sessionId: String) {
        withContext(dispatchers.io) {
            runCatching { sessionDir(sessionId).deleteRecursively() }
        }
    }

    private fun sessionDir(sessionId: String): File =
        File(File(context.cacheDir, "representative_images"), sessionId)
}
