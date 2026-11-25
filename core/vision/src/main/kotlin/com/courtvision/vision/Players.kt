package com.courtvision.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = max(0.0, width) * max(0.0, height)
    val centre get() = Vec2((left + right) / 2, (top + bottom) / 2)
    /** Ground contact point (feet) used for court projection. */
    val foot get() = Vec2((left + right) / 2, bottom)

    fun iou(o: Box): Double {
        val ix = max(0.0, min(right, o.right) - max(left, o.left))
        val iy = max(0.0, min(bottom, o.bottom) - max(top, o.top))
        val inter = ix * iy
        val u = area + o.area - inter
        return if (u <= 0) 0.0 else inter / u
    }
}

data class PersonDetection(val box: Box, val score: Double, val appearance: FloatArray? = null)

data class PlayerTrack(
    val id: Int,
    var box: Box,
    var lastFrame: Long,
    var lastTimeMs: Long,
    var hits: Int,
    var appearance: FloatArray?,
    var court: Vec2?,
)

data class PlayerSample(val playerId: Int, val timeMs: Long, val box: Box, val court: Vec2?)

/**
 * Lightweight multi-person tracker (SORT/ByteTrack style greedy IoU association) with
 * appearance re-identification from clothing colour histograms. No face recognition is used.
 * Only people standing in or around the court are tracked (max [maxPlayers]).
 */
class PlayerTracker(
    private val maxPlayers: Int = 4,
    private val iouThreshold: Double = 0.2,
    private val maxLostMs: Long = 4000,
    private val court: CourtModel? = null,
) {
    private val active = ArrayList<PlayerTrack>()
    private val lost = ArrayList<PlayerTrack>()
    private var nextId = 1

    fun tracks(): List<PlayerTrack> = active.toList()

    /** Returns true if the court point lies inside the playing area extended by run-off margins. */
    private fun onCourt(p: Vec2?): Boolean {
        val c = court ?: return true
        if (p == null) return true
        val backMargin = if (c.type == CourtType.TENNIS) 6.5 else 3.0
        return p.x in -4.0..(c.doublesWidth + 4.0) && p.y in -backMargin..(c.length + backMargin)
    }

    fun update(frame: Long, timeMs: Long, detections: List<PersonDetection>, toCourt: Homography?): List<PlayerSample> {
        val dets = detections
            .map { it to toCourt?.map(it.box.foot) }
            .filter { onCourt(it.second) }
            .sortedByDescending { it.first.score }
        val usedDet = BooleanArray(dets.size)
        // 1) IoU association with active tracks (greedy by best IoU)
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        active.forEachIndexed { ti, t -> dets.forEachIndexed { di, d -> val iou = t.box.iou(d.first.box); if (iou >= iouThreshold) pairs += Triple(ti, di, iou) } }
        val usedTrack = BooleanArray(active.size)
        for ((ti, di, _) in pairs.sortedByDescending { it.third }) {
            if (usedTrack[ti] || usedDet[di]) continue
            usedTrack[ti] = true; usedDet[di] = true
            val t = active[ti]; val d = dets[di]
            t.box = d.first.box; t.lastFrame = frame; t.lastTimeMs = timeMs; t.hits++; t.court = d.second
            t.appearance = blend(t.appearance, d.first.appearance)
        }
        // 2) move stale tracks to the lost pool
        val it = active.iterator()
        while (it.hasNext()) { val t = it.next(); if (timeMs - t.lastTimeMs > 700) { lost += t; it.remove() } }
        lost.removeAll { timeMs - it.lastTimeMs > maxLostMs * 15 } // keep identities ~1 minute
        // 3) unmatched detections: re-identify from lost tracks or create new ones
        dets.forEachIndexed { di, d ->
            if (usedDet[di] || active.size >= maxPlayers) return@forEachIndexed
            val app = d.first.appearance
            var best: PlayerTrack? = null; var bestSim = 0.6
            if (app != null) for (l in lost) {
                val la = l.appearance ?: continue
                val s = bhattacharyya(app, la)
                if (s > bestSim) { bestSim = s; best = l }
            }
            if (best != null) {
                lost.remove(best)
                best.box = d.first.box; best.lastFrame = frame; best.lastTimeMs = timeMs; best.court = d.second
                active += best
            } else if (nextId <= maxPlayers * 3 || lost.isEmpty()) {
                active += PlayerTrack(nextId++, d.first.box, frame, timeMs, 1, app, d.second)
            }
        }
        return active.filter { it.lastFrame == frame }.map { PlayerSample(it.id, timeMs, it.box, it.court) }
    }

    /** Manual identity correction: merges track [from] into [into]. */
    fun relabel(from: Int, into: Int) {
        active.filter { it.id == from }.forEach { t -> active.remove(t); active += t.copy(id = into) }
    }

    private fun blend(a: FloatArray?, b: FloatArray?): FloatArray? {
        if (a == null) return b
        if (b == null || a.size != b.size) return a
        return FloatArray(a.size) { 0.9f * a[it] + 0.1f * b[it] }
    }

    companion object {
        fun bhattacharyya(a: FloatArray, b: FloatArray): Double {
            var s = 0.0
            for (i in a.indices) s += sqrt((a[i] * b[i]).toDouble())
            return s
        }

        /** 4x4x4 RGB histogram of the torso region of [box], normalised to sum 1. */
        fun appearance(pixels: IntArray, width: Int, height: Int, box: Box): FloatArray? {
            val x0 = (box.left + box.width * 0.25).toInt().coerceIn(0, width - 1)
            val x1 = (box.right - box.width * 0.25).toInt().coerceIn(0, width - 1)
            val y0 = (box.top + box.height * 0.2).toInt().coerceIn(0, height - 1)
            val y1 = (box.top + box.height * 0.6).toInt().coerceIn(0, height - 1)
            if (x1 <= x0 || y1 <= y0) return null
            val h = FloatArray(64)
            var n = 0
            for (y in y0..y1) for (x in x0..x1) {
                val c = pixels[y * width + x]
                val r = ((c shr 16) and 0xFF) shr 6; val g = ((c shr 8) and 0xFF) shr 6; val b = (c and 0xFF) shr 6
                h[(r shl 4) or (g shl 2) or b] += 1f; n++
            }
            if (n == 0) return null
            for (i in h.indices) h[i] /= n
            return h
        }
    }
}

/** Movement statistics from court-space player samples. */
object MovementStats {
    data class Result(val distanceMeters: Double, val maxSpeedKmh: Double?, val avgSpeedKmh: Double?, val samples: Int)

    fun compute(samples: List<PlayerSample>): Result {
        val pts = samples.filter { it.court != null }.sortedBy { it.timeMs }
        if (pts.size < 2) return Result(0.0, null, null, pts.size)
        var dist = 0.0; var maxV = 0.0; var moving = 0.0
        for (i in 1 until pts.size) {
            val dt = (pts[i].timeMs - pts[i - 1].timeMs) / 1000.0
            if (dt <= 0 || dt > 2.0) continue
            val d = pts[i].court!!.distanceTo(pts[i - 1].court!!)
            val v = d / dt
            if (v > 9.0) continue // > 32 km/h: identity switch / projection noise, ignore
            if (d < 0.05) continue // jitter
            dist += d; moving += dt
            maxV = max(maxV, v)
        }
        return Result(dist, if (maxV > 0) maxV * 3.6 else null, if (moving > 0) dist / moving * 3.6 else null, pts.size)
    }
}
