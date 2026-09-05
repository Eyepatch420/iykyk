package com.example.ikyky.features.collage.data.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.core.dispatcher.DispatcherProvider
import com.example.ikyky.features.collage.domain.model.CropMode
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.render.CollageRenderer
import com.example.ikyky.features.collage.domain.render.CollageTile
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Rasterizes a [LayoutTemplate] onto a single [Bitmap] with `android.graphics.Canvas`.
 *
 * Deterministic by construction: every geometric quantity is derived from the
 * template's normalized slot data and the requested output size — no random
 * seed, no wall-clock, no external state. The same (template, tiles, size)
 * triple always paints identical pixels.
 *
 * Contains NO count-specific or style-specific branching: it only reads
 * [LayoutSlot] fields (position, rotation, crop mode, corner radius, z-index)
 * that every engine already fills in. A brand-new [com.example.ikyky.features.collage.domain.engine.LayoutEngine]
 * needs zero renderer changes.
 */
class CanvasCollageRenderer(
    private val dispatchers: DispatcherProvider,
) : CollageRenderer {

    override suspend fun render(
        template: LayoutTemplate,
        tiles: List<CollageTile>,
        outputWidth: Int,
        outputHeight: Int,
    ): AppResult<Bitmap> = withContext(dispatchers.default) {
        runCatchingResult {
            require(outputWidth > 0 && outputHeight > 0) { "output size must be positive" }

            val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(template.backgroundColor.toInt())

            val byIndex = tiles.groupBy { it.slotIndex }
            // Renderer-owned ordering rule, applied uniformly to every template:
            // paint in ascending zIndex so later (higher-z) slots land on top.
            // This is the ENTIRE overlap/stacking implementation -- no
            // style-specific branch anywhere else.
            val paintOrder = template.slots.indices.sortedBy { template.slots[it].zIndex }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (slotIndex in paintOrder) {
                val slot = template.slots[slotIndex]
                val tile = byIndex[slotIndex]?.firstOrNull() ?: continue
                drawTile(canvas, slot, tile.bitmap, outputWidth, outputHeight, paint)
            }

            output
        }
    }

    private fun drawTile(
        canvas: Canvas,
        slot: LayoutSlot,
        bitmap: Bitmap,
        outputWidth: Int,
        outputHeight: Int,
        paint: Paint,
    ) {
        if (bitmap.isRecycled) return

        val padPxX = slot.padding * slot.width * outputWidth
        val padPxY = slot.padding * slot.height * outputHeight
        val left = slot.x * outputWidth + padPxX
        val top = slot.y * outputHeight + padPxY
        val right = (slot.x + slot.width) * outputWidth - padPxX
        val bottom = (slot.y + slot.height) * outputHeight - padPxY
        if (right <= left || bottom <= top) return

        val destRect = RectF(left, top, right, bottom)
        val cx = destRect.centerX()
        val cy = destRect.centerY()
        val cornerPx = slot.cornerRadius * min(destRect.width(), destRect.height())

        canvas.save()
        try {
            // Rotation is applied to the CANVAS around the slot's own center, per
            // the frozen contract ("never rotate the source bitmap destructively") --
            // the bitmap itself is untouched; only how it's painted is rotated.
            if (slot.rotationDegrees != 0f) {
                canvas.rotate(slot.rotationDegrees, cx, cy)
            }

            // Clip to the (rounded) rect BEFORE drawing so the fill mode can never
            // paint outside the slot's shape, regardless of crop math below.
            val clipPath = Path().apply {
                addRoundRect(destRect, cornerPx, cornerPx, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)

            val matrix = fillMatrix(bitmap, destRect, slot.cropMode)
            canvas.drawBitmap(bitmap, matrix, paint)
        } finally {
            canvas.restore()
        }
    }

    /**
     * Builds the bitmap->canvas matrix for one slot's fill mode. Every mode
     * preserves aspect ratio -- the brief is explicit that stretching is never
     * acceptable, so there is no "fill exactly, ignore ratio" path at all.
     *
     * - CENTER_CROP / FACE_CENTERED: scale so the bitmap fully covers the dest
     *   rect (the larger of the two scale factors), then center it. The two
     *   modes are geometrically identical here because "face-centered" cropping
     *   needs a face location the renderer doesn't have -- it degrades safely
     *   to a plain center-crop rather than guessing, which is the same
     *   principle Phase 6 applied to landmark fallbacks (never invent data).
     * - FIT: scale so the whole bitmap fits inside the dest rect (the smaller
     *   scale factor), centered, leaving letterbox space rather than cropping.
     */
    private fun fillMatrix(bitmap: Bitmap, dest: RectF, cropMode: CropMode): Matrix {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = when (cropMode) {
            CropMode.FIT -> min(dest.width() / bw, dest.height() / bh)
            CropMode.CENTER_CROP, CropMode.FACE_CENTERED -> max(dest.width() / bw, dest.height() / bh)
        }
        val scaledW = bw * scale
        val scaledH = bh * scale
        val dx = dest.left + (dest.width() - scaledW) / 2f
        val dy = dest.top + (dest.height() - scaledH) / 2f

        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}
