package com.example.ikyky.features.collage.domain.model

/**
 * A data-driven collage template: an ordered list of [LayoutSlot]s plus styling
 * metadata. Adding a new visual style = adding a template (or a generator that
 * emits one), never adding an `if (people == N)` branch.
 */
data class LayoutTemplate(
    val id: String,
    val style: LayoutStyle,
    val slots: List<LayoutSlot>,
    val backgroundColor: Long = 0xFFFFFFFF,
    val outerPadding: Float = 0.02f,     // fraction of canvas
    val slotSpacing: Float = 0.01f,      // fraction of canvas
) {
    val capacity: Int get() = slots.size
}

enum class LayoutStyle {
    GRID,
    ASYMMETRIC,
    MASONRY,
    HERO,
    EDITORIAL,
    OVERLAP,
    SCRAPBOOK,
    PHOTO_DUMP,
}

/**
 * Inputs that drive template selection/generation. The engine picks or builds a
 * template from these — count is just one signal, never a switch.
 */
data class LayoutConfiguration(
    val personCount: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val preferredStyle: LayoutStyle? = null,
    val seed: Long = 0L,
)
