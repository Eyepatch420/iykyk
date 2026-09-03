package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef

/**
 * Chooses which observations of an appearance to embed.
 *
 * Goals (Phase 3 steps 14–16):
 *  - **quality-aware**: prefer good frames; weak frames get lower priority, not a
 *    hard ban — a legitimate short appearance must never end up with zero
 *    embeddings ([PipelineDefaults.MIN_EMBEDDINGS_PER_APPEARANCE]);
 *  - **temporally diverse**: spread the picks across the appearance's duration
 *    rather than taking N adjacent frames, so pose/expression variation is
 *    represented;
 *  - **bounded**: at most [PipelineDefaults.MAX_EMBEDDINGS_PER_APPEARANCE].
 *
 * Pure Kotlin, deterministic, no Android.
 */
class EmbeddingSampleSelector(
    private val maxSamples: Int = PipelineDefaults.MAX_EMBEDDINGS_PER_APPEARANCE,
    private val minSamples: Int = PipelineDefaults.MIN_EMBEDDINGS_PER_APPEARANCE,
    private val minQuality: Float = PipelineDefaults.EMBEDDING_MIN_QUALITY,
) {

    fun select(observations: List<AppearanceObservationRef>): List<AppearanceObservationRef> {
        if (observations.isEmpty()) return emptyList()
        val ordered = observations.sortedBy { it.timestampMs }
        val target = maxSamples.coerceAtLeast(1)
        if (ordered.size <= target) return ordered

        // First-pass quality gate, but never let the pool fall below what we need.
        var pool = ordered.filter { it.qualityScore >= minQuality }
        if (pool.size < minSamples.coerceAtLeast(1) || pool.size < target) {
            // not enough good frames — fall back to the best `target*2` by quality
            // (still time-sorted below) so weak appearances survive.
            pool = ordered.sortedByDescending { it.qualityScore }
                .take((target * 2).coerceAtMost(ordered.size))
                .sortedBy { it.timestampMs }
        }
        if (pool.size <= target) return pool

        // Temporal-diversity pick: split the pool's time span into `target` equal
        // buckets and take the highest-quality observation from each. Empty
        // buckets are back-filled from the remaining best-quality observations.
        val start = pool.first().timestampMs
        val end = pool.last().timestampMs
        val span = (end - start).coerceAtLeast(1)
        val buckets = arrayOfNulls<AppearanceObservationRef>(target)

        for (o in pool) {
            var b = (((o.timestampMs - start).toDouble() / span) * target).toInt()
            if (b >= target) b = target - 1
            val cur = buckets[b]
            if (cur == null || o.qualityScore > cur.qualityScore) buckets[b] = o
        }

        val picked = LinkedHashSet<AppearanceObservationRef>()
        buckets.forEach { if (it != null) picked += it }

        if (picked.size < target) {
            pool.sortedByDescending { it.qualityScore }
                .forEach { if (picked.size < target) picked += it }
        }
        return picked.sortedBy { it.timestampMs }.take(target)
    }
}
