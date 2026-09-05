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
 * Polaroid/scrapbook look: uniformly-sized square-ish tiles with a thick
 * corner radius suggesting a printed-photo border, each rotated by a larger,
 * more pronounced deterministic angle than [OverlapLayoutEngine] (+/-18deg vs
 * +/-10deg) and given generous padding, evoking photos taped onto a page.
 * Smaller person counts read best here — a handful of "pinned" photos rather
 * than a dense pile.
 */
class ScrapbookLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.SCRAPBOOK)
    override val supportedPeopleRange: IntRange = 3..12

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)

            val margin = 0.08f
            val cellW = (1f - margin * 2f) / cols
            val cellH = (1f - margin * 2f) / rows
            // slightly smaller than the cell so the rotation doesn't push tiles
            // outside their allotted region too aggressively
            val tileW = cellW * 0.82f
            val tileH = cellH * 0.82f

            val slots = ArrayList<LayoutSlot>(n)
            var placed = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (placed >= n) break
                    val cx = margin + c * cellW + cellW / 2f
                    val cy = margin + r * cellH + cellH / 2f
                    val rotation = stableSigned(placed) * 18f

                    slots += LayoutSlot(
                        x = (cx - tileW / 2f).coerceIn(0f, 1f - tileW),
                        y = (cy - tileH / 2f).coerceIn(0f, 1f - tileH),
                        width = tileW,
                        height = tileH,
                        zIndex = placed,
                        rotationDegrees = rotation,
                        allowOverlap = true,
                        cornerRadius = 0.08f,
                        padding = 0.04f,
                        priority = n - placed,
                        role = if (placed == 0) SlotRole.PRIMARY else SlotRole.SECONDARY,
                        cropMode = CropMode.FACE_CENTERED,
                    )
                    placed++
                }
            }

            LayoutTemplate(id = "scrapbook_${n}", style = LayoutStyle.SCRAPBOOK, slots = slots)
        }

    private fun stableSigned(seed: Int): Float {
        var x = (seed + 17) * 2654435761L
        x = x xor (x ushr 13)
        x *= 0x5bd1e995L
        x = x xor (x ushr 15)
        val unit = ((x and 0xFFFFFF) / 16777216.0).toFloat()
        return unit * 2f - 1f
    }
}
