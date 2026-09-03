package com.example.ikyky.core.ml.detector

import android.graphics.Bitmap
import com.example.ikyky.core.common.error.AppError
import com.example.ikyky.core.common.result.AppResult
import com.example.ikyky.core.model.BoundingBox
import com.example.ikyky.core.model.DetectedFace
import com.example.ikyky.core.model.HeadPose
import com.example.ikyky.core.model.Landmark
import com.example.ikyky.core.model.LandmarkType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.mlkit.vision.face.FaceDetector as MlKitDetector

/**
 * ML Kit implementation of [FaceDetector].
 *
 * Built from a [DetectorTuning]; production uses [DetectorTuning.DEFAULT]
 * (ACCURATE, landmarks + classification, tracking on). Callers only ever see
 * [DetectedFace] — no `com.google.mlkit.*` leaks past this class.
 */
class MlKitFaceDetector(
    private val tuning: DetectorTuning = DetectorTuning.DEFAULT,
) : FaceDetector {

    /** Back-compat ctor used by older call sites that only tuned min-face-size. */
    constructor(minFaceFraction: Float) : this(DetectorTuning(minFaceFraction = minFaceFraction))

    private val delegate: MlKitDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(
                if (tuning.accurateMode) FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                else FaceDetectorOptions.PERFORMANCE_MODE_FAST
            )
            .setLandmarkMode(
                if (tuning.landmarks) FaceDetectorOptions.LANDMARK_MODE_ALL
                else FaceDetectorOptions.LANDMARK_MODE_NONE
            )
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(
                if (tuning.classification) FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                else FaceDetectorOptions.CLASSIFICATION_MODE_NONE
            )
            .setMinFaceSize(tuning.minFaceFraction)
            .apply { if (tuning.enableTracking) enableTracking() }
            .build()
    )

    override suspend fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int,
    ): AppResult<List<DetectedFace>> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        delegate.process(image)
            .addOnSuccessListener { faces ->
                cont.resume(AppResult.Success(faces.map { it.toDomain() }))
            }
            .addOnFailureListener { e ->
                cont.resume(
                    AppResult.Failure(
                        AppError.MlInference("ML Kit face detection failed", e)
                    )
                )
            }
    }

    override fun close() {
        runCatching { delegate.close() }
    }

    private fun Face.toDomain(): DetectedFace {
        val box = boundingBox
        return DetectedFace(
            boundingBox = BoundingBox(box.left, box.top, box.right, box.bottom),
            landmarks = allLandmarks.mapNotNull { it.toDomain() },
            headPose = HeadPose(headEulerAngleX, headEulerAngleY, headEulerAngleZ),
            leftEyeOpenProbability = leftEyeOpenProbability,
            rightEyeOpenProbability = rightEyeOpenProbability,
            smilingProbability = smilingProbability,
            trackingId = trackingId,
        )
    }

    private fun FaceLandmark.toDomain(): Landmark? {
        val mapped = when (landmarkType) {
            FaceLandmark.LEFT_EYE -> LandmarkType.LEFT_EYE
            FaceLandmark.RIGHT_EYE -> LandmarkType.RIGHT_EYE
            FaceLandmark.NOSE_BASE -> LandmarkType.NOSE_BASE
            FaceLandmark.MOUTH_LEFT -> LandmarkType.MOUTH_LEFT
            FaceLandmark.MOUTH_RIGHT -> LandmarkType.MOUTH_RIGHT
            FaceLandmark.MOUTH_BOTTOM -> LandmarkType.MOUTH_BOTTOM
            FaceLandmark.LEFT_EAR -> LandmarkType.LEFT_EAR
            FaceLandmark.RIGHT_EAR -> LandmarkType.RIGHT_EAR
            FaceLandmark.LEFT_CHEEK -> LandmarkType.LEFT_CHEEK
            FaceLandmark.RIGHT_CHEEK -> LandmarkType.RIGHT_CHEEK
            else -> return null
        }
        return Landmark(mapped, position.x, position.y)
    }
}
