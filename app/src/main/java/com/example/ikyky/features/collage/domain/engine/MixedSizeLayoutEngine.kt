package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.model.SlotRole

/**
 * "Photo dump" composition for LARGE groups: a dense row-based pack where
 * every few tiles one is a "feature" tile spanning roughly 2x the width of its
 * neighbours, breaking up what would otherwise be a monotonous uniform grid at
 * 20-30 people. Every row is filled to exactly 1.0 width by weight, so there is
 * no leftover whitespace regardless of n.
 *
 * The feature-tile RATE (roughly 1 in every [featureEvery] tiles) is constant;
 * the row-packing arithmetic does the rest — there is no branch on a specific
 * person count anywhere in this engine.
 */
class MixedSizeLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.PHOTO_DUMP)
    override val supportedPeopleRange: IntRange = 12..40

    private val featureEvery = 5   // every 5th tile is a "feature" (2x weight)
    private val itemsPerRow = 4    // baseline items per row before weighting

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val spacing = 0.007f

            // Assign a weight to every tile: feature tiles are 2x width.
            val weights = FloatArray(n) { i -> if ((i + 1) % featureEvery == 0) 2f else 1f }

            // Greedily pack tiles into rows so each row's total weight is close to
            // `itemsPerRow` (a feature tile counts as 2 slots' worth).
            val rows = ArrayList<IntRange>()
            var rowStart = 0
            var rowWeight = 0f
            for (i in weights.indices) {
                rowWeight += weights[i]
                if (rowWeight >= itemsPerRow || i == weights.lastIndex) {
                    rows += rowStart..i
                    rowStart = i + 1
                    rowWeight = 0f
                }
            }

            val rowCount = rows.size
            val rowH = (1f - spacing * (rowCount + 1)) / rowCount

            val slots = ArrayList<LayoutSlot>(n)
            for ((ri, range) in rows.withIndex()) {
                val rowIndices = range.toList()
                val totalWeight = rowIndices.sumOf { weights[it].toDouble() }.toFloat()
                val y = spacing + ri * (rowH + spacing)
                val rowWidthAvailable = 1f - spacing * (rowIndices.size + 1)
                var x = spacing
                for (i in rowIndices) {
                    val w = rowWidthAvailable * (weights[i] / totalWeight)
                    slots += LayoutSlot(
                        x = x,
                        y = y,
                        width = w,
                        height = rowH,
                        cornerRadius = 0.03f,
                        priority = n - i,
                        role = if (weights[i] > 1f) SlotRole.PRIMARY else SlotRole.SECONDARY,
                    )
                    x += w + spacing
                }
            }

            LayoutTemplate(id = "mixed_${n}", style = LayoutStyle.PHOTO_DUMP, slots = slots)
        }
}
