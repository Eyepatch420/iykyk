package com.example.ikyky.features.people.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import com.example.ikyky.features.people.domain.model.AppearanceSplitRecord

/**
 * Embedding-assisted **intra-appearance temporal split** (Phase 4, step 3 of the
 * conceptual pipeline).
 *
 * Phase 2/2.5 evidence showed some tracklets stay continuous (ML Kit holds one
 * tracking id, no ≥2-frame gap) even though the embedding drift over time
 * suggests two identities. This corrects that — conservatively.
 *
 * Method: **1-D scan for a single best change point.**
 *  - order the appearance's embedded observations by time;
 *  - for every interior cut index `k`, score
 *      `separation(k) = mean(within-left cos, within-right cos) − mean(cross cos)`;
 *  - take `k*` = argmax separation;
 *  - accept the cut only if `separation(k*) ≥ INTRA_APPEARANCE_SPLIT_MIN_MARGIN`
 *    and both sides keep ≥ `INTRA_APPEARANCE_SPLIT_MIN_SIDE_EMBEDDINGS`;
 *  - recurse into each half (up to `INTRA_APPEARANCE_SPLIT_MAX_DEPTH`).
 *
 * A false split is recoverable (clustering can re-merge the fragments); a false
 * merge destroys an appearance boundary. So the bar is deliberately high.
 *
 * Pure Kotlin, deterministic (stable ordering by timestamp then observationId).
 */
class IntraAppearanceSplitter(
    private val minMargin: Float = PipelineDefaults.INTRA_APPEARANCE_SPLIT_MIN_MARGIN,
    private val minSideEmbeddings: Int = PipelineDefaults.INTRA_APPEARANCE_SPLIT_MIN_SIDE_EMBEDDINGS,
    private val maxDepth: Int = PipelineDefaults.INTRA_APPEARANCE_SPLIT_MAX_DEPTH,
) {

    data class Result(
        val appearances: List<AppearanceCandidate>,
        val records: List<AppearanceSplitRecord>,
    )

    /**
     * @param appearances the (corrected-so-far) appearance candidates
     * @param embeddingsByAppearance per-observation embeddings, keyed by appearance id
     */
    fun split(
        appearances: List<AppearanceCandidate>,
        embeddingsByAppearance: Map<String, List<EmbeddedFaceObservation>>,
    ): Result {
        val out = ArrayList<AppearanceCandidate>()
        val records = ArrayList<AppearanceSplitRecord>()

        for (appearance in appearances) {
            val emb = embeddingsByAppearance[appearance.id]
                ?.sortedWith(compareBy({ it.timestampMs }, { it.observationId }))
                .orEmpty()
            if (emb.size < 2 * minSideEmbeddings) {
                out += appearance
                continue
            }
            val cuts = ArrayList<Long>()
            val fragments = recurse(appearance, emb, depth = 0, cuts = cuts, fragmentIndex = intArrayOf(0))
            if (fragments.size <= 1) {
                out += appearance
            } else {
                out += fragments
                records += AppearanceSplitRecord(
                    originalAppearanceId = appearance.id,
                    trackletId = appearance.trackletId,
                    resultAppearanceIds = fragments.map { it.id },
                    cutTimestampsMs = cuts.sorted(),
                    separation = lastSeparation,
                )
            }
        }
        return Result(out, records)
    }

    private var lastSeparation = 0f

    private fun recurse(
        source: AppearanceCandidate,
        emb: List<EmbeddedFaceObservation>,
        depth: Int,
        cuts: MutableList<Long>,
        fragmentIndex: IntArray,
    ): List<AppearanceCandidate> {
        val bestCut = if (depth >= maxDepth) null else bestChangePoint(emb)
        if (bestCut == null) {
            return listOf(materialize(source, emb, fragmentIndex))
        }
        val (k, separation) = bestCut
        lastSeparation = separation
        cuts += emb[k].timestampMs
        val left = emb.subList(0, k)
        val right = emb.subList(k, emb.size)
        return recurse(source, left, depth + 1, cuts, fragmentIndex) +
            recurse(source, right, depth + 1, cuts, fragmentIndex)
    }

    /** argmax separation over interior cut indices, or null if none clears the bar. */
    private fun bestChangePoint(emb: List<EmbeddedFaceObservation>): Pair<Int, Float>? {
        val n = emb.size
        if (n < 2 * minSideEmbeddings) return null
        var bestK = -1
        var bestSep = Float.NEGATIVE_INFINITY
        for (k in minSideEmbeddings..(n - minSideEmbeddings)) {
            val within = 0.5f * (meanPairwiseCos(emb, 0, k) + meanPairwiseCos(emb, k, n))
            val across = meanCrossCos(emb, k)
            val sep = within - across
            if (sep > bestSep) {
                bestSep = sep
                bestK = k
            }
        }
        return if (bestK >= 0 && bestSep >= minMargin) bestK to bestSep else null
    }

    private fun meanPairwiseCos(emb: List<EmbeddedFaceObservation>, from: Int, to: Int): Float {
        var sum = 0f
        var count = 0
        for (i in from until to) for (j in i + 1 until to) {
            sum += emb[i].embedding.cosineSimilarity(emb[j].embedding)
            count++
        }
        return if (count == 0) 1f else sum / count
    }

    private fun meanCrossCos(emb: List<EmbeddedFaceObservation>, k: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until k) for (j in k until emb.size) {
            sum += emb[i].embedding.cosineSimilarity(emb[j].embedding)
            count++
        }
        return if (count == 0) 1f else sum / count
    }

    /** Build an appearance fragment from a contiguous slice of embedded observations. */
    private fun materialize(
        source: AppearanceCandidate,
        emb: List<EmbeddedFaceObservation>,
        fragmentIndex: IntArray,
    ): AppearanceCandidate {
        val idx = fragmentIndex[0]++
        val fragId = if (idx == 0) source.id else "${source.id}_s$idx"
        val embIds = emb.map { it.observationId }.toSet()
        val embTimes = emb.map { it.timestampMs }
        val minT = embTimes.min()
        val maxT = embTimes.max()

        // Carry every source observation ref whose timestamp falls in this
        // fragment's span (so tracking observations between embedded picks are
        // kept with the right fragment).
        val refs: List<AppearanceObservationRef> = source.observations
            .filter { it.observationId in embIds || it.timestampMs in minT..maxT }
            .sortedWith(compareBy({ it.timestampMs }, { it.observationId }))

        val effectiveRefs = refs.ifEmpty {
            source.observations.filter { it.timestampMs in minT..maxT }
        }.ifEmpty { source.observations }

        val quals = effectiveRefs.map { it.qualityScore }
        val best = effectiveRefs.maxByOrNull { it.qualityScore } ?: source.observations.first()

        return source.copy(
            id = fragId,
            startTimestampMs = effectiveRefs.first().timestampMs,
            endTimestampMs = effectiveRefs.last().timestampMs,
            firstFrameIndex = effectiveRefs.first().frameIndex,
            lastFrameIndex = effectiveRefs.last().frameIndex,
            observationCount = effectiveRefs.size,
            meanQuality = if (quals.isEmpty()) source.meanQuality else quals.average().toFloat(),
            bestQuality = best.qualityScore,
            bestFrameIndex = best.frameIndex,
            bestFrameTimestampMs = best.timestampMs,
            bestFrameBox = best.canonicalBox,
            observations = effectiveRefs,
        )
    }
}
