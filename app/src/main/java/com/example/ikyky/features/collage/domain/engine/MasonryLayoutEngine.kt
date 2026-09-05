package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.model.SlotRole
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pinterest-style masonry: a fixed number of equal-width columns, each filled
 * top-to-bottom with tiles of VARYING height (a deterministic pseudo-random
 * height multiplier seeded from the tile's own index, so the same [n] always
 * produces the same layout). Each new tile goes into whichever column is
 * currently shortest — the classic masonry packing rule — which is what
 * produces the dense, non-uniform look at high people counts instead of a
 * boring shrunk grid.
 *
 * Column count grows with [n] (more people -> narrower columns -> denser
 * composition) via a simple arithmetic rule, never a literal count switch.
 */
class MasonryLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.MASONRY)
    override val supportedPeopleRange: IntRange = 8..40

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val spacing = 0.008f
            // more people -> more columns, capped so tiles never get absurdly thin
            val columns = max(3, (sqrt(n.toDouble()) * 0.9).toInt()).coerceAtMost(7)

            val colWidth = (1f - spacing * (columns + 1)) / columns
            val colHeights = FloatArray(columns) { spacing } // current fill height per column

            val slots = ArrayList<LayoutSlot>(n)
            // Base tile height assumes an even split then varies +/-40% per tile by a
            // deterministic hash of its index -- same n always yields the same heights.
            val baseHeight = 1f / (n.toFloat() / columns).coerceAtLeast(1f)
            for (i in 0 until n) {
                val col = colHeights.indices.minByOrNull { colHeights[it] } ?: 0
                val variance = 0.7f + (stableFraction(i) * 0.6f) // in [0.7, 1.3)
                val height = (baseHeight * variance).coerceIn(0.05f, 0.5f)

                slots += LayoutSlot(
                    x = spacing + col * (colWidth + spacing),
                    y = colHeights[col],
                    width = colWidth,
                    height = height,
                    cornerRadius = 0.04f,
                    priority = n - i,
                    role = if (i == 0) SlotRole.PRIMARY else SlotRole.SECONDARY,
                )
                colHeights[col] += height + spacing
            }

            // Normalize: the tallest column may exceed 1f (or leave slack below it) --
            // rescale every slot's y/height by the tallest column's final extent so the
            // whole composition fits exactly in the [0,1] canvas.
            val contentHeight = colHeights.max().coerceAtLeast(1e-3f)
            val scale = 1f / contentHeight
            val normalized = slots.map { it.copy(y = it.y * scale, height = it.height * scale) }

            LayoutTemplate(id = "masonry_${n}", style = LayoutStyle.MASONRY, slots = normalized)
        }

    /** Deterministic pseudo-random value in [0,1) derived from an integer index. */
    private fun stableFraction(index: Int): Float {
        var x = index * 2654435761L
        x = x xor (x ushr 13)
        x *= 0x5bd1e995L
        x = x xor (x ushr 15)
        return ((x and 0xFFFFFF) / 16777216.0).toFloat()
    }
}
