package com.courtvision.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

class PickleballEngineTest {
    @Test fun doublesStartsAtZeroZeroTwo() {
        val r = PickleballRules()
        val e = PickleballEngine(r)
        assertEquals("0-0-2", PickleballEngine.call(e.initial(Side.A), r))
    }

    @Test fun onlyServingTeamScoresInSideOut() {
        val r = PickleballRules()
        val e = PickleballEngine(r)
        var s = e.initial(Side.A)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(1, s.scoreA)
        // A loses on server 2 at game start -> side out, B serves with server 1
        s = e.apply(s, ScoringEvent.Rally(Side.B))
        assertEquals(0, s.scoreB)
        assertEquals(Side.B, s.servingSide)
        assertEquals(1, s.serverNumber)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(Side.B, s.servingSide)
        assertEquals(2, s.serverNumber)
        assertEquals("0-1-2", PickleballEngine.call(s, r))
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(Side.A, s.servingSide)
    }

    @Test fun gameToElevenWinByTwo() {
        val r = PickleballRules(scoring = PickleballScoring.RALLY)
        val e = PickleballEngine(r)
        var s = e.initial(Side.A)
        repeat(10) { s = e.apply(s, ScoringEvent.Rally(Side.A)); s = e.apply(s, ScoringEvent.Rally(Side.B)) }
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(11, s.scoreA)
        s = e.apply(s, ScoringEvent.Rally(Side.A))
        assertEquals(Side.A, s.winner)
        assertEquals(listOf(12 to 10), s.games)
    }

    @Test fun singlesSideOutSwitchesImmediately() {
        val r = PickleballRules(doubles = false)
        val e = PickleballEngine(r)
        var s = e.initial(Side.A)
        s = e.apply(s, ScoringEvent.Rally(Side.B))
        assertEquals(Side.B, s.servingSide)
        assertEquals(0, s.scoreB)
    }
}
