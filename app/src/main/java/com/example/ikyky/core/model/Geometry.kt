package com.example.ikyky.core.model

/**
 * Framework-free geometry primitives shared by the ML/tracking/collage layers.
 * Domain code must not reference `android.graphics.Rect` / `PointF` directly.
 */
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Int get() = width.coerceAtLeast(0) * height.coerceAtLeast(0)

    fun iou(other: BoundingBox): Float {
        val ix = maxOf(left, other.left)
        val iy = maxOf(top, other.top)
        val ax = minOf(right, other.right)
        val ay = minOf(bottom, other.bottom)
        val iw = (ax - ix).coerceAtLeast(0)
        val ih = (ay - iy).coerceAtLeast(0)
        val inter = iw * ih
        val union = area + other.area - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }
}

data class Landmark(
    val type: LandmarkType,
    val x: Float,
    val y: Float,
)

enum class LandmarkType {
    LEFT_EYE, RIGHT_EYE, NOSE_BASE, MOUTH_LEFT, MOUTH_RIGHT, MOUTH_BOTTOM,
    LEFT_EAR, RIGHT_EAR, LEFT_CHEEK, RIGHT_CHEEK,
}

data class HeadPose(
    val eulerX: Float, // pitch
    val eulerY: Float, // yaw
    val eulerZ: Float, // roll
)
