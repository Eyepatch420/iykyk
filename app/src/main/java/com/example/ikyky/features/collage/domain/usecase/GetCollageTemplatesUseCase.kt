package com.example.ikyky.features.collage.domain.usecase

import com.example.ikyky.features.collage.domain.engine.CollageTemplateRegistry
import com.example.ikyky.features.collage.domain.model.LayoutTemplate

/**
 * Presentation-facing lookup: "what templates fit this many people". The
 * ViewModel calls this instead of touching [CollageTemplateRegistry] directly
 * so the presentation layer never needs to know templates come from a registry
 * of pluggable engines at all.
 */
class GetCollageTemplatesUseCase(private val registry: CollageTemplateRegistry) {

    operator fun invoke(personCount: Int, canvasWidth: Int, canvasHeight: Int): List<LayoutTemplate> =
        registry.templatesFor(personCount, canvasWidth, canvasHeight)
}
