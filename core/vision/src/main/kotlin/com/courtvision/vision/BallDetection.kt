package com.courtvision.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Ball candidate in image pixels of the analysed frame. */
data class BallCandidate(
    val x: Double,
    val y: Double,
    val radius: Double,
    /** 0..1 detector score (not a calibrated probability). */
    val score: Double,
    val source: Source,
) {
    enum class Source { COLOR_MOTION, NEURAL, FUSED }
    val pos: Vec2 get() = Vec2(x, y)
}

/** Ball colour profile (hue in degrees 0..360, saturation/value 0..1). */
data class BallColorProfile(
    val hueMin: Double, val hueMax: Double,
    val satMin: Double, val valMin: Double,
) {
    companion object {
        /** ITF optic yellow tennis ball. Works on clay (orange, hue ~15-30) since ranges do not overlap. */
        val TENNIS_OPTIC_YELLOW = BallColorProfile(45.0, 80.0, 0.30, 0.40)
        val PICKLEBALL_YELLOW = BallColorProfile(45.0, 75.0, 0.35, 0.40)
        val PICKLEBALL_ORANGE = BallColorProfile(12.0, 35.0, 0.55, 0.45)
        val PICKLEBALL_GREEN = BallColorProfile(80.0, 150.0, 0.35, 0.35)
    }
}

/**
 * Classical ball detector: colour mask AND frame-difference motion mask, followed by
 * connected components with size/shape filtering. Operates on ARGB pixels (Android Bitmap layout).
 * Deterministic: the same frames always produce the same candidates.
 */
class ColorMotionBallDetector(
    var profile: BallColorProfile = BallColorProfile.TENNIS_OPTIC_YELLOW,
    /** Luminance difference (0..255) needed to consider a pixel moving. */
    var motionThreshold: Int = 18,
    var minAreaPx: Int = 2,
    var maxAreaPx: Int = 900,
    /** When true, strongly coloured static blobs are also accepted (lower score). */
    var allowStatic: Boolean = false,
    /** In low light the colour thresholds are relaxed automatically. */
    var adaptiveLowLight: Boolean = true,
) {
    private var prevLuma: IntArray? = null
    private var mask: ByteArray = ByteArray(0)
    private var labels: IntArray = IntArray(0)
    private var queue: IntArray = IntArray(0)

    fun reset() { prevLuma = null }

    /** Mean luminance of the last processed frame (0..255), useful for low-light warnings. */
    var lastMeanLuma: Double = 0.0
        private set

    fun detect(pixels: IntArray, width: Int, height: Int, roi: IntArray? = null): List<BallCandidate> {
        val n = width * height
        require(pixels.size >= n)
        if (mask.size != n) { mask = ByteArray(n); labels = IntArray(n); queue = IntArray(n) }
        val luma = IntArray(n)
        var sum = 0L
        for (i in 0 until n) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            luma[i] = l
            sum += l
        }
        lastMeanLuma = sum.toDouble() / n
        val lowLight = adaptiveLowLight && lastMeanLuma < 60
        val valMin = if (lowLight) profile.valMin * 0.55 else profile.valMin
        val satMin = if (lowLight) profile.satMin * 0.75 else profile.satMin
        val prev = prevLuma
        val x0 = roi?.get(0)?.coerceIn(0, width) ?: 0
        val y0 = roi?.get(1)?.coerceIn(0, height) ?: 0
        val x1 = roi?.get(2)?.coerceIn(0, width) ?: width
        val y1 = roi?.get(3)?.coerceIn(0, height) ?: height
        java.util.Arrays.fill(mask, 0)
        for (y in y0 until y1) {
            val row = y * width
            for (x in x0 until x1) {
                val i = row + x
                val c = pixels[i]
                val r = ((c shr 16) and 0xFF) / 255.0
                val g = ((c shr 8) and 0xFF) / 255.0
                val b = (c and 0xFF) / 255.0
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val d = mx - mn
                if (mx < valMin || mx <= 0.0) continue
                val s = d / mx
                if (s < satMin || d <= 1e-6) continue
                val h = when (mx) {
                    r -> 60.0 * (((g - b) / d) % 6.0)
                    g -> 60.0 * ((b - r) / d + 2.0)
                    else -> 60.0 * ((r - g) / d + 4.0)
                }.let { if (it < 0) it + 360.0 else it }
                if (h < profile.hueMin || h > profile.hueMax) continue
                val moving = prev != null && abs(luma[i] - prev[i]) >= motionThreshold
                mask[i] = when {
                    moving -> 2
                    allowStatic || prev == null -> 1
                    else -> 0
                }
            }
        }
        prevLuma = luma
        return components(width, height)
    }

    private fun components(width: Int, height: Int): List<BallCandidate> {
        java.util.Arrays.fill(labels, 0)
        val out = ArrayList<BallCandidate>()
        var label = 0
        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || labels[start] != 0) continue
            label++
            var head = 0; var tail = 0
            queue[tail++] = start; labels[start] = label
            var area = 0; var sx = 0L; var sy = 0L; var moving = 0
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            while (head < tail) {
                val i = queue[head++]
                val x = i % width; val y = i / width
                area++; sx += x; sy += y
                if (mask[i].toInt() == 2) moving++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (area > maxAreaPx * 4) continue // keep labelling but it will be rejected
                // 8-connectivity
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val j = ny * width + nx
                    if (mask[j].toInt() != 0 && labels[j] == 0) { labels[j] = label; queue[tail++] = j }
                }
            }
            if (area < minAreaPx || area > maxAreaPx) continue
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val aspect = max(bw, bh).toDouble() / min(bw, bh)
            // motion blur elongates the ball: accept up to 4:1
            if (aspect > 4.0) continue
            val fill = area.toDouble() / (bw * bh)
            if (fill < 0.3) continue
            val motionRatio = moving.toDouble() / area
            val score = (0.45 * motionRatio + 0.35 * fill + 0.2 * (1.0 / aspect)).coerceIn(0.0, 1.0)
            out += BallCandidate(
                x = sx.toDouble() / area, y = sy.toDouble() / area,
                radius = sqrt(area / PI), score = score, source = BallCandidate.Source.COLOR_MOTION,
            )
        }
        return out.sortedByDescending { it.score }.take(16)
    }
}

/**
 * Rejects candidates that stay in the same place for a long time (balls lying on the court,
 * yellow logos, fences). Uses a coarse occupancy grid with exponential decay.
 */
class StaticObjectFilter(private val width: Int, private val height: Int, private val cell: Int = 12) {
    private val gw = (width + cell - 1) / cell
    private val gh = (height + cell - 1) / cell
    private val heat = FloatArray(gw * gh)

    fun filter(cands: List<BallCandidate>): List<BallCandidate> {
        for (i in heat.indices) heat[i] *= 0.97f
        val out = ArrayList<BallCandidate>()
        for (c in cands) {
            val gx = (c.x / cell).toInt().coerceIn(0, gw - 1)
            val gy = (c.y / cell).toInt().coerceIn(0, gh - 1)
            val idx = gy * gw + gx
            heat[idx] += 1f
            if (heat[idx] < 12f) out += c
        }
        return out
    }
}

/** Fuses neural detections (e.g. "sports ball") with colour/motion candidates. */
object CandidateFusion {
    fun fuse(classic: List<BallCandidate>, neural: List<BallCandidate>, mergeRadius: Double = 8.0): List<BallCandidate> {
        if (neural.isEmpty()) return classic
        val used = BooleanArray(classic.size)
        val out = ArrayList<BallCandidate>()
        for (nc in neural) {
            var best = -1; var bestD = mergeRadius
            classic.forEachIndexed { i, c -> val d = c.pos.distanceTo(nc.pos); if (!used[i] && d < bestD) { bestD = d; best = i } }
            if (best >= 0) {
                used[best] = true
                val c = classic[best]
                out += BallCandidate((c.x + nc.x) / 2, (c.y + nc.y) / 2, max(c.radius, nc.radius), min(1.0, 0.5 + 0.5 * max(c.score, nc.score)), BallCandidate.Source.FUSED)
            } else out += nc
        }
        classic.forEachIndexed { i, c -> if (!used[i]) out += c }
        return out.sortedByDescending { it.score }
    }
}
