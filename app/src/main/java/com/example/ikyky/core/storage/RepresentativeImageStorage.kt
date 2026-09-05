package com.example.ikyky.core.storage

import android.graphics.Bitmap
import android.net.Uri
import com.example.ikyky.core.common.result.AppResult

/**
 * Persists ONE person's representative-frame crop to **app-private storage**
 * and returns a stable [Uri] a `Coil`/`ImageDecoder`-style loader can display.
 *
 * Deliberately **not** [CollageStorage]: that interface writes to the public
 * MediaStore gallery, which is correct for the one finished collage a user
 * explicitly saves/shares, but wrong here — Phase 7 produces one crop per
 * detected person (often 5-10 per session), and none of them should appear in
 * the user's Photos app as a side effect of merely viewing results.
 *
 * Files live under the app's cache dir, so they are automatically eligible for
 * OS-driven cleanup under storage pressure and never require the app to ask
 * for gallery/media permissions.
 */
interface RepresentativeImageStorage {
    /**
     * @param sessionId scopes the file so a later session cannot collide with
     *   or accidentally reuse a stale crop from an earlier one.
     * @param personId used to name the file; not sanitized beyond ASCII
     *   id-safe characters already guaranteed by [Person.id][com.example.ikyky.features.people.domain.model.Person.id].
     */
    suspend fun save(sessionId: String, personId: String, bitmap: Bitmap): AppResult<Uri>

    /** Removes every representative-image file for [sessionId], if any exist. */
    suspend fun clear(sessionId: String)
}
