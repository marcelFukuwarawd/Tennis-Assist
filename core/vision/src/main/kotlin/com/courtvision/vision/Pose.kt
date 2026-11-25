package com.courtvision.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** 2D body landmark in image pixels with visibility 0..1 (MediaPipe 33-point topology). */
data class Landmark(val x: Double, val y: Double, val visibility: Double)

class Pose(val landmarks: List<Landmark>) {
    init { require(landmarks.size >= 33) }

    operator fun get(i: Int) = landmarks[i]
    fun visible(vararg idx: Int, min: Double = 0.5) = idx.all { landmarks[it].visibility >= min }
    fun p(i: Int) = Vec2(landmarks[i].x, landmarks[i].y)

    val box: Box
        get() {
            val v = landmarks.filter { it.visibility > 0.3 }
            if (v.isEmpty()) return Box(0.0, 0.0, 0.0, 0.0)
            return Box(v.minOf { it.x }, v.minOf { it.y }, v.maxOf { it.x }, v.maxOf { it.y })
        }

    companion object {
        const val NOSE = 0
        const val L_SHOULDER = 11; const val R_SHOULDER = 12
        const val L_ELBOW = 13; const val R_ELBOW = 14
        const val L_WRIST = 15; const val R_WRIST = 16
        const val L_HIP = 23; const val R_HIP = 24
        const val L_KNEE = 25; const val R_KNEE = 26
        const val L_ANKLE = 27; const val R_ANKLE = 28

        val BONES = listOf(
            L_SHOULDER to R_SHOULDER, L_SHOULDER to L_ELBOW, L_ELBOW to L_WRIST,
            R_SHOULDER to R_ELBOW, R_ELBOW to R_WRIST, L_SHOULDER to L_HIP, R_SHOULDER to R_HIP,
            L_HIP to R_HIP, L_HIP to L_KNEE, L_KNEE to L_ANKLE, R_HIP to R_KNEE, R_KNEE to R_ANKLE,
        )
    }
}

/**
 * 2D (monocular) joint-angle measurements. These are image-plane angles, NOT validated 3D
 * biomechanical measurements; the UI labels them accordingly.
 */
object Biomechanics {
    /** Angle ABC in degrees at vertex B. */
    fun angle(a: Vec2, b: Vec2, c: Vec2): Double {
        val ba = a - b; val bc = c - b
        val den = ba.length * bc.length
        if (den < 1e-9) return Double.NaN
        return Math.toDegrees(acos((ba.dot(bc) / den).coerceIn(-1.0, 1.0)))
    }

    /** Orientation of a segment in degrees relative to the image horizontal. */
    fun orientation(a: Vec2, b: Vec2): Double = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))

    data class Report(
        val leftKnee: Double?, val rightKnee: Double?,
        val leftElbow: Double?, val rightElbow: Double?,
        /** Difference between shoulder-line and hip-line orientation (proxy of trunk separation). */
        val shoulderHipSeparation: Double?,
        /** Apparent shoulder width / hip width ratio: lower values suggest the trunk is turned sideways. */
        val shoulderTurnRatio: Double?,
        /** Horizontal offset of the hip centre from the ankle midpoint, normalised by leg length. */
        val balanceOffset: Double?,
        /** Ankle distance / shoulder width (stance width). */
        val stanceWidth: Double?,
    )

    fun analyse(p: Pose): Report {
        fun ang(a: Int, b: Int, c: Int) = if (p.visible(a, b, c)) angle(p.p(a), p.p(b), p.p(c)) else null
        val shoulders = p.visible(Pose.L_SHOULDER, Pose.R_SHOULDER)
        val hips = p.visible(Pose.L_HIP, Pose.R_HIP)
        val ankles = p.visible(Pose.L_ANKLE, Pose.R_ANKLE)
        val sw = if (shoulders) p.p(Pose.L_SHOULDER).distanceTo(p.p(Pose.R_SHOULDER)) else null
        val hw = if (hips) p.p(Pose.L_HIP).distanceTo(p.p(Pose.R_HIP)) else null
        val sep = if (shoulders && hips) {
            var d = orientation(p.p(Pose.L_SHOULDER), p.p(Pose.R_SHOULDER)) - orientation(p.p(Pose.L_HIP), p.p(Pose.R_HIP))
            while (d > 180) d -= 360; while (d < -180) d += 360
            abs(d)
        } else null
        val balance = if (hips && ankles) {
            val hc = (p.p(Pose.L_HIP) + p.p(Pose.R_HIP)) * 0.5
            val ac = (p.p(Pose.L_ANKLE) + p.p(Pose.R_ANKLE)) * 0.5
            val leg = hc.distanceTo(ac)
            if (leg > 1) (hc.x - ac.x) / leg else null
        } else null
        return Report(
            leftKnee = ang(Pose.L_HIP, Pose.L_KNEE, Pose.L_ANKLE),
            rightKnee = ang(Pose.R_HIP, Pose.R_KNEE, Pose.R_ANKLE),
            leftElbow = ang(Pose.L_SHOULDER, Pose.L_ELBOW, Pose.L_WRIST),
            rightElbow = ang(Pose.R_SHOULDER, Pose.R_ELBOW, Pose.R_WRIST),
            shoulderHipSeparation = sep,
            shoulderTurnRatio = if (sw != null && hw != null && hw > 1) sw / hw else null,
            balanceOffset = balance,
            stanceWidth = if (ankles && sw != null && sw > 1) p.p(Pose.L_ANKLE).distanceTo(p.p(Pose.R_ANKLE)) / sw else null,
        )
    }

    /** Normalises a pose (hip-centred, torso-length scaled) to overlay poses of different sessions. */
    fun normalise(p: Pose): List<Vec2>? {
        if (!p.visible(Pose.L_HIP, Pose.R_HIP, Pose.L_SHOULDER, Pose.R_SHOULDER)) return null
        val hc = (p.p(Pose.L_HIP) + p.p(Pose.R_HIP)) * 0.5
        val sc = (p.p(Pose.L_SHOULDER) + p.p(Pose.R_SHOULDER)) * 0.5
        val torso = hc.distanceTo(sc)
        if (torso < 1) return null
        return p.landmarks.map { Vec2((it.x - hc.x) / torso, (it.y - hc.y) / torso) }
    }

    /** Mean distance between two normalised poses (lower = more similar). */
    fun similarity(a: List<Vec2>, b: List<Vec2>): Double {
        val idx = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        return sqrt(idx.sumOf { a[it].distanceTo(b[it]).let { d -> d * d } } / idx.size)
    }
}
