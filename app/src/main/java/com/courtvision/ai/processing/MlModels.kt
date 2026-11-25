package com.courtvision.ai.processing

import android.content.Context
import android.graphics.Bitmap
import com.courtvision.vision.BallCandidate
import com.courtvision.vision.Box
import com.courtvision.vision.Landmark
import com.courtvision.vision.PersonDetection
import com.courtvision.vision.Pose
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable

/**
 * On-device neural models (Google MediaPipe Tasks, Apache-2.0), bundled in assets/models:
 *  - EfficientDet-Lite0 (COCO) for "person" and "sports ball";
 *  - Pose Landmarker Lite (33 landmarks).
 * GPU delegate is tried first and the CPU is used as fallback.
 */
class MlModels(private val context: Context, preferGpu: Boolean) : Closeable {

    var delegateUsed: String = "none"; private set

    private val detector: ObjectDetector? = create(preferGpu) { delegate ->
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR).setDelegate(delegate).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(12)
                .setScoreThreshold(0.25f)
                .setCategoryAllowlist(listOf("person", "sports ball"))
                .build(),
        )
    }

    private val pose: PoseLandmarker? = create(preferGpu) { delegate ->
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE).setDelegate(delegate).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(4)
                .setMinPoseDetectionConfidence(0.4f)
                .setMinPosePresenceConfidence(0.4f)
                .build(),
        )
    }

    val available: Boolean get() = detector != null

    private fun <T> create(preferGpu: Boolean, block: (Delegate) -> T): T? {
        if (preferGpu) runCatching { return block(Delegate.GPU).also { delegateUsed = "GPU" } }
        return runCatching { block(Delegate.CPU).also { if (delegateUsed != "GPU") delegateUsed = "CPU" } }.getOrNull()
    }

    data class Detections(val persons: List<PersonDetection>, val balls: List<BallCandidate>)

    fun detect(bitmap: Bitmap): Detections {
        val d = detector ?: return Detections(emptyList(), emptyList())
        val res = d.detect(BitmapImageBuilder(bitmap).build())
        val persons = ArrayList<PersonDetection>()
        val balls = ArrayList<BallCandidate>()
        for (det in res.detections()) {
            val cat = det.categories().firstOrNull() ?: continue
            val r = det.boundingBox()
            when (cat.categoryName()) {
                "person" -> persons += PersonDetection(Box(r.left.toDouble(), r.top.toDouble(), r.right.toDouble(), r.bottom.toDouble()), cat.score().toDouble())
                "sports ball" -> balls += BallCandidate(
                    r.centerX().toDouble(), r.centerY().toDouble(), (r.width() + r.height()) / 4.0,
                    cat.score().toDouble(), BallCandidate.Source.NEURAL,
                )
            }
        }
        return Detections(persons, balls)
    }

    fun poses(bitmap: Bitmap): List<Pose> {
        val p = pose ?: return emptyList()
        val res = p.detect(BitmapImageBuilder(bitmap).build())
        val w = bitmap.width.toDouble(); val h = bitmap.height.toDouble()
        return res.landmarks().map { lm ->
            Pose(lm.map { Landmark(it.x() * w, it.y() * h, it.visibility().orElse(0f).toDouble()) })
        }
    }

    override fun close() {
        runCatching { detector?.close() }
        runCatching { pose?.close() }
    }

    companion object {
        const val DETECTOR = "models/efficientdet_lite0.tflite"
        const val POSE = "models/pose_landmarker_lite.task"
    }
}
