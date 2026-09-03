package com.example.ikyky.core.ml.clustering

import com.example.ikyky.core.common.constants.PipelineDefaults
import com.example.ikyky.core.model.FaceEmbedding

/**
 * One item handed to the clusterer: an appearance-level aggregated embedding
 * plus a caller-defined id so the result can be mapped back.
 */
data class ClusterItem(
    val id: String,
    val embedding: FaceEmbedding,
)

/**
 * The outcome of clustering: each cluster is a set of [ClusterItem] ids believed
 * to be one unique person, with a representative (centroid) embedding.
 */
data class ClusteringResult(
    val clusters: List<Cluster>,
) {
    val uniquePeopleCount: Int get() = clusters.size

    data class Cluster(
        val label: Int,
        val itemIds: List<String>,
        val centroid: FaceEmbedding,
    )
}

/**
 * Groups appearance-level embeddings into unique identities.
 *
 * Design intent: agglomerative / hierarchical clustering with a cosine-similarity
 * merge threshold, suitable for the small number of appearance embeddings we
 * expect (tens, not thousands). No vector database. Phase 1 fixes the contract
 * only; [PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD] is a placeholder to be
 * calibrated on the sample videos.
 */
interface IdentityClusterer {
    fun cluster(
        items: List<ClusterItem>,
        mergeCosineThreshold: Float = PipelineDefaults.IDENTITY_MERGE_COSINE_THRESHOLD,
    ): ClusteringResult
}
