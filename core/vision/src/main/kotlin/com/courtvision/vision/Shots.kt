package com.courtvision.vision

import kotlin.math.abs

enum class ShotType {
    SERVE, FOREHAND, BACKHAND, VOLLEY, SMASH, LOB, DROP_SHOT, RETURN, DINK, DRIVE, THIRD_SHOT_DROP, UNKNOWN
}

enum class BackhandGrip { ONE_HANDED, TWO_HANDED }

data class ShotFeatures(
    val sport: CourtType,
    val indexInRally: Int,
    /** Court position of the hitter's feet (metres) if known. */
    val hitterCourt: Vec2?,
    /** Ball image position at contact. */
    val ballAtContact: Vec2,
    val pose: Pose?,
    val rightHanded: Boolean = true,
    /** Court position of the next bounce, if detected. */
    val nextBounce: Vec2?,
    /** Time from contact to the next bounce (ms). */
    val flightMs: Long?,
    val avgSpeedKmh: Double?,
    /** Whether the ball bounced on the hitter's side before the hit. */
    val bouncedBeforeHit: Boolean?,
)

data class ShotClassification(
    val type: ShotType,
    val grip: BackhandGrip?,
    /** Heuristic score 0..1: how many cues supported the decision (not a calibrated probability). */
    val support: Double,
    val cues: List<String>,
)

/**
 * Rule-based stroke classifier combining pose, ball/court geometry and timing.
 * Each decision lists the cues that produced it, and unsupported cases return UNKNOWN.
 */
object ShotClassifier {
    fun classify(f: ShotFeatures, court: CourtModel): ShotClassification {
        val cues = ArrayList<String>()
        val pose = f.pose
        val netDist = f.hitterCourt?.let { abs(it.y - court.netY) }
        val wristAboveHead = pose?.let {
            val nose = it[Pose.NOSE]
            val lw = it[Pose.L_WRIST]; val rw = it[Pose.R_WRIST]
            (lw.visibility > 0.5 && lw.y < nose.y) || (rw.visibility > 0.5 && rw.y < nose.y)
        }
        val ballAboveHead = pose?.let { it[Pose.NOSE].visibility > 0.5 && f.ballAtContact.y < it[Pose.NOSE].y }

        // ---- serve ----
        if (f.indexInRally == 0) {
            cues += "first_shot_of_rally"
            val nearBaseline = netDist != null && netDist > court.length / 2 - 2.0
            if (nearBaseline) cues += "hitter_behind_baseline"
            if (f.sport == CourtType.TENNIS && (wristAboveHead == true || ballAboveHead == true)) cues += "overhead_contact"
            return ShotClassification(ShotType.SERVE, null, if (cues.size >= 2) 0.9 else 0.6, cues)
        }
        if (f.indexInRally == 1) cues += "second_shot_return"

        // ---- overhead ----
        if (f.sport == CourtType.TENNIS && (wristAboveHead == true && ballAboveHead == true)) {
            cues += "overhead_contact"
            return ShotClassification(ShotType.SMASH, null, 0.75, cues)
        }

        // ---- direction-independent outcome cues ----
        val bounceNetDist = f.nextBounce?.let { abs(it.y - court.netY) }
        if (f.sport == CourtType.PICKLEBALL) {
            val inKitchen = bounceNetDist != null && bounceNetDist <= court.serviceDistance
            val hitterAtKitchen = netDist != null && netDist <= court.serviceDistance + 0.8
            if (f.indexInRally == 2 && inKitchen) { cues += "third_shot_into_kitchen"; return ShotClassification(ShotType.THIRD_SHOT_DROP, null, 0.7, cues) }
            if (inKitchen && hitterAtKitchen && (f.avgSpeedKmh ?: 0.0) < 25) { cues += "soft_kitchen_exchange"; return ShotClassification(ShotType.DINK, null, 0.7, cues) }
            if (!hitterAtKitchen && (f.avgSpeedKmh ?: 0.0) >= 40) { cues += "fast_from_back"; return ShotClassification(ShotType.DRIVE, null, 0.65, cues) }
        } else {
            if (f.flightMs != null && f.avgSpeedKmh != null && f.flightMs > 1500 && f.avgSpeedKmh < 45) {
                cues += "long_flight_slow"; return ShotClassification(ShotType.LOB, null, 0.6, cues)
            }
            if (bounceNetDist != null && bounceNetDist < 3.0 && (f.avgSpeedKmh ?: 99.0) < 40 && netDist != null && netDist > 8) {
                cues += "short_soft_from_back"; return ShotClassification(ShotType.DROP_SHOT, null, 0.6, cues)
            }
            if (netDist != null && netDist < court.serviceDistance - 1.5 && f.bouncedBeforeHit == false) {
                cues += "near_net_no_bounce"; return ShotClassification(ShotType.VOLLEY, null, 0.7, cues)
            }
        }

        // ---- forehand / backhand from pose ----
        if (pose != null && pose.visible(Pose.L_SHOULDER, Pose.R_SHOULDER)) {
            val ls = pose.p(Pose.L_SHOULDER); val rs = pose.p(Pose.R_SHOULDER)
            val centre = (ls + rs) * 0.5
            val axis = rs - ls // anatomical left -> right
            val side = (f.ballAtContact - centre).dot(axis)
            val onRight = side > 0
            val forehand = if (f.rightHanded) onRight else !onRight
            cues += if (onRight) "contact_right_side" else "contact_left_side"
            var grip: BackhandGrip? = null
            if (!forehand && pose.visible(Pose.L_WRIST, Pose.R_WRIST)) {
                val wd = pose.p(Pose.L_WRIST).distanceTo(pose.p(Pose.R_WRIST))
                grip = if (wd < 0.6 * axis.length) BackhandGrip.TWO_HANDED else BackhandGrip.ONE_HANDED
                cues += "wrist_distance"
            }
            val type = if (f.indexInRally == 1) ShotType.RETURN else if (forehand) ShotType.FOREHAND else ShotType.BACKHAND
            val support = (0.5 + 0.5 * (abs(side) / (axis.length * axis.length + 1e-9)).coerceAtMost(1.0)).coerceIn(0.5, 0.9)
            return ShotClassification(type, grip, support, cues)
        }
        if (f.indexInRally == 1) return ShotClassification(ShotType.RETURN, null, 0.5, cues)
        return ShotClassification(ShotType.UNKNOWN, null, 0.0, cues + "no_pose")
    }

    /** Return-shot classification refined with stroke side (for statistics). */
    fun strokeSide(f: ShotFeatures): ShotType? {
        val pose = f.pose ?: return null
        if (!pose.visible(Pose.L_SHOULDER, Pose.R_SHOULDER)) return null
        val ls = pose.p(Pose.L_SHOULDER); val rs = pose.p(Pose.R_SHOULDER)
        val side = (f.ballAtContact - (ls + rs) * 0.5).dot(rs - ls)
        val forehand = if (f.rightHanded) side > 0 else side < 0
        return if (forehand) ShotType.FOREHAND else ShotType.BACKHAND
    }
}
