package com.example.ikyky.features.collage.data.repository

import android.graphics.Bitmap
import com.example.ikyky.features.collage.domain.repository.CollageResultRepository
import java.util.concurrent.ConcurrentHashMap


class InMemoryCollageResultRepository constructor() : CollageResultRepository {

    private val bySession = ConcurrentHashMap<String, Bitmap>()

    override fun setCollage(sessionId: String, bitmap: Bitmap) {
        bySession[sessionId] = bitmap
    }

    override fun getCollage(sessionId: String): Bitmap? = bySession[sessionId]

    override fun clear() = bySession.clear()
}
