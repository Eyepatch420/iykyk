package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.people.domain.model.SuspicionReport

/**
 * Decides which appearances deserve the expensive **dense** temporal-split
 * analysis (Phase 4.5). Everything else keeps the normal Phase-3 embedding
 * budget and the cheap Phase-4 splitter.
 *
 * An appearance is suspicious if ANY of:
 *  - duration > [PipelineDefaults.DENSE_SUSPICIOUS_MIN_DURATION_MS]
 *    (a *trigger for analysis only* — long ≠ must-split, there is no duration cap);
 *  - global-embedding compactness (mean pairwise cosine of its ≤5 Phase-3
 *    embeddings) < [PipelineDefaults.DENSE_SUSPICIOUS_COMPACTNESS_MAX];
 *  - the biggest drop between consecutive (time-ordered) global-embedding
 *    cosines ≥ [PipelineDefaults.DENSE_SUSPICIOUS_DRIFT_MIN].
 *
 * Pure Kotlin, deterministic.
 */
class SuspiciousAppearanceSelector(
    private val minDurationMs: Long = PipelineDefaults.DENSE_SUSPICIOUS_MIN_DURATION_MS,
    private val compactnessMax: Float = PipelineDefaults.DENSE_SUSPICIOUS_COMPACTNESS_MAX,
    private val driftMin: Float = PipelineDefaults.DENSE_SUSPICIOUS_DRIFT_MIN,
) {

    fun evaluate(
        appearances: List<AppearanceCandidate>,
        embeddingsByAppearance: Map<String, List<EmbeddedFaceObservation>>,
    ): List<SuspicionReport> = appearances.map { ap ->
        val emb = embeddingsByAppearance[ap.id]
            ?.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
            .orEmpty()

        val compactness = meanPairwiseCos(emb)
        val maxDrop = maxNeighborDrop(emb)
        val durationMs = ap.endTimestampMs - ap.startTimestampMs

        val reasons = buildList {
            if (durationMs > minDurationMs) add("duration ${durationMs}ms > ${minDurationMs}ms")
            if (emb.size >= 2 && compactness < compactnessMax) add("compactness ${"%.2f".format(compactness)} < ${compactnessMax}")
            if (maxDrop >= driftMin) add("neighbor drop ${"%.2f".format(maxDrop)} >= ${driftMin}")
        }

        SuspicionReport(
            appearanceId = ap.id,
            trackletId = ap.trackletId,
            durationMs = durationMs,
            globalCompactness = compactness,
            maxNeighborDrop = maxDrop,
            suspicious = reasons.isNotEmpty(),
            reasons = reasons,
        )
    }

    private fun meanPairwiseCos(emb: List<EmbeddedFaceObservation>): Float {
        if (emb.size < 2) return 1f
        var sum = 0f
        var count = 0
        for (i in emb.indices) for (j in i + 1 until emb.size) {
            sum += emb[i].embedding.cosineSimilarity(emb[j].embedding)
            count++
        }
        return sum / count
    }

    private fun maxNeighborDrop(emb: List<EmbeddedFaceObservation>): Float {
        if (emb.size < 3) return 0f
        val cos = (0 until emb.size - 1).map {
            emb[it].embedding.cosineSimilarity(emb[it + 1].embedding)
        }
        var maxDrop = 0f
        for (i in 0 until cos.size - 1) {
            val d = cos[i] - cos[i + 1]
            if (d > maxDrop) maxDrop = d
        }
        // also count a single low neighbour-cosine as a "drop" vs the series median
        val median = cos.sorted()[cos.size / 2]
        val worst = median - cos.min()
        return maxOf(maxDrop, worst)
    }
}
