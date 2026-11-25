package com.courtvision.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Automatic court proposal from a single frame:
 * 1. white-line mask (bright, low saturation, brighter than a local neighbourhood => works on
 *    clay, hard, grass and coloured courts because it is relative to the local surface colour);
 * 2. robust extremal points of the mask give the 4 outer corners of a perspective trapezoid;
 * 3. the hypothesis is VERIFIED by projecting all court lines and measuring line support.
 * The user can always correct the corners manually.
 */
class CourtAutoDetector(private val court: CourtModel) {

    data class Result(val corners: List<Vec2>, val lineSupport: Double, val calibration: CourtCalibration)

    fun lineMask(pixels: IntArray, w: Int, h: Int): BooleanArray {
        val luma = IntArray(w * h)
        val sat = FloatArray(w * h)
        for (i in 0 until w * h) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            luma[i] = (r * 77 + g * 150 + b * 29) shr 8
            sat[i] = if (mx == 0) 0f else (mx - mn).toFloat() / mx
        }
        val out = BooleanArray(w * h)
        val k = max(3, w / 80)
        for (y in k until h - k) for (x in k until w - k) {
            val i = y * w + x
            val l = luma[i]
            if (l < 140 || sat[i] > 0.28f) continue
            // thin bright structure: brighter than pixels k away in either direction
            val horiz = l - max(luma[i - k], luma[i + k])
            val vert = l - max(luma[i - k * w], luma[i + k * w])
            if (horiz > 25 || vert > 25) out[i] = true
        }
        return out
    }

    fun detect(pixels: IntArray, w: Int, h: Int): Result? {
        val mask = lineMask(pixels, w, h)
        val pts = ArrayList<Vec2>()
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) pts += Vec2(x.toDouble(), y.toDouble())
        if (pts.size < 200) return null
        fun extreme(score: (Vec2) -> Double): Vec2 {
            val sorted = pts.sortedByDescending(score)
            val top = sorted.take(max(5, pts.size / 500))
            return Vec2(top.map { it.x }.average(), top.map { it.y }.average())
        }
        val farLeft = extreme { -(it.x + it.y) }
        val farRight = extreme { it.x - it.y }
        val nearRight = extreme { it.x + it.y }
        val nearLeft = extreme { it.y - it.x }
        val corners = listOf(farLeft, farRight, nearRight, nearLeft)
        val cal = CourtCalibration.fromCorners(court, corners, w, h)
        if (cal.courtToImage == null) return null
        val support = lineSupport(cal, mask, w, h)
        return Result(corners, support, cal)
    }

    /** Fraction of sampled court-line points that fall on (or 2 px near) the line mask. */
    fun lineSupport(cal: CourtCalibration, mask: BooleanArray, w: Int, h: Int): Double {
        val toImg = cal.courtToImage ?: return 0.0
        var hit = 0; var total = 0
        for (line in court.lines()) {
            if (line.name == "net") continue
            for (s in 0..40) {
                val t = s / 40.0
                val p = toImg.map(line.a + (line.b - line.a) * t)
                val x = p.x.toInt(); val y = p.y.toInt()
                if (x < 2 || y < 2 || x >= w - 2 || y >= h - 2) continue
                total++
                var found = false
                loop@ for (dy in -2..2) for (dx in -2..2) if (mask[(y + dy) * w + x + dx]) { found = true; break@loop }
                if (found) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }
}

enum class PlacementAdvice {
    OK, LEVEL_DEVICE, TILT_DOWN, TILT_UP, MOVE_BACK, CENTER_COURT, ZOOM_IN, LOW_LIGHT, RAISE_CAMERA, CALIBRATE
}

/** Real-time camera placement checks from sensors, image brightness and the court calibration. */
object PlacementAdvisor {
    fun advise(
        rollDegrees: Double?, pitchDegrees: Double?, meanLuma: Double?,
        corners: List<Vec2>?, imageW: Int, imageH: Int,
    ): List<PlacementAdvice> {
        val out = ArrayList<PlacementAdvice>()
        if (rollDegrees != null && kotlin.math.abs(rollDegrees) > 3.0) out += PlacementAdvice.LEVEL_DEVICE
        // pitch: 0 = device vertical, positive = camera pointing down
        if (pitchDegrees != null) {
            if (pitchDegrees < -2) out += PlacementAdvice.TILT_DOWN
            if (pitchDegrees > 35) out += PlacementAdvice.TILT_UP
        }
        if (meanLuma != null && meanLuma < 50) out += PlacementAdvice.LOW_LIGHT
        if (corners == null || corners.size != 4) {
            out += PlacementAdvice.CALIBRATE
        } else {
            val margin = 0.01
            val outside = corners.any { it.x < imageW * margin || it.x > imageW * (1 - margin) || it.y < imageH * margin || it.y > imageH * (1 - margin) }
            if (outside) out += PlacementAdvice.MOVE_BACK
            val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
            val cx = (minX + maxX) / 2
            if (kotlin.math.abs(cx - imageW / 2.0) > imageW * 0.15) out += PlacementAdvice.CENTER_COURT
            if (!outside && (maxX - minX) < imageW * 0.45) out += PlacementAdvice.ZOOM_IN
            val far = corners[0].distanceTo(corners[1]); val near = corners[3].distanceTo(corners[2])
            if (near > 0 && far / near < 0.22) out += PlacementAdvice.RAISE_CAMERA
        }
        return if (out.isEmpty()) listOf(PlacementAdvice.OK) else out
    }
}
