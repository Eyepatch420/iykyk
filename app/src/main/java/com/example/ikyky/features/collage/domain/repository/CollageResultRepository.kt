package com.example.ikyky.features.collage.domain.repository

import android.graphics.Bitmap

/**
 * In-memory holder for the generated collage bitmap of the current session,
 * shared between the collage screen and the result (save/share) screen.
 */
interface CollageResultRepository {
    fun setCollage(sessionId: String, bitmap: Bitmap)
    fun getCollage(sessionId: String): Bitmap?
    fun clear()
}
