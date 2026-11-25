package com.courtvision.ai

import com.courtvision.ai.data.AppJson
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.RulesConfig
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.SessionMode
import com.courtvision.ai.data.pickleballRules
import com.courtvision.ai.data.scoringSession
import com.courtvision.ai.data.tennisRules
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.processing.PipelineConfig
import com.courtvision.ai.data.ProcessingMode
import com.courtvision.scoring.PickleballScoring
import com.courtvision.scoring.ScoringEvent
import com.courtvision.scoring.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MappersTest {
    private fun session(mode: SessionMode, rules: RulesConfig = RulesConfig()) = SessionEntity(
        title = "t", mode = mode, createdAt = 0, rulesJson = AppJson.encodeToString(RulesConfig.serializer(), rules),
    )

    @Test fun serverOverrideBecomesScoringEvent() {
        val pts = listOf(
            PointEntity(sessionId = 1, order = 0, winnerA = true),
            PointEntity(sessionId = 1, order = 1, winnerA = false, serverOverrideA = false),
        )
        val ev = pts.toScoringEvents()
        assertEquals(3, ev.size)
        assertEquals(ScoringEvent.SetServer(Side.B, 0), ev[1])
    }

    @Test fun rulesAreParsed() {
        val s = session(SessionMode.TENNIS_DOUBLES, RulesConfig(bestOf = 5, noAd = true))
        assertEquals(5, s.tennisRules().bestOf)
        assertTrue(s.tennisRules().noAd)
        assertTrue(s.tennisRules().doubles)
        val p = session(SessionMode.PICKLEBALL_SINGLES, RulesConfig(pickleballRally = true, pickleballPoints = 21))
        assertEquals(PickleballScoring.RALLY, p.pickleballRules().scoring)
        assertEquals(21, p.pickleballRules().pointsToWin)
    }

    @Test fun scoreIsRecomputedFromStoredPoints() {
        val s = session(SessionMode.TENNIS_SINGLES)
        val pts = List(4) { PointEntity(sessionId = 1, order = it, winnerA = true) }
        val board = s.scoringSession().board(pts.toScoringEvents())
        assertEquals(listOf(1), board.setsOrGamesA)
    }

    @Test fun processingModesScale() {
        assertTrue(PipelineConfig.of(ProcessingMode.ECONOMY).analysisWidth < PipelineConfig.of(ProcessingMode.MAX_ACCURACY).analysisWidth)
    }
}
