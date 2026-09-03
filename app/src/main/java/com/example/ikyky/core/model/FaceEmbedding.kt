package com.example.ikyky.core.model

import kotlin.math.sqrt

/**
 * An L2-normalized face identity vector produced by the on-device embedding model.
 * The runtime tensor type (LiteRT / ONNX) never escapes the ML layer.
 */
data class FaceEmbedding(
    val vector: FloatArray,
) {
    val dimension: Int get() = vector.size

    /** Cosine similarity. Assumes both vectors are L2-normalized. */
    fun cosineSimilarity(other: FaceEmbedding): Float {
        require(dimension == other.dimension) {
            "Embedding dimension mismatch: $dimension vs ${other.dimension}"
        }
        var dot = 0f
        for (i in vector.indices) dot += vector[i] * other.vector[i]
        return dot
    }

    fun isFinite(): Boolean = vector.all { it.isFinite() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbedding) return false
        return vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = vector.contentHashCode()

    companion object {
        /**
         * Cosine similarity of two embeddings. If both are already L2-normalized
         * this is just their dot product; this form also normalizes defensively
         * so it is safe for raw diagnostics. Returns 0f if either has zero norm.
         */
        fun cosineSimilarity(a: FaceEmbedding, b: FaceEmbedding): Float {
            require(a.dimension == b.dimension) {
                "Embedding dimension mismatch: ${a.dimension} vs ${b.dimension}"
            }
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.vector.indices) {
                dot += a.vector[i] * b.vector[i]
                na += a.vector[i] * a.vector[i]
                nb += b.vector[i] * b.vector[i]
            }
            val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
            return if (denom == 0f || !denom.isFinite()) 0f else dot / denom
        }

        fun l2Normalized(raw: FloatArray): FaceEmbedding {
            var norm = 0f
            for (v in raw) norm += v * v
            norm = sqrt(norm)
            if (norm == 0f || !norm.isFinite()) return FaceEmbedding(raw.copyOf())
            val out = FloatArray(raw.size) { raw[it] / norm }
            return FaceEmbedding(out)
        }

        /** Centroid of a set of embeddings, re-normalized (used for aggregation). */
        fun centroid(embeddings: List<FaceEmbedding>): FaceEmbedding {
            require(embeddings.isNotEmpty()) { "Cannot compute centroid of empty list" }
            val dim = embeddings.first().dimension
            val acc = FloatArray(dim)
            for (e in embeddings) for (i in 0 until dim) acc[i] += e.vector[i]
            for (i in 0 until dim) acc[i] /= embeddings.size
            return l2Normalized(acc)
        }
    }
}
