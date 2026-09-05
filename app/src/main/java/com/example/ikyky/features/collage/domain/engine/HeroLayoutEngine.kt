package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.features.collage.domain.model.CropMode
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.model.SlotRole
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * ONE large "hero" tile (the first/highest-priority person) beside a compact
 * grid of the rest. Reads well for small counts where one clear focal point
 * still leaves room to name every other face.
 *
 * Geometry is arithmetic on [LayoutConfiguration.personCount] — never a
 * `when (n)`: the hero always takes the left `heroFraction` of the canvas; the
 * remaining people tile a grid in the right column, row/col count derived the
 * same way [GridLayoutEngine] derives its own.
 */
class HeroLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.HERO)
    override val supportedPeopleRange: IntRange = 2..12

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(2)
            val spacing = 0.014f
            val heroFraction = 0.52f

            val slots = ArrayList<LayoutSlot>(n)
            // hero: full height, left column
            slots += LayoutSlot(
                x = spacing,
                y = spacing,
                width = heroFraction - spacing * 1.5f,
                height = 1f - spacing * 2f,
                cornerRadius = 0.03f,
                priority = n,
                role = SlotRole.HERO,
                cropMode = CropMode.FACE_CENTERED,
            )

            // remaining people: grid in the right column
            val rest = n - 1
            val cols = ceil(sqrt(rest.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(rest.toDouble() / cols).toInt().coerceAtLeast(1)
            val colX = heroFraction + spacing * 0.5f
            val colWidth = 1f - colX - spacing
            val cellW = (colWidth - spacing * (cols - 1)) / cols
            val cellH = (1f - spacing * (rows + 1)) / rows

            var placed = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (placed >= rest) break
                    slots += LayoutSlot(
                        x = colX + c * (cellW + spacing),
                        y = spacing + r * (cellH + spacing),
                        width = cellW,
                        height = cellH,
                        cornerRadius = 0.05f,
                        priority = rest - placed,
                        role = SlotRole.SECONDARY,
                    )
                    placed++
                }
            }

            LayoutTemplate(id = "hero_${n}", style = LayoutStyle.HERO, slots = slots)
        }
}
