package com.example.ikyky.features.processing.domain.usecase

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation

/**
 * Combines an appearance's per-observation embeddings into one stable
 * [AppearanceEmbedding].
 *
 * Robust-but-simple (Phase 3 step 17):
 *  1. plain mean of the L2-normalized members → provisional centroid;
 *  2. drop members whose cosine similarity to that centroid is below
 *     [PipelineDefaults.EMBEDDING_OUTLIER_COSINE_FLOOR], as long as at least
 *     [PipelineDefaults.MIN_INLIERS_FOR_ROBUST_MEAN] remain;
 *  3. re-mean the survivors and L2-normalize → final centroid.
 *
 * No covariance / medoid / iterative re-weighting — one outlier pass is enough
 * to stop a single bad frame from dragging the identity vector.
 */
class AppearanceEmbeddingAggregator(
    private val outlierFloor: Float = PipelineDefaults.EMBEDDING_OUTLIER_COSINE_FLOOR,
    private val minInliers: Int = PipelineDefaults.MIN_INLIERS_FOR_ROBUST_MEAN,
) {

    fun aggregate(
        appearanceId: String,
        trackletId: Long,
        members: List<EmbeddedFaceObservation>,
    ): AppearanceEmbedding? {
        if (members.isEmpty()) return null

        val vectors = members.map { it.embedding }
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
