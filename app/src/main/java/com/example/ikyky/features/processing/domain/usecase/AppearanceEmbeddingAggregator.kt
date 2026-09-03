package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation

/**
 * Combines an appearance's per-observation embeddings into one stable
 * [AppearanceEmbedding].
 *
 * Two modes, selected by [mode]:
 *
 *  - [Mode.MEAN] — **the frozen Phase 5I behaviour.** Plain mean of the
 *    L2-normalized members, re-normalized. Phase 5H compared mean / median /
 *    medoid / quality-weighted aggregation and found all four within 0.01 AUC of
 *    each other; mean is the cheapest and the most deterministic, so it was
 *    frozen (§10 decision 5). This is the default.
 *  - [Mode.ROBUST_MEAN] — the older Phase 3 behaviour, kept for the diagnostic
 *    call sites that still compare against it: plain mean → drop members below
 *    [PipelineDefaults.EMBEDDING_OUTLIER_COSINE_FLOOR] (while at least
 *    [PipelineDefaults.MIN_INLIERS_FOR_ROBUST_MEAN] remain) → re-mean.
 *
 * The outlier pass is NOT part of the validated pipeline. It was never wrong,
 * just unnecessary: with the shot-aware tracker's appearance gate already
 * rejecting incompatible observations *before* they enter a tracklet, there is
 * essentially nothing left for a second outlier filter to remove.
 */
class AppearanceEmbeddingAggregator(
    private val mode: Mode = Mode.MEAN,
    private val outlierFloor: Float = PipelineDefaults.EMBEDDING_OUTLIER_COSINE_FLOOR,
    private val minInliers: Int = PipelineDefaults.MIN_INLIERS_FOR_ROBUST_MEAN,
) {

    enum class Mode {
        /** Frozen Phase 5I: plain mean of L2-normalized members, re-normalized. */
        MEAN,

        /** Legacy Phase 3: mean with one outlier-rejection pass. */
        ROBUST_MEAN,
    }

    fun aggregate(
        appearanceId: String,
        trackletId: Long,
        members: List<EmbeddedFaceObservation>,
    ): AppearanceEmbedding? {
        if (members.isEmpty()) return null

        val vectors = members.map { it.embedding }

        if (mode == Mode.MEAN) {
            val centroid = FaceEmbedding.centroid(vectors)
            val meanSim = vectors.map { it.cosineSimilarity(centroid) }.average().toFloat()
            return AppearanceEmbedding(
                appearanceId = appearanceId,
                trackletId = trackletId,
                embedding = centroid,
                memberCount = members.size,
                rejectedOutliers = 0,
                meanMemberSimilarity = meanSim,
                bestQuality = members.maxOf { it.qualityScore },
            )
        }

        val provisional = FaceEmbedding.centroid(vectors)

        val sims = vectors.map { it.cosineSimilarity(provisional) }
        val inliers = members.filterIndexed { i, _ -> sims[i] >= outlierFloor }

        val kept = if (inliers.size >= minInliers && inliers.size < members.size) inliers else members
        val rejected = members.size - kept.size

        val finalCentroid = FaceEmbedding.centroid(kept.map { it.embedding })
        val meanSim = kept
            .map { it.embedding.cosineSimilarity(finalCentroid) }
            .average()
            .toFloat()

        return AppearanceEmbedding(
            appearanceId = appearanceId,
            trackletId = trackletId,
            embedding = finalCentroid,
            memberCount = kept.size,
            rejectedOutliers = rejected,
            meanMemberSimilarity = meanSim,
            bestQuality = members.maxOf { it.qualityScore },
        )
    }
}
