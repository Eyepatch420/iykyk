package com.example.ikyky.features.people.domain.usecase

import android.graphics.Bitmap
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.VideoFrameExtractor
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.ml.preprocessing.SimilarityTransformFaceAligner
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.VideoFrame
import com.example.ikyky.core.storage.RepresentativeImageStorage
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.model.RepresentativeFrame
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate

/**
 * Picks and persists **one representative crop per [Person]**, populating the
 * previously-always-null [Person.representativeFrame].
 *
 * Selection is a lookup, not a new algorithm: for each person, take the
 * [AppearanceCandidate] among their appearances with the highest
 * [AppearanceCandidate.bestQuality] (already computed by Phase 2's
 * `AppearanceCandidate.fromObservations` — `observations.maxBy { it.qualityScore }`),
 * then re-decode that appearance's already-recorded
 * [AppearanceCandidate.bestFrameTimestampMs] and crop
 * [AppearanceCandidate.bestFrameBox] with the margin-expanded **presentation**
 * geometry ([SimilarityTransformFaceAligner.presentationCrop] — un-aligned, more
 * generous than the 112x112 recognition crop, and deliberately NOT the
 * `ArcFaceFivePointAligner` the frozen recognition path uses; mixing the two
 * crops is exactly the confusion the Phase 6 crop-naming discipline exists to
 * prevent).
 *
 * One frame is decoded and cropped per PERSON (not per appearance, not per
 * observation) — for a typical 5-10 person result that's 5-10 extra decodes,
 * negligible next to the ~750-frame shot scan. The produced bitmap is written
 * to [RepresentativeImageStorage] and immediately released; nothing here
 * retains a `Bitmap` past this function call, matching the Phase 6 lifecycle
 * discipline (one working bitmap at a time, no retention past its use).
 */
class SelectRepresentativeImagesUseCase(
    private val frameExtractor: VideoFrameExtractor,
    private val aligner: SimilarityTransformFaceAligner,
    private val storage: RepresentativeImageStorage,
    private val logger: Logger,
) {

    suspend fun select(
        sessionId: String,
        uriString: String,
        metadata: VideoMetadata,
        people: List<Person>,
        candidates: List<AppearanceCandidate>,
    ): List<Person> {
        val byId = candidates.associateBy { it.id }
        return people.map { person -> withRepresentativeFrame(sessionId, uriString, metadata, person, byId) }
    }

    private suspend fun withRepresentativeFrame(
        sessionId: String,
        uriString: String,
        metadata: VideoMetadata,
        person: Person,
        byId: Map<String, AppearanceCandidate>,
    ): Person {
        val best = person.appearanceIds
            .mapNotNull { byId[it] }
            .maxByOrNull { it.bestQuality }
            ?: return person

        val frame: VideoFrame = frameExtractor.decodeFrameAt(uriString, metadata, best.bestFrameTimestampMs)
            ?: run {
                logger.w(TAG, "${person.id}: could not re-decode @${best.bestFrameTimestampMs}ms")
                return person
            }

        return try {
            val s = frame.geometry.scale
            val faceInDecoded = DetectedFace(
                boundingBox = scaleBox(best.bestFrameBox, s),
            )
            val crop: Bitmap = aligner.presentationCrop(frame.bitmap, faceInDecoded)
                ?: run {
                    logger.w(TAG, "${person.id}: presentation crop unusable")
                    return person
                }
            val saved = try {
                storage.save(sessionId, person.id, crop)
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
            when (saved) {
                is AppResult.Success -> person.copy(
                    representativeFrame = RepresentativeFrame(
                        personId = person.id,
                        sourceObservationId = best.id,
                        frameIndex = best.bestFrameIndex,
                        timestampMs = best.bestFrameTimestampMs,
                        presentationCropKey = saved.value.toString(),
                        qualityScore = best.bestQuality,
                    ),
                )
                is AppResult.Failure -> {
                    logger.w(TAG, "${person.id}: failed to persist representative image: ${saved.error.message}")
                    person
                }
            }
        } finally {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
        }
    }

    private fun scaleBox(box: BoundingBox, s: Float): BoundingBox = BoundingBox(
        left = (box.left * s).toInt(),
        top = (box.top * s).toInt(),
        right = (box.right * s).toInt(),
        bottom = (box.bottom * s).toInt(),
    )

    private companion object {
        const val TAG = "SelectRepresentativeImages"
    }
}
