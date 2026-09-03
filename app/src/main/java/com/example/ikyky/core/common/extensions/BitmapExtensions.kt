package com.example.ikyky.core.common.extensions

import android.graphics.Bitmap
import android.graphics.Rect

/** Clamps a [Rect] to the bounds of a bitmap, returning null if it collapses. */
fun Rect.clampTo(bitmap: Bitmap): Rect? {
    val left = left.coerceIn(0, bitmap.width)
    val top = top.coerceIn(0, bitmap.height)
    val right = right.coerceIn(0, bitmap.width)
    val bottom = bottom.coerceIn(0, bitmap.height)
    if (right <= left || bottom <= top) return null
    return Rect(left, top, right, bottom)
}

/** Expands a rect by [fraction] of its size on every side (used for crop margins). */
fun Rect.expandBy(fraction: Float): Rect {
    val dx = (width() * fraction).toInt()
    val dy = (height() * fraction).toInt()
    return Rect(left - dx, top - dy, right + dx, bottom + dy)
}

/** Safe sub-bitmap crop; returns null instead of throwing on an invalid rect. */
fun Bitmap.cropOrNull(rect: Rect): Bitmap? {
    val safe = rect.clampTo(this) ?: return null
    return Bitmap.createBitmap(this, safe.left, safe.top, safe.width(), safe.height())
}
