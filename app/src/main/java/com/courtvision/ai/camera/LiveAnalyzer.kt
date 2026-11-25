package com.courtvision.ai.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.courtvision.ai.data.CalibrationData
import com.courtvision.ai.data.toCalibration
import com.courtvision.vision.BallColorProfile
import com.courtvision.vision.BallEventType
import com.courtvision.vision.BallTracker
import com.courtvision.vision.ColorMotionBallDetector
import com.courtvision.vision.CourtCalibration
import com.courtvision.vision.CourtModel
import com.courtvision.vision.EventDetector
import com.courtvision.vision.StaticObjectFilter
import com.courtvision.vision.TrackPoint
import com.courtvision.vision.Vec2

/**
 * Real-time analysis of the low-resolution camera stream:
 * brightness for placement advice, the latest frame for calibration and, when calibrated,
 * live ball tracking with bounce detection (used by line calls and precision training).
 */
class LiveAnalyzer(
    private val court: CourtModel,
    profile: BallColorProfile,
    private val onLuma: (Double) -> Unit,
) : ImageAnalysis.Analyzer {
    /** Called on the analysis thread for every new bounce (court metres, sigma, time). */
    @Volatile var bounceListener: ((court: Vec2, sigma: Vec2, timeMs: Long) -> Unit)? = null
    @Volatile var calibration: CalibrationData? = null
    @Volatile var trackingEnabled = false
    @Volatile var latestFrame: Bitmap? = null
        private set
    private val detector = ColorMotionBallDetector(profile)
    private val tracker = BallTracker()
    private val events = EventDetector()
    private val window = ArrayDeque<TrackPoint>()
    private var filter: StaticObjectFilter? = null
    private var cal: CourtCalibration? = null
    private var calKey: CalibrationData? = null
    private var lastBounceMs = 0L
    private var frameNo = 0L
    private var pixels = IntArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val rot = image.imageInfo.rotationDegrees
            val bmp = image.toBitmap()
            val upright = if (rot == 0) bmp else {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            latestFrame = upright
            val w = upright.width; val h = upright.height
            if (pixels.size != w * h) { pixels = IntArray(w * h); filter = StaticObjectFilter(w, h) }
            upright.getPixels(pixels, 0, w, 0, 0, w, h)
            val cands = detector.detect(pixels, w, h)
            onLuma(detector.lastMeanLuma)
            val c = calibration
            if (!trackingEnabled || c == null) return
            if (c != calKey) { cal = c.toCalibration(court, w, h); calKey = c }
            val hgm = cal?.imageToCourt ?: return
            val t = image.imageInfo.timestamp / 1_000_000
            for (p in tracker.update(frameNo++, t, filter!!.filter(cands))) {
                window.addLast(p)
                while (window.size > 45) window.removeFirst()
            }
            val found = events.detect(window.toList(), hgm)
            for (e in found) {
                if (e.type != BallEventType.BOUNCE || e.timeMs <= lastBounceMs + 300) continue
                val cc = e.court ?: continue
                lastBounceMs = e.timeMs
                bounceListener?.invoke(cc, e.sigma ?: Vec2(0.3, 0.3), e.timeMs)
            }
        } catch (_: Throwable) {
            // a failing frame must never stop recording
        } finally {
            image.close()
        }
    }
}
