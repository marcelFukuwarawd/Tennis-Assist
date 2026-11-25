package com.courtvision.vision

/** One trajectory sample. [observed] = false means the point was predicted (no detection). */
data class TrackPoint(
    val frame: Long,
    val timeMs: Long,
    val x: Double,
    val y: Double,
    val observed: Boolean,
    val confidence: Double,
    val radius: Double,
) {
    val pos: Vec2 get() = Vec2(x, y)
}

/**
 * Single-ball tracker: Kalman prediction + gated nearest-candidate association.
 * Coasting (prediction without evidence) is limited and always flagged as not observed.
 */
class BallTracker(
    /** Max distance (px) between prediction and detection, scaled with uncertainty. */
    private val baseGatePx: Double = 40.0,
    private val maxCoastFrames: Int = 6,
    /** Frames of consistent detections before a track is confirmed. */
    private val confirmFrames: Int = 3,
) {
    private var kf: KalmanTracker? = null
    private var lastTimeMs: Long = 0
    private var misses = 0
    private var hits = 0
    private val pending = ArrayList<TrackPoint>()

    val isTracking: Boolean get() = kf != null && hits >= confirmFrames

    fun reset() { kf = null; misses = 0; hits = 0; pending.clear() }

    /**
     * Processes the candidates of one frame and returns the track points that became final
     * (confirmed tracks release their buffered points at once).
     */
    fun update(frame: Long, timeMs: Long, candidates: List<BallCandidate>): List<TrackPoint> {
        val filter = kf
        if (filter == null) {
            val best = candidates.maxByOrNull { it.score } ?: return emptyList()
            kf = KalmanTracker(best.x, best.y)
            lastTimeMs = timeMs; hits = 1; misses = 0
            pending.clear()
            pending += TrackPoint(frame, timeMs, best.x, best.y, true, best.score, best.radius)
            return emptyList()
        }
        val dt = ((timeMs - lastTimeMs).coerceAtLeast(1)) / 1000.0
        lastTimeMs = timeMs
        val pred = filter.predict(dt)
        val speed = filter.velocity.length * dt
        val gate = baseGatePx + 1.5 * speed + 2.0 * filter.positionSigma.coerceAtMost(60.0)
        var best: BallCandidate? = null
        var bestCost = Double.MAX_VALUE
        for (c in candidates) {
            val d = c.pos.distanceTo(pred)
            if (d > gate) continue
            val cost = d / gate - 0.5 * c.score
            if (cost < bestCost) { bestCost = cost; best = c }
        }
        if (best != null) {
            filter.update(best.x, best.y, (best.radius * 0.5).coerceAtLeast(1.0))
            misses = 0; hits++
            val conf = (best.score * (1.0 - (best.pos.distanceTo(pred) / gate) * 0.5)).coerceIn(0.0, 1.0)
            val p = TrackPoint(frame, timeMs, best.x, best.y, true, conf, best.radius)
            return emit(p)
        }
        misses++
        if (misses > maxCoastFrames) {
            val wasConfirmed = hits >= confirmFrames
            reset()
            // a lost track may restart from a strong candidate anywhere
            if (candidates.isNotEmpty()) update(frame, timeMs, candidates)
            return if (wasConfirmed) emptyList() else emptyList()
        }
        val p = TrackPoint(frame, timeMs, pred.x, pred.y, false, 0.0, 0.0)
        return emit(p)
    }

    private fun emit(p: TrackPoint): List<TrackPoint> {
        if (hits < confirmFrames) { pending += p; return emptyList() }
        if (pending.isNotEmpty()) {
            val out = ArrayList(pending); pending.clear(); out += p; return out
        }
        return listOf(p)
    }
}
