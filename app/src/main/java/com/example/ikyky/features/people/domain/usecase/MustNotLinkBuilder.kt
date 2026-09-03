package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.people.domain.model.MustNotLinkEdge

/**
 * Derives **must-not-link** constraints from *observation-level* simultaneity.
 *
 * Two appearance candidates cannot be the same person if, at the same sampled
 * frame (index within [PipelineDefaults.MUST_NOT_LINK_FRAME_TOLERANCE]), they
 * each hold a face observation and those two faces are genuinely distinct — i.e.
 * their canonical boxes overlap by no more than
 * [PipelineDefaults.MUST_NOT_LINK_MAX_IOU]. (High IoU would mean the same face
 * detected twice, not two people.)
 *
 * This is stricter than "their timestamp ranges overlap": it requires actual
 * concurrent observations of different faces. A must-not-link edge always
 * overrides embedding similarity in the clusterer.
 *
 * Pure Kotlin, deterministic (edges sorted by appearance ids then frame index).
 */
class MustNotLinkBuilder(
    private val frameTolerance: Int = PipelineDefaults.MUST_NOT_LINK_FRAME_TOLERANCE,
    private val maxIou: Float = PipelineDefaults.MUST_NOT_LINK_MAX_IOU,
) {

    fun build(appearances: List<AppearanceCandidate>): List<MustNotLinkEdge> {
        // index observations by frame for cheap lookup
        val edges = LinkedHashMap<Pair<String, String>, MustNotLinkEdge>()

        for (a in appearances.indices) {
            for (b in a + 1 until appearances.size) {
                val apA = appearances[a]
                val apB = appearances[b]
                // quick reject: frame-index ranges can't be near each other
                if (apA.lastFrameIndex + frameTolerance < apB.firstFrameIndex ||
                    apB.lastFrameIndex + frameTolerance < apA.firstFrameIndex
                ) continue

                val edge = firstConcurrentDistinctFace(apA, apB) ?: continue
                val key = if (apA.id <= apB.id) apA.id to apB.id else apB.id to apA.id
                edges.putIfAbsent(key, edge)
            }
        }
        return edges.values.sortedWith(
            compareBy({ it.key.first }, { it.key.second }, { it.frameIndex }),
        )
    }

    private fun firstConcurrentDistinctFace(
        a: AppearanceCandidate,
        b: AppearanceCandidate,
    ): MustNotLinkEdge? {
        val bByFrame = b.observations.groupBy { it.frameIndex }
        // iterate a's observations in time order for determinism
        for (oa in a.observations.sortedWith(compareBy({ it.frameIndex }, { it.observationId }))) {
            for (df in -frameTolerance..frameTolerance) {
                val candidates = bByFrame[oa.frameIndex + df] ?: continue
                for (ob in candidates.sortedBy { it.observationId }) {
                    if (ob.observationId == oa.observationId) continue // same detection
                    val iou = oa.canonicalBox.iou(ob.canonicalBox)
                    if (iou <= maxIou) {
                        return MustNotLinkEdge(
                            appearanceIdA = a.id,
                            appearanceIdB = b.id,
                            frameIndex = oa.frameIndex,
                            timestampMs = oa.timestampMs,
                            iou = iou,
                        )
                    }
                }
            }
        }
        return null
    }
}
