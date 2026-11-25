package com.courtvision.ai.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 *  - EfficientDet-Lite0 int8 (COCO) for "person" and "sports ball" — always on CPU, because
 *    quantised (int8) models fail on the GPU delegate of many phones
 *    ("ToTensorConverter: input data size does not match expected size");
 *  - Pose Landmarker Lite (33 landmarks) — GPU when available, CPU fallback.
 * A model that fails at runtime is recreated on CPU once; if it fails again it is disabled and
 * the analysis continues with the classical (colour + motion) detector instead of stopping.
 */
class MlModels(private val context: Context, private val preferGpu: Boolean) : Closeable {

    var delegateUsed: String = "CPU"; private set
    /** True when a neural model had to be disabled; results then rely on classical vision only. */
    var degraded: Boolean = false; private set

    private var detector: ObjectDetector? = createDetector()
    private var detectorRetried = false
    private var pose: PoseLandmarker? = null
    private var poseCreated = false
    private var poseOnGpu = false
    private var poseRetried = false

    val available: Boolean get() = detector != null

    private fun createDetector(): ObjectDetector? = runCatching {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR).setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(12)
                .setScoreThreshold(0.25f)
                .setCategoryAllowlist(listOf("person", "sports ball"))
                .build(),
        )
    }.onFailure { Log.w(TAG, "object detector unavailable", it) }.getOrNull()

    private fun createPose(delegate: Delegate): PoseLandmarker? = runCatching {
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
    }.onFailure { Log.w(TAG, "pose model unavailable on $delegate", it) }.getOrNull()

    private fun ensurePose(): PoseLandmarker? {
        if (!poseCreated) {
            poseCreated = true
            if (preferGpu) { pose = createPose(Delegate.GPU); poseOnGpu = pose != null; if (poseOnGpu) delegateUsed = "CPU+GPU" }
            if (pose == null) pose = createPose(Delegate.CPU)
        }
        return pose
    }

    /** MediaPipe requires ARGB_8888 bitmaps. */
    private fun argb(b: Bitmap): Bitmap = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)

    data class Detections(val persons: List<PersonDetection>, val balls: List<BallCandidate>)

    fun detect(bitmap: Bitmap): Detections {
        val d = detector ?: return Detections(emptyList(), emptyList())
        val res = try {
            d.detect(BitmapImageBuilder(argb(bitmap)).build())
        } catch (e: Exception) {
            Log.w(TAG, "object detection failed", e)
            runCatching { d.close() }
            detector = if (!detectorRetried) { detectorRetried = true; createDetector() } else null
            if (detector == null) degraded = true
            return Detections(emptyList(), emptyList())
        }
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
        val p = ensurePose() ?: return emptyList()
        val img = argb(bitmap)
        val res = try {
            p.detect(BitmapImageBuilder(img).build())
        } catch (e: Exception) {
            Log.w(TAG, "pose detection failed", e)
            runCatching { p.close() }
            pose = if (!poseRetried) { poseRetried = true; createPose(Delegate.CPU) } else null
            if (pose == null) degraded = true
            return emptyList()
        }
        val w = img.width.toDouble(); val h = img.height.toDouble()
        return res.landmarks().map { lm ->
            Pose(lm.map { Landmark(it.x() * w, it.y() * h, it.visibility().orElse(0f).toDouble()) })
        }
    }

    /** True if the pose model can be loaded (used by the biomechanics screen). */
    val poseAvailable: Boolean get() = ensurePose() != null

    override fun close() {
        runCatching { detector?.close() }
        runCatching { pose?.close() }
    }

    companion object {
        private const val TAG = "MlModels"
        const val DETECTOR = "models/efficientdet_lite0.tflite"
        const val POSE = "models/pose_landmarker_lite.task"
    }
}
