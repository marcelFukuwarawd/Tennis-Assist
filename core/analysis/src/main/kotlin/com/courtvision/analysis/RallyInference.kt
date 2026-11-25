package com.courtvision.analysis

import com.courtvision.scoring.ScoringEvent
import com.courtvision.scoring.ScoringSession
import com.courtvision.scoring.Side
import com.courtvision.vision.BallEvent
import com.courtvision.vision.BallEventType
import com.courtvision.vision.CallResult
import com.courtvision.vision.CourtModel

/** Groups ball events into rallies: a gap of more than [maxGapMs] without events ends a rally. */
object RallySegmenter {
    fun segment(events: List<BallEvent>, maxGapMs: Long = 3500): List<List<BallEvent>> {
        val out = ArrayList<MutableList<BallEvent>>()
        for (e in events.sortedBy { it.timeMs }) {
            val cur = out.lastOrNull()
            if (cur == null || e.timeMs - cur.last().timeMs > maxGapMs) out += mutableListOf(e) else cur += e
        }
        // a rally needs at least one hit
        return out.filter { r -> r.any { it.type == BallEventType.HIT } }
    }
}

/**
 * Proposes point winners from rally events. The proposal is ALWAYS reviewable: low confidence
 * points are flagged [PointRecord.needsReview]. Rules:
 * - last bounce OUT -> the last hitter loses;
 * - last bounce IN and no further hit -> the last hitter wins (winner / unreturned);
 * - two consecutive bounces on the same half -> the player on that half loses.
 * The side of the court a hitter is on is mapped to team A/B using the score state (ends change).
 */
class PointInference(private val court: CourtModel, private val session: ScoringSession) {

    data class Proposal(val point: PointRecord, val explanation: String)

    fun infer(
        rallies: List<List<BallEvent>>,
        calls: (BallEvent) -> CallResult?,
        existing: List<PointRecord> = emptyList(),
        reviewThreshold: Double = 0.8,
    ): List<Proposal> {
        val points = existing.toMutableList()
        val out = ArrayList<Proposal>()
        for (rally in rallies) {
            val board = session.board(points.sortedBy { it.order }.map { ScoringEvent.Rally(it.winner) })
            if (board.winner != null) break
            fun sideOfHalf(near: Boolean): Side = if (near == board.aOnNearEnd) Side.A else Side.B
            val hits = rally.filter { it.type == BallEventType.HIT }
            val lastHit = hits.lastOrNull() ?: continue
            val lastHitNear = lastHit.court?.let { court.isNearHalf(it) }
            val after = rally.filter { it.timeMs > lastHit.timeMs && it.type == BallEventType.BOUNCE }
            var winner: Side? = null
            var reason = PointReason.UNKNOWN
            var conf = 0.0
            var why = ""
            val first = after.firstOrNull()
            if (lastHitNear != null && first != null) {
                val call = calls(first)
                val hitter = sideOfHalf(lastHitNear)
                val bounceOnOpponentHalf = first.court?.let { court.isNearHalf(it) != lastHitNear } ?: false
                when {
                    !bounceOnOpponentHalf -> { winner = hitter.other; reason = PointReason.NET; conf = 0.6; why = "bounce_on_own_half" }
                    call == CallResult.OUT -> { winner = hitter.other; reason = PointReason.OUT; conf = 0.85; why = "last_bounce_out" }
                    call == CallResult.IN && after.size >= 2 && after[1].court?.let { court.isNearHalf(it) != lastHitNear } == true -> {
                        winner = hitter; reason = PointReason.DOUBLE_BOUNCE; conf = 0.8; why = "double_bounce"
                    }
                    call == CallResult.IN -> { winner = hitter; reason = PointReason.WINNER; conf = 0.65; why = "last_bounce_in_unreturned" }
                    else -> { winner = hitter; conf = 0.3; why = "inconclusive_call" }
                }
                if (hits.size == 1) { // serve only
                    if (reason == PointReason.WINNER || reason == PointReason.DOUBLE_BOUNCE) reason = PointReason.ACE
                }
            } else if (lastHitNear != null) {
                winner = sideOfHalf(lastHitNear); conf = 0.25; why = "no_bounce_after_last_hit"
            }
            if (winner == null) continue
            val p = PointRecord(
                order = (points.maxOfOrNull { it.order } ?: -1) + 1,
                winner = winner, reason = reason,
                startMs = rally.first().timeMs, endMs = rally.last().timeMs,
                confidence = conf, needsReview = conf < reviewThreshold, source = Source.AUTO,
            )
            points += p
            out += Proposal(p, why)
        }
        return out
    }
}
