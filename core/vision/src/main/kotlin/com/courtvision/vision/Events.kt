package com.courtvision.vision

import kotlin.math.abs

enum class BallEventType { BOUNCE, HIT }

data class BallEvent(
    val type: BallEventType,
    val frame: Long,
    val timeMs: Long,
    /** Refined image position of the event. */
    val image: Vec2,
    /** Court position (metres) for bounces; for hits the projection is only approximate. */
    val court: Vec2?,
    /** 1-sigma uncertainty in metres along court X/Y (bounces only). */
    val sigma: Vec2?,
    /** Detector strength of the kink 0..1 (not a calibrated probability). */
    val strength: Double,
)

/**
 * Detects bounces and racket hits from a ball trajectory by looking for velocity discontinuities.
 *
 * Physics: at a bounce the vertical velocity flips sign -> in the image the ball's vertical
 * velocity jumps "upwards" (negative dy). A kink that happens at a player's racket zone is a
 * HIT. Note that the ground projection of an airborne ball is NOT its ground position, so the
 * along-court direction is not used to separate hits from bounces (it flips at bounces too).
 * Only OBSERVED points are used, so no events are invented from predicted positions.
 */
class EventDetector(
    private val window: Int = 3,
    private val minGapMs: Long = 120,
    /** Minimum relative vertical-velocity jump for a bounce. */
    private val bounceJump: Double = 0.6,
    private val pixelSigma: Double = 2.0,
) {
    /**
     * @param players returns the player boxes visible at a timestamp (may be empty). Used to
     *  recognise racket contacts.
     */
    fun detect(
        track: List<TrackPoint>,
        toCourt: Homography?,
        players: ((Long) -> List<Box>)? = null,
    ): List<BallEvent> {
        val pts = track.filter { it.observed }
        if (pts.size < 2 * window + 1) return emptyList()
        val raw = ArrayList<BallEvent>()
        for (i in window until pts.size - window) {
            val before = pts.subList(i - window, i + 1)
            val after = pts.subList(i, i + window + 1)
            // reject windows spanning large time gaps (lost track)
            if (after.last().timeMs - before.first().timeMs > 1200) continue
            val vb = velocity(before) ?: continue
            val va = velocity(after) ?: continue
            val speed = maxOf(vb.length, va.length, 1.0)
            val dvy = va.y - vb.y
            val turn = 1.0 - (vb.dot(va) / (vb.length * va.length + 1e-9)) // 0 = same direction, 2 = opposite
            val p = pts[i]
            val atPlayer = players?.invoke(p.timeMs)?.any { inRacketZone(it, p.pos) } ?: false
            if (atPlayer && turn > 0.15) {
                raw += BallEvent(BallEventType.HIT, p.frame, p.timeMs, p.pos, toCourt?.map(p.pos), null, (0.5 + turn / 4).coerceIn(0.0, 1.0))
            } else if (dvy < 0 && (-dvy / speed) > bounceJump) {
                val refined = intersect(before, after) ?: p.pos
                val court = toCourt?.map(refined)
                // temporal quantisation: bounce lies between frames -> half-frame displacement
                val dtMs = (after[1].timeMs - p.timeMs).coerceAtLeast(1)
                val quant = vb.length * dtMs / 1000.0 / 2.0
                val sigmaPx = kotlin.math.sqrt(pixelSigma * pixelSigma + quant * quant + (p.radius / 2).let { it * it })
                val sigma = toCourt?.propagateSigma(refined, sigmaPx)
                raw += BallEvent(BallEventType.BOUNCE, p.frame, p.timeMs, refined, court, sigma, ((-dvy / speed) / 2).coerceIn(0.0, 1.0))
            } else if (turn > 1.0 && players == null) {
                // without player information a sharp reversal (> 90 degrees) far from a bounce is a probable hit
                raw += BallEvent(BallEventType.HIT, p.frame, p.timeMs, p.pos, toCourt?.map(p.pos), null, (turn / 4).coerceIn(0.0, 0.5))
            }
        }
        return suppress(raw)
    }

    /** Least-squares velocity (px/s) over the points. */
    private fun velocity(p: List<TrackPoint>): Vec2? {
        val t0 = p.first().timeMs
        val n = p.size
        val ts = p.map { (it.timeMs - t0) / 1000.0 }
        val mt = ts.average()
        val den = ts.sumOf { (it - mt) * (it - mt) }
        if (den < 1e-9) return null
        val mx = p.map { it.x }.average(); val my = p.map { it.y }.average()
        var nx = 0.0; var ny = 0.0
        for (k in 0 until n) { nx += (ts[k] - mt) * (p[k].x - mx); ny += (ts[k] - mt) * (p[k].y - my) }
        return Vec2(nx / den, ny / den)
    }

    /** Racket reach zone around a player box: box widened by 60% and raised by 30% of height. */
    private fun inRacketZone(b: Box, p: Vec2): Boolean {
        val mx = b.width * 0.6; val my = b.height * 0.3
        return p.x in (b.left - mx)..(b.right + mx) && p.y in (b.top - my)..(b.bottom + my * 0.3)
    }

    /** Intersection of the incoming and outgoing straight segments for sub-frame accuracy. */
    private fun intersect(b: List<TrackPoint>, a: List<TrackPoint>): Vec2? {
        val p1 = b.first().pos; val p2 = b.last().pos
        val p3 = a.first().pos; val p4 = a.last().pos
        val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (abs(d) < 1e-9) return null
        val t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / d
        val r = Vec2(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y))
        // keep refinement local
        return if (r.distanceTo(p2) > 3 * (p2.distanceTo(p1) + 1)) null else r
    }

    private fun suppress(ev: List<BallEvent>): List<BallEvent> {
        val out = ArrayList<BallEvent>()
        for (e in ev.sortedBy { it.timeMs }) {
            val last = out.lastOrNull()
            if (last != null && e.timeMs - last.timeMs < minGapMs) {
                if (e.strength > last.strength) out[out.size - 1] = e
            } else out += e
        }
        return out
    }
}
