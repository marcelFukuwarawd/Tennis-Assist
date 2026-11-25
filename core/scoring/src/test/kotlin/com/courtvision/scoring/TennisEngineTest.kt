package com.courtvision.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TennisEngineTest {
    private fun rallies(vararg w: Side) = w.map { ScoringEvent.Rally(it) }
    private fun repeat(side: Side, n: Int) = List(n) { ScoringEvent.Rally(side) }
    private fun game(side: Side) = repeat(side, 4)

    @Test fun pointCallsFollowTennisSequence() {
        val e = TennisEngine(TennisRules())
        val (s, _) = e.replay(Side.A, rallies(Side.A, Side.A, Side.B))
        assertEquals("30-15", TennisEngine.gameScoreLabel(s, e.rules))
    }

    @Test fun deuceAndAdvantage() {
        val e = TennisEngine(TennisRules())
        val ev = rallies(Side.A, Side.A, Side.A, Side.B, Side.B, Side.B)
        var (s, _) = e.replay(Side.A, ev)
        assertEquals("40-40", TennisEngine.gameScoreLabel(s, e.rules))
        s = e.replay(Side.A, ev + rallies(Side.B)).first
        assertEquals("40-AD", TennisEngine.gameScoreLabel(s, e.rules))
        s = e.replay(Side.A, ev + rallies(Side.B, Side.A)).first
        assertEquals("40-40", TennisEngine.gameScoreLabel(s, e.rules))
        s = e.replay(Side.A, ev + rallies(Side.A, Side.A)).first
        assertEquals(1, s.gamesA)
        assertEquals(0, s.pointsA)
    }

    @Test fun noAdDecidingPoint() {
        val e = TennisEngine(TennisRules(noAd = true))
        val s = e.replay(Side.A, rallies(Side.A, Side.A, Side.A, Side.B, Side.B, Side.B, Side.B)).first
        assertEquals(1, s.gamesB)
    }

    @Test fun serverAlternatesEachGameAndEndsChangeOnOddGames() {
        val e = TennisEngine(TennisRules())
        var s = e.replay(Side.A, game(Side.A)).first
        assertEquals(Side.B, s.server)
        assertFalse(s.aOnNearEnd) // changed ends after game 1
        s = e.replay(Side.A, game(Side.A) + game(Side.A)).first
        assertEquals(Side.A, s.server)
        assertFalse(s.aOnNearEnd) // no change after game 2
    }

    @Test fun setWonSixFour() {
        val e = TennisEngine(TennisRules())
        val ev = (1..4).flatMap { game(Side.A) + game(Side.B) } + game(Side.A) + game(Side.A)
        val s = e.replay(Side.A, ev).first
        assertEquals(listOf(6 to 4), s.sets)
        assertEquals(0, s.gamesA)
    }

    @Test fun tiebreakAtSixAllWithRotation() {
        val e = TennisEngine(TennisRules())
        val ev = (1..6).flatMap { game(Side.A) + game(Side.B) }
        var s = e.replay(Side.A, ev).first
        assertTrue(s.inTiebreak)
        val first = s.server
        // after one tie-break point the other player serves two points
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(first.other, s.server)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(first.other, s.server)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(first, s.server)
        // finish 7-0
        repeat(4) { s = e.apply(s, ScoringEvent.Rally(Side.A)) }
        assertEquals(listOf(7 to 6), s.sets)
        assertEquals(7 to 0, s.tiebreakScores[0])
        // receiver of the first tie-break point serves first in the next set
        assertEquals(first.other, s.server)
    }

    @Test fun tiebreakRequiresTwoPointMargin() {
        val e = TennisEngine(TennisRules())
        var s = e.replay(Side.A, (1..6).flatMap { game(Side.A) + game(Side.B) }).first
        repeat(6) { s = e.apply(s, ScoringEvent.Rally(Side.A)); s = e.apply(s, ScoringEvent.Rally(Side.B)) }
        assertTrue(s.inTiebreak)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertTrue(s.inTiebreak)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(1, s.sets.size)
    }

    @Test fun bestOfThreeMatchEnds() {
        val e = TennisEngine(TennisRules(bestOf = 3))
        val set = (1..6).flatMap { game(Side.B) }
        val (s, _) = e.replay(Side.A, set + set)
        assertEquals(Side.B, s.winner)
        // further rallies are ignored
        val s2 = e.replay(Side.A, set + set + game(Side.A)).first
        assertEquals(s, s2)
    }

    @Test fun matchTiebreakReplacesFinalSet() {
        val e = TennisEngine(TennisRules(bestOf = 3, finalSetMatchTiebreak = true))
        val setA = (1..6).flatMap { game(Side.A) }
        val setB = (1..6).flatMap { game(Side.B) }
        var s = e.replay(Side.A, setA + setB).first
        assertTrue(s.matchTiebreak)
        repeat(9) { s = e.apply(s, ScoringEvent.Rally(Side.A)) }
        assertNull(s.winner)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(Side.A, s.winner)
        assertEquals(1 to 0, s.sets.last())
    }

    @Test fun breakPointIsDetected() {
        val e = TennisEngine(TennisRules())
        // A serves; B leads 0-40
        val (_, ctx) = e.replay(Side.A, rallies(Side.B, Side.B, Side.B, Side.B))
        assertFalse(ctx[0].isBreakPoint)
        assertTrue(ctx[3].isBreakPoint)
        assertTrue(ctx[3].gameWon)
    }

    @Test fun manualServerCorrection() {
        val e = TennisEngine(TennisRules())
        val s = e.replay(Side.A, listOf(ScoringEvent.SetServer(Side.B)) + game(Side.A)).first
        assertEquals(Side.A, s.server)
    }

    @Test fun doublesServerRotationCyclesFourPlayers() {
        val e = TennisEngine(TennisRules(doubles = true))
        val servers = mutableListOf<Pair<Side, Int>>()
        var s = e.initial(Side.A)
        repeat(5) {
            servers += s.server to s.serverPlayer
            repeat(4) { s = e.apply(s, ScoringEvent.Rally(Side.A)) }
        }
        assertEquals(listOf(Side.A to 0, Side.B to 0, Side.A to 1, Side.B to 1, Side.A to 0), servers)
    }

    @Test fun historyUndoRedo() {
        val h = History(listOf<Int>())
        h.push(listOf(1)); h.push(listOf(1, 2))
        assertEquals(listOf(1), h.undo())
        assertEquals(listOf(1, 2), h.redo())
        h.undo(); h.push(listOf(1, 3))
        assertFalse(h.canRedo)
    }
}
