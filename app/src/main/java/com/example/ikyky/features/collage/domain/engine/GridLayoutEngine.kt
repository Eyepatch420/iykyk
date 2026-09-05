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
 * The one concrete engine shipped in Phase 1: a count-adaptive uniform grid.
 * It exists to prove the abstraction end-to-end and to give the renderer
 * something real to draw. Richer styles (masonry, hero, scrapbook…) are added
 * later as additional [LayoutEngine]s without touching callers.
 *
 * Note there is NO `when (count)` here — rows/cols are derived arithmetically.
 */
class GridLayoutEngine constructor() : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.GRID)

    override val supportedPeopleRange: IntRange = 1..40

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)

            val spacing = 0.012f
            val cellW = (1f - spacing * (cols + 1)) / cols
            val cellH = (1f - spacing * (rows + 1)) / rows

            val slots = ArrayList<LayoutSlot>(n)
            var placed = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (placed >= n) break
                    slots += LayoutSlot(
                        x = spacing + c * (cellW + spacing),
                        y = spacing + r * (cellH + spacing),
                        width = cellW,
                        height = cellH,
                        cornerRadius = 0.06f,
                        priority = n - placed,
                        role = if (placed == 0) SlotRole.PRIMARY else SlotRole.SECONDARY,
                    )
                    placed++
                }
            }

            LayoutTemplate(
                id = "grid_${rows}x${cols}",
                style = LayoutStyle.GRID,
                slots = slots,
            )
        }
}
