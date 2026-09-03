package com.example.ikyky.identity

import com.example.ikyky.core.model.AppearanceEmbedding
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.FaceEmbedding
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef
import com.example.ikyky.features.processing.domain.model.EmbeddedFaceObservation
import kotlin.math.cos
import kotlin.math.sin

/** Shared builders for the Phase-4 pure-Kotlin tests. */
object TestEmbeddings {

    /** A unit vector in a 8-d space at angle [thetaRad] in the first plane, plus a tiny id-specific jitter. */
    fun vec(thetaRad: Double, jitter: Float = 0f, seed: Int = 0): FaceEmbedding {
        val v = FloatArray(8)
        v[0] = cos(thetaRad).toFloat()
        v[1] = sin(thetaRad).toFloat()
        v[2] = jitter * ((seed % 3) - 1)
        v[3] = jitter * (((seed / 3) % 3) - 1)
        return FaceEmbedding.l2Normalized(v)
    }

    fun appEmb(id: String, theta: Double, jitter: Float = 0.02f, seed: Int = 0) = AppearanceEmbedding(
        appearanceId = id,
        trackletId = id.hashCode().toLong(),
        embedding = vec(theta, jitter, seed),
        memberCount = 5,
        rejectedOutliers = 0,
        meanMemberSimilarity = 0.95f,
        bestQuality = 1f,
    )

    fun ref(
        obsId: String,
        tsMs: Long,
        box: BoundingBox = BoundingBox(0, 0, 100, 100),
        q: Float = 0.9f,
        trackletId: Long = 1L,
    ) = AppearanceObservationRef(
        observationId = obsId,
        trackletId = trackletId,
        frameIndex = (tsMs / 250).toInt(),
        timestampMs = tsMs,
        canonicalBox = box,
        landmarks = emptyList(),
        qualityScore = q,
        usable = q >= 0.35f,
    )

    fun candidate(
        id: String,
        trackletId: Long,
        refs: List<AppearanceObservationRef>,
    ) = AppearanceCandidate(
        id = id,
        trackletId = trackletId,
        startTimestampMs = refs.first().timestampMs,
        endTimestampMs = refs.last().timestampMs,
        firstFrameIndex = refs.first().frameIndex,
        lastFrameIndex = refs.last().frameIndex,
        observationCount = refs.size,
        lastTrackingId = null,
        meanQuality = refs.map { it.qualityScore }.average().toFloat(),
        bestQuality = refs.maxOf { it.qualityScore },
        bestFrameIndex = refs.first().frameIndex,
        bestFrameTimestampMs = refs.first().timestampMs,
        bestFrameBox = refs.first().canonicalBox,
        observations = refs,
    )

    fun embedded(
        appearanceId: String,
        obsId: String,
        tsMs: Long,
        embedding: FaceEmbedding,
        trackletId: Long = 1L,
        q: Float = 0.9f,
    ) = EmbeddedFaceObservation(
        appearanceId = appearanceId,
        trackletId = trackletId,
        frameIndex = (tsMs / 250).toInt(),
        timestampMs = tsMs,
        observationId = obsId,
        faceBox = BoundingBox(0, 0, 100, 100),
        qualityScore = q,
        embedding = embedding,
        alignedByLandmarks = true,
    )
}
