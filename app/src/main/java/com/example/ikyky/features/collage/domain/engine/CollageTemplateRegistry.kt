package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate

/**
 * The catalog: every [LayoutEngine] the app knows about, queryable by people
 * count or by a specific template id. This is the ONLY place that knows how
 * many styles exist — callers (use cases, the ViewModel) never branch on count
 * or style themselves, they ask the registry "what fits N people" and get data
 * back.
 *
 * Adding a new visual style = adding one more [LayoutEngine] to [engines].
 * Nothing else in the collage feature changes.
 */
class CollageTemplateRegistry(private val engines: List<LayoutEngine>) {

    /**
     * Every template that can accommodate [personCount], one per engine that
     * supports it, in a stable order (engine list order — deterministic,
     * doesn't depend on a Set's iteration order or wall-clock).
     */
    fun templatesFor(personCount: Int, canvasWidth: Int, canvasHeight: Int): List<LayoutTemplate> =
        engines
            .filter { personCount in it.supportedPeopleRange }
            .mapNotNull { engine ->
                val config = LayoutConfiguration(
                    personCount = personCount,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                )
                engine.buildTemplate(config).let { r ->
                    if (r is com.example.ikyky.core.common.result.AppResult.Success) r.value else null
                }
            }

    /** All styles any registered engine can produce, for a picker UI. */
    fun availableStyles(): Set<LayoutStyle> = engines.flatMap { it.supportedStyles() }.toSet()

    /** True if at least one engine can handle this many people. */
    fun supports(personCount: Int): Boolean = engines.any { personCount in it.supportedPeopleRange }
}
