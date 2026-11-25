package com.courtvision.analysis

import com.courtvision.scoring.ScoringSession
import com.courtvision.scoring.Side
import com.courtvision.scoring.Sport
import com.courtvision.scoring.TennisRules
import com.courtvision.vision.BallEvent
import com.courtvision.vision.BallEventType
import com.courtvision.vision.CallResult
import com.courtvision.vision.CourtModel
import com.courtvision.vision.ShotType
import com.courtvision.vision.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {
    private val court = CourtModel.TENNIS

    private fun ev(t: BallEventType, ms: Long, y: Double) = BallEvent(t, ms / 33, ms, Vec2(0.0, 0.0), Vec2(5.0, y), Vec2(0.05, 0.05), 0.8)

    @Test fun segmentsRalliesByGaps() {
        val e = listOf(ev(BallEventType.HIT, 0, 23.0), ev(BallEventType.BOUNCE, 900, 5.0), ev(BallEventType.HIT, 10_000, 23.0))
        assertEquals(2, RallySegmenter.segment(e).size)
    }

    @Test fun outBallGivesPointToOpponentAndInconclusiveNeedsReview() {
        val session = ScoringSession(Sport.TENNIS)
        val inf = PointInference(court, session)
        val outRally = listOf(ev(BallEventType.HIT, 0, 23.0), ev(BallEventType.BOUNCE, 900, -0.5))
        val unknownRally = listOf(ev(BallEventType.HIT, 10_000, 23.0), ev(BallEventType.BOUNCE, 10_900, 0.01))
        val props = inf.infer(listOf(outRally, unknownRally), calls = { if (it.court!!.y < 0) CallResult.OUT else CallResult.INCONCLUSIVE })
        assertEquals(2, props.size)
        // A starts on the near end, so the near hitter is A and loses the first point
        assertEquals(Side.B, props[0].point.winner)
        assertEquals(PointReason.OUT, props[0].point.reason)
        assertTrue(props[1].point.needsReview)
    }

    @Test fun statisticsUseRealDataOnly() {
        val pts = listOf(
            PointRecord(order = 0, winner = Side.A, reason = PointReason.ACE, serveNumber = 1),
            PointRecord(order = 1, winner = Side.B, reason = PointReason.DOUBLE_FAULT),
            PointRecord(order = 2, winner = Side.A, serveNumber = 2),
        )
        val shots = listOf(
            ShotRecord(timeMs = 0, side = Side.A, playerId = 1, type = ShotType.SERVE, speedKmh = 150.0, hitterCourt = null, bounce = Vec2(4.0, 8.0), call = CallResult.IN, rallyIndex = 0, indexInRally = 0),
            ShotRecord(timeMs = 5000, side = Side.A, playerId = 1, type = ShotType.FOREHAND, speedKmh = null, hitterCourt = null, bounce = Vec2(4.0, 1.0), call = CallResult.IN, rallyIndex = 1, indexInRally = 1),
        )
        val s = StatisticsCalculator.compute(Sport.TENNIS, court, TennisRules(), Side.A, pts, shots, listOf(RallyRecord(0, 0, 3000, 1), RallyRecord(1, 4000, 9000, 3)))
        assertEquals(1, s.a.aces)
        assertEquals(1, s.a.doubleFaults)
        assertEquals(Ratio(1, 3), s.a.firstServeIn)
        assertEquals(150.0, s.a.maxSpeedKmh!!, 1e-9)
        assertNull(s.b.maxSpeedKmh) // no data -> no number
        assertEquals(3, s.longestRallyShots)
        assertEquals(Ratio(1, 2), s.a.deepShots)
    }

    @Test fun coachNeedsEnoughSamples() {
        val coach = Coach(court)
        val stats = SideStats(Side.A, accuracyByType = mapOf(ShotType.FOREHAND to Ratio(9, 10), ShotType.BACKHAND to Ratio(5, 10)))
        val ins = coach.analyse(Side.A, stats, emptyList())
        assertTrue(ins.any { it.kind == InsightKind.BACKHAND_LESS_ACCURATE })
        val few = SideStats(Side.A, accuracyByType = mapOf(ShotType.FOREHAND to Ratio(3, 3), ShotType.BACKHAND to Ratio(0, 3)))
        assertTrue(coach.analyse(Side.A, few, emptyList()).isEmpty())
    }

    @Test fun highlightsMergeOverlaps() {
        val clips = Highlights.condensed(listOf(RallyRecord(0, 2000, 5000, 3), RallyRecord(1, 6000, 9000, 4), RallyRecord(2, 20000, 22000, 2)))
        assertEquals(2, clips.size)
        assertEquals(500, clips[0].startMs)
    }
}
