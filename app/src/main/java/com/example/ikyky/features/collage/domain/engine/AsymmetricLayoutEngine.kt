package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.common.result.runCatchingResult
import com.example.ikyky.features.collage.domain.model.CropMode
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import com.example.ikyky.features.collage.domain.model.SlotRole

/**
 * Magazine-style asymmetric column layout: alternating wide/narrow bands,
 * one big "feature" tile per band and 1-2 smaller companions — the classic
 * editorial mix of one large photo next to two stacked smaller ones, repeated
 * down the canvas. Every dimension is computed from [n] and a fixed band
 * pattern (`[BIG, SMALL, SMALL]`, `[SMALL, BIG]`, ...), never a literal count
 * check.
 */
class AsymmetricLayoutEngine : LayoutEngine {

    override fun supportedStyles(): Set<LayoutStyle> = setOf(LayoutStyle.ASYMMETRIC)
    override val supportedPeopleRange: IntRange = 3..18

    /** One repeating band pattern: how many people it consumes and their relative width weights. */
    private val bandPatterns = listOf(
        listOf(2f, 1f),       // big left, small right
        listOf(1f, 1f, 1f),   // three even
        listOf(1f, 2f),       // small left, big right
    )

    override fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate> =
        runCatchingResult {
            val n = config.personCount.coerceAtLeast(1)
            val spacing = 0.012f
            val slots = ArrayList<LayoutSlot>(n)

            var remaining = n
            var bandIndex = 0
            val bandHeights = ArrayList<Int>() // people-per-band, to compute row heights later
            val chosenPatterns = ArrayList<List<Float>>()
            while (remaining > 0) {
                val pattern = bandPatterns[bandIndex % bandPatterns.size]
                val take = minOf(pattern.size, remaining)
                chosenPatterns += pattern.take(take)
                bandHeights += take
                remaining -= take
                bandIndex++
            }

            val bandCount = chosenPatterns.size
            val bandH = (1f - spacing * (bandCount + 1)) / bandCount

            var personIdx = 0
            for ((bi, weights) in chosenPatterns.withIndex()) {
                val y = spacing + bi * (bandH + spacing)
                val totalWeight = weights.sum()
                var x = spacing
                val bandWidth = 1f - spacing * (weights.size + 1)
                for (w in weights) {
                    val slotW = bandWidth * (w / totalWeight)
                    slots += LayoutSlot(
                        x = x,
                        y = y,
                        width = slotW,
                        height = bandH,
                        cornerRadius = 0.025f,
                        priority = n - personIdx,
                        role = if (w == weights.max() && bi == 0) SlotRole.PRIMARY else SlotRole.SECONDARY,
                        cropMode = CropMode.FACE_CENTERED,
                    )
                    x += slotW + spacing
                    personIdx++
                }
            }

            LayoutTemplate(id = "asymmetric_${n}", style = LayoutStyle.ASYMMETRIC, slots = slots)
        }
}
