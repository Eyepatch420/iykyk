package com.example.ikyky.core.model

/**
 * The aggregated identity vector for one appearance (one continuous visible
 * segment of a face). Produced in Phase 3 by embedding several observations of
 * the appearance and combining them robustly.
 *
 * Framework-free: no `Tensor` / `Interpreter` / `Bitmap` here. Global identity
 * clustering (Phase 4) consumes these; Phase 3 does NOT create `Person`s.
 *
 * @property appearanceId  the [com.example.ikyky.features.processing.domain.model.AppearanceCandidate] id
 * @property trackletId    originating short-term tracklet id (debug / traceability)
 * @property embedding     L2-normalized centroid of the member embeddings
 * @property memberCount   how many per-observation embeddings survived aggregation
 * @property rejectedOutliers   member embeddings dropped as outliers
 * @property meanMemberSimilarity  mean cosine of the kept members to [embedding]
 *                                  (a compactness score: ~1 tight, lower = noisy)
 * @property bestQuality   best Phase-2 quality score among the embedded observations
 */
data class AppearanceEmbedding(
    val appearanceId: String,
    val trackletId: Long,
    val embedding: FaceEmbedding,
    val memberCount: Int,
    val rejectedOutliers: Int,
    val meanMemberSimilarity: Float,
    val bestQuality: Float,
) {
    val dimension: Int get() = embedding.dimension

    fun cosineSimilarity(other: AppearanceEmbedding): Float =
        embedding.cosineSimilarity(other.embedding)
}
