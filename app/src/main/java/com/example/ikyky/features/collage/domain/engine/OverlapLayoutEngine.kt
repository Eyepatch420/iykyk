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
 * Photo-scattered / freeform look: tiles sit on a loose grid but each is
 * enlarged slightly and nudged + rotated by a small, deterministic amount so
 * neighbours overlap at the edges — like photos tossed onto a table. Every
 * slot sets [LayoutSlot.allowOverlap] and a non-zero [LayoutSlot.zIndex] so the
 * renderer's z-sort (not special-case code) produces the correct stacking.
 *
 * Later tiles get a higher zIndex, so the composition reads left-to-right,
 * top-to-bottom as "on top" — a deliberate, deterministic rule, not visual
 * randomness.
 */
class OverlapLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.OVERLAP)
    override val supportedPeopleRange: IntRange = 3..16

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val cols = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
            val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)

            // base grid cell, then each tile is grown by `growth` beyond it so
            // adjacent tiles physically overlap at the seams
            val margin = 0.06f
            val cellW = (1f - margin * 2f) / cols
            val cellH = (1f - margin * 2f) / rows
            val growth = 1.18f
            val tileW = cellW * growth
            val tileH = cellH * growth

            val slots = ArrayList<LayoutSlot>(n)
            var placed = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (placed >= n) break
                    val cx = margin + c * cellW + cellW / 2f
                    val cy = margin + r * cellH + cellH / 2f
                    val jitterX = (stableSigned(placed * 2) * 0.03f)
                    val jitterY = (stableSigned(placed * 2 + 1) * 0.03f)
                    val rotation = stableSigned(placed * 7) * 10f // +/-10 degrees

                    slots += LayoutSlot(
                        x = (cx + jitterX - tileW / 2f).coerceIn(0f, 1f - tileW),
                        y = (cy + jitterY - tileH / 2f).coerceIn(0f, 1f - tileH),
                        width = tileW,
                        height = tileH,
                        zIndex = placed,
                        rotationDegrees = rotation,
                        allowOverlap = true,
                        cornerRadius = 0.02f,
                        priority = n - placed,
                        role = if (placed == 0) SlotRole.PRIMARY else SlotRole.SECONDARY,
                    )
                    placed++
                }
            }

            LayoutTemplate(id = "overlap_${n}", style = LayoutStyle.OVERLAP, slots = slots)
        }

    /** Deterministic pseudo-random value in [-1, 1) derived from an integer seed. */
    private fun stableSigned(seed: Int): Float {
        var x = seed * 2654435761L
        x = x xor (x ushr 13)
        x *= 0x5bd1e995L
        x = x xor (x ushr 15)
        val unit = ((x and 0xFFFFFF) / 16777216.0).toFloat() // [0,1)
        return unit * 2f - 1f
    }
}
