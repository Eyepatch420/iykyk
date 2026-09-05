package com.example.ikyky.features.collage.domain.engine

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutStyle
import com.example.ikyky.features.collage.domain.model.LayoutTemplate

/**
 * Produces a [LayoutTemplate] with at least [LayoutConfiguration.personCount]
 * slots for the given configuration.
 *
 * Implementations may pick from a catalog of hand-authored templates or generate
 * slots procedurally (e.g. masonry / photo-dump). The rest of the pipeline is
 * count-agnostic: it asks for a template, gets slots back, and fills them.
 */
interface LayoutEngine {
    fun buildTemplate(config: LayoutConfiguration): AppResult<LayoutTemplate>

    /** Styles this engine can currently produce. */
    fun supportedStyles(): Set<LayoutStyle>

    /**
     * The people counts this engine is a sensible choice for. A registry uses
     * this to decide which engines to offer for a given count — the engine
     * itself never sees "if count == N" branching to decide its own slots,
     * this range only gates WHETHER it's asked at all.
     */
    val supportedPeopleRange: IntRange
}

/**
 * Chooses which [LayoutStyle] suits a configuration when the caller hasn't
 * forced one. Kept separate from [LayoutEngine] so selection policy can evolve
 * independently of slot generation.
 */
interface LayoutStyleSelector {
    fun select(config: LayoutConfiguration): LayoutStyle
}
