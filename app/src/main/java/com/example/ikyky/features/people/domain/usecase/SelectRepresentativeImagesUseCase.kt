package com.example.ikyky.features.people.domain.usecase

import android.graphics.Bitmap
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.logging.Logger
import com.example.ikyky.core.media.VideoFrameExtractor
import com.example.ikyky.core.media.VideoMetadata
import com.example.ikyky.core.ml.preprocessing.PresentationFaceCropper
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.Landmark
import com.example.ikyky.core.model.VideoFrame
import com.example.ikyky.core.storage.RepresentativeImageStorage
import com.example.ikyky.features.people.domain.model.Person
import com.example.ikyky.features.people.domain.model.RepresentativeFrame
import com.example.ikyky.features.processing.domain.model.AppearanceCandidate
import com.example.ikyky.features.processing.domain.model.AppearanceObservationRef

/**
 * Picks and persists **one individual-person representative crop per [Person]**,
 * populating the previously-always-null [Person.representativeFrame].
 *
 * ## Phase 8.2 — reject-then-rank
 *
 * Selection is delegated to [RepresentativeCandidateEvaluator], which for each
 * person:
 *  1. **rejects** invalid candidate observations — oversized ML-Kit boxes (C1),
 *     corner false positives (C2), too-soft / extreme-pose frames (C3),
 *     degenerate landmark geometry, crops that would contain a second person,
 *     and crops where the target face is mostly absent;
 *  2. **ranks** survivors on real signals (landmark completeness, sharpness,
 *     pose frontality, face-size sanity, temporal position) — never on the
 *     saturated Phase-2 `qualityScore`;
 *  3. prefers a **landmark-tight** crop; a detection-box crop is an explicit
 *     lower-priority fallback; if nothing valid remains the person's
 *     representative is left **unavailable** (`representativeFrame = null`) — a
 *     full source frame is NEVER used.
 *
 * The chosen crop is produced by [PresentationFaceCropper]: from the facial
 * landmarks when available ([PresentationFaceCropper.cropFromLandmarks]), else
 * from the detection box, always neighbour-trimmed against the other faces in
 * that frame. Nothing here touches detection / tracking / MNL / clustering / the
 * MobileFaceNet recognition crop, and no extra ML inference is run.
 *
 * One frame is decoded and cropped per PERSON; the bitmap is written to
 * [RepresentativeImageStorage] and immediately released.
 */
class SelectRepresentativeImagesUseCase(
    private val frameExtractor: VideoFrameExtractor,
    private val storage: RepresentativeImageStorage,
    private val logger: Logger,
) {

    /** Per-session diagnostics for the Phase 8.2 before/after report. */
    data class Diagnostics(
        val peopleConsidered: Int = 0,
        val observationsConsidered: Int = 0,
        val rejectedByReason: Map<RepresentativeCandidateEvaluator.Reject, Int> = emptyMap(),
        val acceptedCandidates: Int = 0,
        val landmarkTightCrops: Int = 0,
        val detectionBoxFallbacks: Int = 0,
        val representativesUnavailable: Int = 0,
        val representativesPersisted: Int = 0,
    )

    var lastDiagnostics: Diagnostics = Diagnostics()
        private set

    suspend fun select(
        sessionId: String,
        uriString: String,
        metadata: VideoMetadata,
        people: List<Person>,
        candidates: List<AppearanceCandidate>,
    ): List<Person> {
        val byId = candidates.associateBy { it.id }
        // Every detected face box per frame index, across every candidate — the
        // sibling set for neighbour-trim and the one-person check.
        val boxesByFrame: Map<Int, List<BoundingBox>> = buildMap {
            for (c in candidates) for (o in c.observations) {
                getOrPut(o.frameIndex) { ArrayList() }.let { (it as ArrayList).add(o.canonicalBox) }
            }
        }

        val rejectTally = LinkedHashMap<RepresentativeCandidateEvaluator.Reject, Int>()
        var obsConsidered = 0
        var accepted = 0
        var landmarkTight = 0
        var fallbacks = 0
        var unavailable = 0
        var persisted = 0

        val out = people.map { person ->
            val appearances = person.appearanceIds.mapNotNull { byId[it] }
            if (appearances.isEmpty()) {
                unavailable++
                return@map person
            }

            val eval = RepresentativeCandidateEvaluator.evaluate(
                observationsByAppearance = appearances.associate { it.id to it.observations },
                frameWidth = metadata.displayWidth,
                frameHeight = metadata.displayHeight,
                faceBoxesByFrame = boxesByFrame,
            )
            obsConsidered += eval.consideredCount
            accepted += eval.acceptedCount
            eval.rejected.values.forEach { r -> rejectTally.merge(r, 1, Int::plus) }

            val candidate = eval.chosen
            if (candidate == null) {
                unavailable++
                logger.w(
                    TAG,
                    "${person.id}: no valid representative candidate " +
                        "(${eval.consideredCount} considered, all rejected: ${eval.rejected.values.groupingBy { it }.eachCount()})",
                )
                return@map person
            }
            if (candidate.cropKind == RepresentativeCandidateEvaluator.CropKind.LANDMARK_TIGHT) landmarkTight++
            else fallbacks++

            val updated = persist(sessionId, uriString, metadata, person, candidate, boxesByFrame)
            if (updated.representativeFrame != null) persisted++ else unavailable++
            updated
        }

        lastDiagnostics = Diagnostics(
            peopleConsidered = people.size,
            observationsConsidered = obsConsidered,
            rejectedByReason = rejectTally,
            acceptedCandidates = accepted,
            landmarkTightCrops = landmarkTight,
            detectionBoxFallbacks = fallbacks,
            representativesUnavailable = unavailable,
            representativesPersisted = persisted,
        )
        logger.i(TAG, "Phase 8.2 representative selection: $lastDiagnostics")
        return out
    }

    private suspend fun persist(
        sessionId: String,
        uriString: String,
        metadata: VideoMetadata,
        person: Person,
        candidate: RepresentativeCandidateEvaluator.Candidate,
        boxesByFrame: Map<Int, List<BoundingBox>>,
    ): Person {
        val obs = candidate.observation
        val frame: VideoFrame = frameExtractor.decodeFrameAt(uriString, metadata, obs.timestampMs)
            ?: run {
                logger.w(TAG, "${person.id}: could not re-decode @${obs.timestampMs}ms — representative unavailable")
                return person
            }

        return try {
            val s = frame.geometry.scale
            val boxInDecoded = scaleBox(obs.canonicalBox, s)
            val lmInDecoded = obs.landmarks.map { scaleLandmark(it, s) }
            val siblings = (boxesByFrame[obs.frameIndex] ?: emptyList())
                .filter { it != obs.canonicalBox }
                .map { scaleBox(it, s) }

            val crop: Bitmap? = when (candidate.cropKind) {
                RepresentativeCandidateEvaluator.CropKind.LANDMARK_TIGHT ->
                    PresentationFaceCropper.cropFromLandmarks(frame.bitmap, boxInDecoded, lmInDecoded, siblings)
                // The fallback still crops landmark-tight when landmarks exist
                // (the landmark rect is what limits the neighbour bleed); it
                // differs only in that the evaluator ranked it below a clean
                // landmark candidate.
                RepresentativeCandidateEvaluator.CropKind.DETECTION_BOX_FALLBACK ->
                    if (lmInDecoded.count { it.type in LM_CORE } >= 4)
                        PresentationFaceCropper.cropFromLandmarks(frame.bitmap, boxInDecoded, lmInDecoded, siblings)
                    else
                        PresentationFaceCropper.crop(frame.bitmap, boxInDecoded, siblings)
            }
            if (crop == null) {
                // The evaluator already validated the rect; a null here means the
                // decoded-space rect degenerated. Representative stays unavailable
                // — we do NOT fall back to the full frame (Phase 8.2 invariant).
                logger.w(TAG, "${person.id}: crop rect degenerate at decode time — representative unavailable")
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
                        sourceObservationId = candidate.appearanceId,
                        frameIndex = obs.frameIndex,
                        timestampMs = obs.timestampMs,
                        presentationCropKey = saved.value.toString(),
                        // Real composite quality now, not the saturated Phase-2 max.
                        qualityScore = candidate.score,
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

    private fun scaleLandmark(l: Landmark, s: Float): Landmark = l.copy(x = l.x * s, y = l.y * s)

    private companion object {
        const val TAG = "SelectRepresentativeImages"

        val LM_CORE = setOf(
            com.example.ikyky.core.model.LandmarkType.LEFT_EYE,
            com.example.ikyky.core.model.LandmarkType.RIGHT_EYE,
            com.example.ikyky.core.model.LandmarkType.NOSE_BASE,
            com.example.ikyky.core.model.LandmarkType.MOUTH_LEFT,
            com.example.ikyky.core.model.LandmarkType.MOUTH_RIGHT,
            com.example.ikyky.core.model.LandmarkType.MOUTH_BOTTOM,
            com.example.ikyky.core.model.LandmarkType.LEFT_EAR,
            com.example.ikyky.core.model.LandmarkType.RIGHT_EAR,
            com.example.ikyky.core.model.LandmarkType.LEFT_CHEEK,
            com.example.ikyky.core.model.LandmarkType.RIGHT_CHEEK,
        )
    }
}
