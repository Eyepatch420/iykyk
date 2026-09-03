package com.example.ikyky.features.collage.domain.render

import android.graphics.Bitmap
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.collage.domain.model.LayoutTemplate

/**
 * One tile to place: a person's representative crop plus the slot index it maps
 * to. The engine guarantees `template.slots.size >= tiles.size` and that every
 * person appears exactly once.
 */
data class CollageTile(
    val personId: String,
    val bitmap: Bitmap,
    val slotIndex: Int,
)

/**
 * Rasterizes a [LayoutTemplate] + tiles into a single collage bitmap on a
 * background dispatcher. Phase 1 defines the contract; the Canvas-based
 * implementation lands with the layout styles.
 */
interface CollageRenderer {
    suspend fun render(
        template: LayoutTemplate,
        tiles: List<CollageTile>,
        outputWidth: Int,
        outputHeight: Int,
    ): AppResult<Bitmap>
}
