package com.example.ikyky.collage

import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.features.collage.domain.engine.AsymmetricLayoutEngine
import com.example.ikyky.features.collage.domain.engine.CollageTemplateRegistry
import com.example.ikyky.features.collage.domain.engine.GridLayoutEngine
import com.example.ikyky.features.collage.domain.engine.HeroLayoutEngine
import com.example.ikyky.features.collage.domain.engine.LayoutEngine
import com.example.ikyky.features.collage.domain.engine.MasonryLayoutEngine
import com.example.ikyky.features.collage.domain.engine.MixedSizeLayoutEngine
import com.example.ikyky.features.collage.domain.engine.OverlapLayoutEngine
import com.example.ikyky.features.collage.domain.engine.ScrapbookLayoutEngine
import com.example.ikyky.features.collage.domain.engine.StaggeredLayoutEngine
import com.example.ikyky.features.collage.domain.model.LayoutConfiguration
import com.example.ikyky.features.collage.domain.model.LayoutSlot
import com.example.ikyky.features.collage.domain.model.LayoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 — the geometry contract every [LayoutEngine] must satisfy, checked
 * across ALL engines and a representative spread of people counts (3, 5, 10,
 * 15, 20, 25, 30). No engine-specific branching here: this is exactly the
 * "renderer never special-cases a template" contract applied to tests instead.
 */
class LayoutEngineGeometryTest {

    private val allEngines: List<LayoutEngine> = listOf(
        GridLayoutEngine(), HeroLayoutEngine(), AsymmetricLayoutEngine(),
        StaggeredLayoutEngine(), MasonryLayoutEngine(), OverlapLayoutEngine(),
        ScrapbookLayoutEngine(), MixedSizeLayoutEngine(),
    )

    private val counts = listOf(3, 5, 10, 15, 20, 25, 30)

    private fun config(n: Int) = LayoutConfiguration(personCount = n, canvasWidth = 1080, canvasHeight = 1350)

    private fun buildAll(): List<Pair<LayoutEngine, LayoutTemplate>> =
        allEngines.flatMap { engine ->
            counts.filter { it in engine.supportedPeopleRange }.mapNotNull { n ->
                when (val r = engine.buildTemplate(config(n))) {
                    is AppResult.Success -> engine to r.value
                    is AppResult.Failure -> null
                }
            }
        }

    @Test
    fun everyEngineSupportsAtLeastOneOfTheRequiredCounts() {
        for (engine in allEngines) {
            assertTrue(
                "${engine::class.simpleName} supports none of $counts (range=${engine.supportedPeopleRange})",
                counts.any { it in engine.supportedPeopleRange },
            )
        }
    }

    @Test
    fun normalizedSlotBounds_everySlotStaysWithinTheCanvas() {
        for ((engine, template) in buildAll()) {
            for (slot in template.slots) {
                assertTrue("${engine::class.simpleName}/${template.id}: x>=0 failed for $slot", slot.x >= -1e-4f)
                assertTrue("${engine::class.simpleName}/${template.id}: y>=0 failed for $slot", slot.y >= -1e-4f)
                assertTrue("${engine::class.simpleName}/${template.id}: width>0 failed for $slot", slot.width > 0f)
                assertTrue("${engine::class.simpleName}/${template.id}: height>0 failed for $slot", slot.height > 0f)

                // The brief allows deliberate overlap between slots, but only for
                // templates that opt in; even then a slot must stay ON the canvas
                // (small floating-point slack for the overlap engines' growth math).
                val slack = if (slot.allowOverlap) 0.05f else 1e-3f
                assertTrue(
                    "${engine::class.simpleName}/${template.id}: x+width<=1 failed for $slot",
                    slot.x + slot.width <= 1f + slack,
                )
                assertTrue(
                    "${engine::class.simpleName}/${template.id}: y+height<=1 failed for $slot",
                    slot.y + slot.height <= 1f + slack,
                )
            }
        }
    }

    @Test
    fun everyTemplateHasExactlyOneSlotPerPerson() {
        for ((engine, template) in buildAll()) {
            val n = template.id.substringAfterLast('_').toIntOrNull()
            if (n != null) {
                assertEquals("${engine::class.simpleName}/${template.id}", n, template.slots.size)
            }
        }
    }

    @Test
    fun zIndexOrderingIsWellDefined_noNegativeValues() {
        for ((_, template) in buildAll()) {
            for (slot in template.slots) {
                assertTrue(slot.zIndex >= 0)
            }
        }
    }

    @Test
    fun overlapEnginesActuallyUseZIndexToDifferentiateStackOrder() {
        val overlap = OverlapLayoutEngine()
        val template = (overlap.buildTemplate(config(6)) as AppResult.Success).value
        val zIndices = template.slots.map { it.zIndex }
        assertEquals("z-index should differentiate paint order", zIndices.distinct().size, zIndices.size)
    }

    @Test
    fun rotationMetadata_isPresentOnlyOnEnginesThatIntendIt_andWithinASaneRange() {
        for ((engine, template) in buildAll()) {
            for (slot in template.slots) {
                assertTrue(
                    "${engine::class.simpleName}: rotation ${slot.rotationDegrees} out of [-45,45]",
                    slot.rotationDegrees in -45f..45f,
                )
            }
        }
        // Grid/masonry/mixed are axis-aligned by design.
        val grid = (GridLayoutEngine().buildTemplate(config(9)) as AppResult.Success).value
        assertTrue(grid.slots.all { it.rotationDegrees == 0f })

        // Overlap/scrapbook deliberately rotate.
        val scrapbook = (ScrapbookLayoutEngine().buildTemplate(config(6)) as AppResult.Success).value
        assertTrue(scrapbook.slots.any { it.rotationDegrees != 0f })
    }

    @Test
    fun deterministicTemplateOutput_sameInputSameOutput() {
        for (engine in allEngines) {
            val n = counts.first { it in engine.supportedPeopleRange }
            val a = (engine.buildTemplate(config(n)) as AppResult.Success).value
            val b = (engine.buildTemplate(config(n)) as AppResult.Success).value
            assertEquals("${engine::class.simpleName} must be deterministic", a, b)
        }
    }

    @Test
    fun deterministicSlotOrdering_sameInputSameOrder() {
        for (engine in allEngines) {
            val n = counts.first { it in engine.supportedPeopleRange }
            val a = (engine.buildTemplate(config(n)) as AppResult.Success).value.slots
            val b = (engine.buildTemplate(config(n)) as AppResult.Success).value.slots
            assertEquals(a.map { it.x to it.y }, b.map { it.x to it.y })
        }
    }

    @Test
    fun aspectRatioPreference_ifSetIsPositive() {
        for ((_, template) in buildAll()) {
            for (slot in template.slots) {
                slot.aspectPreference?.let { assertTrue(it > 0f) }
            }
        }
    }

    // =======================================================================
    // REGISTRY
    // =======================================================================

    private val registry = CollageTemplateRegistry(allEngines)

    @Test
    fun registryLookup_returnsTemplatesForEachRequiredCount() {
        for (n in counts) {
            val templates = registry.templatesFor(n, 1080, 1350)
            assertTrue("no templates for $n people", templates.isNotEmpty())
        }
    }

    @Test
    fun registryLookup_multipleVariantsWherePractical() {
        // 5 and 10 both sit inside several engines' ranges -- prove the
        // registry actually surfaces more than one style there, not just one.
        assertTrue(registry.templatesFor(5, 1080, 1350).map { it.style }.distinct().size >= 2)
        assertTrue(registry.templatesFor(10, 1080, 1350).map { it.style }.distinct().size >= 2)
    }

    @Test
    fun registryLookup_isDeterministicOrdering() {
        val a = registry.templatesFor(10, 1080, 1350).map { it.id }
        val b = registry.templatesFor(10, 1080, 1350).map { it.id }
        assertEquals(a, b)
    }

    @Test
    fun peopleCountSupport_moreThanAnyTemplateSupports_returnsEmpty() {
        assertTrue(registry.templatesFor(500, 1080, 1350).isEmpty())
        assertTrue(!registry.supports(500))
    }

    @Test
    fun peopleCountSupport_zeroPeople_doesNotCrashAndReturnsEmptyOrGraceful() {
        // Zero people is a caller-level error (handled by the use case, tested
        // separately) -- the registry itself must simply not throw.
        val templates = registry.templatesFor(0, 1080, 1350)
        assertTrue(templates.isEmpty() || templates.all { it.slots.isEmpty() })
    }
}
