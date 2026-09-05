package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.model.SlotRole
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Rows of square-ish tiles where every other row is horizontally offset by
 * half a tile width — a brick/staggered pattern rather than a strict grid.
 * Row/column counts derive from [n] exactly like [GridLayoutEngine]; the only
 * difference is the alternating x-offset, so it reads as visually distinct
 * without any per-count branching.
 */
class StaggeredLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.EDITORIAL)
    override val supportedPeopleRange: IntRange = 5..26

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val spacing = 0.01f
            // Reserve room for the largest possible offset (half a cell) up front by
            // fitting (cols + 0.5) cells per row, so an offset row's last tile never
            // runs off the right edge — no skip/fallback logic needed.
            val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(2)
            val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)

            val cellW = (1f - spacing * (cols + 1)) / (cols + 0.5f)
            val cellH = (1f - spacing * (rows + 1)) / rows
            val offset = cellW / 2f

            val slots = ArrayList<LayoutSlot>(n)
            var placed = 0
            for (r in 0 until rows) {
                val remaining = n - placed
                val tilesThisRow = minOf(cols, remaining)
                val rowOffset = if (r % 2 == 1) offset else 0f
                // A short last row is CENTERED across the row's own extent rather
                // than left-aligned into unused cell slots -- left-aligning it was
                // exactly what produced large trailing whitespace on the right
                // (and, once offset, sometimes near-empty rows) whenever n wasn't a
                // clean multiple of cols.
                val rowWidth = tilesThisRow * cellW + (tilesThisRow - 1) * spacing
                val rowStartX = ((1f - rowWidth) / 2f) + rowOffset * (if (tilesThisRow < cols) 0.5f else 1f)
                for (c in 0 until tilesThisRow) {
                    slots += LayoutSlot(
                        x = (rowStartX + c * (cellW + spacing)).coerceIn(0f, 1f - cellW),
                        y = spacing + r * (cellH + spacing),
                        width = cellW,
                        height = cellH,
                        cornerRadius = 0.5f, // circular-ish tiles read well staggered
                        priority = n - placed,
                        role = if (placed == 0) SlotRole.PRIMARY else SlotRole.SECONDARY,
                    )
                    placed++
                }
            }

            LayoutTemplate(id = "staggered_${n}", style = LayoutStyle.EDITORIAL, slots = slots)
        }
}
