package com.courtvision.ai.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.hypot

data class LensOption(val cameraId: String, val kind: LensKind, val equivalentMm: Int)
enum class LensKind { ULTRAWIDE, MAIN, TELE }

/** Manual capture parameters; null = automatic. */
data class ManualControls(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,
    val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
)

data class CameraCaps(
    val qualities: List<Quality>,
    val fpsOptions: List<Int>,
    val isoRange: Range<Int>?,
    val shutterRange: Range<Long>?,
    val minFocusDiopters: Float?,
    val exposureRange: Range<Int>,
    val exposureStep: Float,
)

/**
 * CameraX wrapper: preview + video + optional low-resolution analysis stream.
 * Long matches are recorded as consecutive segment files so a crash or a battery shutdown
 * can only affect the segment being written.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var continueSegments = false
    private var segmentIndex = 0
    var analysisBound = false; private set

    suspend fun provider(): ProcessCameraProvider = provider ?: suspendCancellableCoroutine { cont ->
        val f = ProcessCameraProvider.getInstance(context)
        f.addListener({ provider = f.get(); cont.resume(f.get()) }, mainExecutor)
    }

    /** Lists back cameras and classifies them by 35 mm-equivalent focal length. */
    suspend fun lenses(): List<LensOption> {
        val p = provider()
        return p.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }.mapNotNull { info ->
            val c2 = Camera2CameraInfo.from(info)
            val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@mapNotNull null
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@mapNotNull null
            val diag = hypot(size.width.toDouble(), size.height.toDouble())
            val eq = (focal * 43.27 / diag).toInt()
            val kind = when { eq < 20 -> LensKind.ULTRAWIDE; eq > 45 -> LensKind.TELE; else -> LensKind.MAIN }
            LensOption(c2.cameraId, kind, eq)
        }.distinctBy { it.cameraId }.sortedBy { it.equivalentMm }
    }

    private fun selector(cameraId: String?): CameraSelector {
        if (cameraId == null) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId }.ifEmpty { infos.take(1) }
        }.build()
    }

    fun caps(): CameraCaps? {
        val cam = camera ?: return null
        val info = cam.cameraInfo
        val c2 = Camera2CameraInfo.from(info)
        val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        val fps = info.supportedFrameRateRanges.filter { it.lower == it.upper }.map { it.upper }.toSortedSet()
            .ifEmpty { sortedSetOf(30) }.filter { it in setOf(24, 30, 60, 120) }
        return CameraCaps(
            qualities = qualities,
            fpsOptions = fps.ifEmpty { listOf(30) },
            isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            shutterRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDiopters = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            exposureRange = info.exposureState.exposureCompensationRange,
            exposureStep = info.exposureState.exposureCompensationStep.toFloat(),
        )
    }

    fun cameraInfo(): CameraInfo? = camera?.cameraInfo

    suspend fun bind(
        owner: LifecycleOwner, previewView: PreviewView, cameraId: String?, quality: Quality, fps: Int,
        analyzer: ImageAnalysis.Analyzer?,
    ) {
        val p = provider()
        p.unbindAll()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .build()
        val video = VideoCapture.Builder(recorder).setTargetFrameRate(Range(fps, fps)).build()
        videoCapture = video
        val sel = selector(cameraId)
        analysisBound = false
        if (analyzer != null) {
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, analyzer)
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(video).addUseCase(analysis).build()
            val ok = runCatching { camera = p.bindToLifecycle(owner, sel, group) }.isSuccess
            if (ok) { analysisBound = true; return }
            p.unbindAll()
        }
        camera = p.bindToLifecycle(owner, sel, preview, video)
    }

    fun setZoom(linear: Float) { camera?.cameraControl?.setLinearZoom(linear.coerceIn(0f, 1f)) }
    fun setExposureIndex(i: Int) { camera?.cameraControl?.setExposureCompensationIndex(i) }

    fun focusAt(previewView: PreviewView, x: Float, y: Float, lock: Boolean) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).apply { if (lock) disableAutoCancel() }.build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun applyManual(m: ManualControls) {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        if (m.iso != null || m.shutterNs != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            m.iso?.let { b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
            m.shutterNs?.let { b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, m.aeLock)
        }
        if (m.focusDiopters != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, m.focusDiopters)
        }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, m.awbLock)
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    /**
     * Starts segmented recording into [dir]. [onSegment] is called for every finalised file
     * (with null error when the file is complete).
     */
    @SuppressLint("MissingPermission")
    fun startRecording(dir: File, segmentMinutes: Int, audio: Boolean, onSegment: (Uri, Int?) -> Unit, onStatus: (Long) -> Unit) {
        continueSegments = true
        segmentIndex = dir.listFiles { f -> f.extension == "mp4" }?.size ?: 0
        startSegment(dir, segmentMinutes, audio, onSegment, onStatus)
    }

    @SuppressLint("MissingPermission")
    private fun startSegment(dir: File, minutes: Int, audio: Boolean, onSegment: (Uri, Int?) -> Unit, onStatus: (Long) -> Unit) {
        val vc = videoCapture ?: return
        val file = File(dir, String.format(java.util.Locale.US, "seg_%03d.mp4", segmentIndex++))
        val pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply { if (audio) withAudioEnabled() }
        recording = pending.start(mainExecutor) { e ->
            when (e) {
                is VideoRecordEvent.Status -> onStatus(e.recordingStats.recordedDurationNanos / 1_000_000)
                is VideoRecordEvent.Finalize -> {
                    val err = if (e.error == VideoRecordEvent.Finalize.ERROR_NONE) null else e.error
                    onSegment(Uri.fromFile(file), err)
                    if (continueSegments && (err == null || err == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED || err == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)) {
                        startSegment(dir, minutes, audio, onSegment, onStatus)
                    }
                }
                else -> Unit
            }
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (continueSegments) recording?.stop() }, minutes * 60_000L)
    }

    fun stopRecording() {
        continueSegments = false
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        recording = null
    }

    val isRecording: Boolean get() = recording != null && continueSegments

    fun release() {
        stopRecording()
        provider?.unbindAll()
        analysisExecutor.shutdown()
    }
}
