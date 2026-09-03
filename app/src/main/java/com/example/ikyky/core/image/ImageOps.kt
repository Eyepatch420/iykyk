package com.example.ikyky.core.image

import android.graphics.Bitmap

/**
 * Small bitmap utility seam (resize / rotate / center-crop) so pipeline code
 * doesn't scatter `Bitmap.createBitmap` / `Matrix` calls everywhere and can be
 * swapped for a pooled implementation later if memory pressure demands it.
 */
interface ImageOps {
    fun resize(source: Bitmap, width: Int, height: Int, filter: Boolean = true): Bitmap
    fun rotate(source: Bitmap, degrees: Int): Bitmap
    fun centerSquareCrop(source: Bitmap): Bitmap
}

class DefaultImageOps : ImageOps {
    override fun resize(source: Bitmap, width: Int, height: Int, filter: Boolean): Bitmap =
        Bitmap.createScaledBitmap(source, width, height, filter)

    override fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    override fun centerSquareCrop(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        return Bitmap.createBitmap(source, x, y, side, side)
    }
}
