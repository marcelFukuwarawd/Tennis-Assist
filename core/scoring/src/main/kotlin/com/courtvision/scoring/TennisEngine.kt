package com.courtvision.scoring

/** Immutable snapshot of a tennis match before or after a rally. */
data class TennisState(
    val sets: List<Pair<Int, Int>> = emptyList(), // completed sets (A games, B games)
    val tiebreakScores: List<Pair<Int, Int>?> = emptyList(), // tie-break points of completed sets
    val gamesA: Int = 0,
    val gamesB: Int = 0,
    val pointsA: Int = 0,
    val pointsB: Int = 0,
    val inTiebreak: Boolean = false,
    val matchTiebreak: Boolean = false,
    val server: Side = Side.A,
    /** Index within the serving team (doubles); always 0 in singles. */
    val serverPlayer: Int = 0,
    /** Server who started the current tie-break (needed for rotation). */
    val tiebreakFirstServer: Side = Side.A,
    val tiebreakFirstServerPlayer: Int = 0,
    /** Doubles: which player of each team serves next time that team serves. */
    val nextPlayerA: Int = 0,
    val nextPlayerB: Int = 0,
    /** True if side A is on the "near" end of the court (camera side). */
    val aOnNearEnd: Boolean = true,
    val winner: Side? = null,
) {
    val setsWonA: Int get() = sets.count { (a, b) -> a > b }
    val setsWonB: Int get() = sets.count { (a, b) -> b > a }
    val isOver: Boolean get() = winner != null
}

/** Context of a single rally, used by statistics (break points, set index, server...). */
data class TennisPointContext(
    val index: Int,
    val before: TennisState,
    val after: TennisState,
    val winner: Side,
    val setIndex: Int,
    val isBreakPoint: Boolean,
    val isSetPoint: Boolean,
    val isMatchPoint: Boolean,
    val gameWon: Boolean,
    val setWon: Boolean,
)

/**
 * Pure, deterministic tennis scoring state machine.
 * It never depends on AI: AI only proposes [ScoringEvent]s, which may be corrected.
 */
class TennisEngine(val rules: TennisRules) {

    fun initial(firstServer: Side = Side.A): TennisState =
        TennisState(server = firstServer, tiebreakFirstServer = firstServer)

    private fun isDecidingSet(s: TennisState): Boolean =
        s.setsWonA == rules.setsToWin - 1 && s.setsWonB == rules.setsToWin - 1

    /** Points needed in the current game/tie-break to win, and whether win-by-two applies. */
    private fun gameTarget(s: TennisState): Int = when {
        s.matchTiebreak -> rules.matchTiebreakPoints
        s.inTiebreak -> rules.tiebreakPoints
        else -> 4
    }

    private fun gameWinner(s: TennisState, pA: Int, pB: Int): Side? {
        val target = gameTarget(s)
        if (!s.inTiebreak && !s.matchTiebreak && rules.noAd) {
            if (pA >= 4 && pA > pB) return Side.A
            if (pB >= 4 && pB > pA) return Side.B
            return null
        }
        if (pA >= target && pA - pB >= 2) return Side.A
        if (pB >= target && pB - pA >= 2) return Side.B
        return null
    }

    private fun setWinner(gA: Int, gB: Int, tiebreakPlayed: Boolean): Side? {
        val n = rules.gamesPerSet
        if (tiebreakPlayed) return if (gA > gB) Side.A else Side.B
        if (gA >= n && gA - gB >= 2) return Side.A
        if (gB >= n && gB - gA >= 2) return Side.B
        return null
    }

    /** Server for the next point inside a tie-break, following the 1-2-2 rotation. */
    private fun tiebreakServer(s: TennisState, pointsPlayed: Int): Pair<Side, Int> {
        // point 0: first server; points 1-2: other; 3-4: first; ...
        val block = (pointsPlayed + 1) / 2
        val firstServes = block % 2 == 0
        if (!rules.doubles) {
            return (if (firstServes) s.tiebreakFirstServer else s.tiebreakFirstServer.other) to 0
        }
        // doubles: order rotates through 4 players: F0, O0, F1, O1 starting with the players due to serve
        val f = s.tiebreakFirstServer
        val fPlayer = s.tiebreakFirstServerPlayer
        val oPlayer = if (f == Side.A) s.nextPlayerB else s.nextPlayerA
        val turn = block % 4
        return when (turn) {
            0 -> f to fPlayer
            1 -> f.other to oPlayer
            2 -> f to 1 - fPlayer
            else -> f.other to 1 - oPlayer
        }
    }

    fun apply(s: TennisState, event: ScoringEvent): TennisState {
        if (s.isOver) return s
        return when (event) {
            is ScoringEvent.SetServer -> {
                val p = if (rules.doubles) event.playerIndex.coerceIn(0, 1) else 0
                s.copy(
                    server = event.side, serverPlayer = p,
                    tiebreakFirstServer = if (s.inTiebreak && s.pointsA + s.pointsB == 0) event.side else s.tiebreakFirstServer,
                    tiebreakFirstServerPlayer = if (s.inTiebreak && s.pointsA + s.pointsB == 0) p else s.tiebreakFirstServerPlayer,
                )
            }
            is ScoringEvent.Rally -> rally(s, event.winner)
        }
    }

    private fun rally(s: TennisState, w: Side): TennisState {
        val pA = s.pointsA + if (w == Side.A) 1 else 0
        val pB = s.pointsB + if (w == Side.B) 1 else 0
        val gw = gameWinner(s, pA, pB)
        if (gw == null) {
            var next = s.copy(pointsA = pA, pointsB = pB)
            if (s.inTiebreak || s.matchTiebreak) {
                val (srv, pl) = tiebreakServer(s, pA + pB)
                next = next.copy(server = srv, serverPlayer = pl)
                if ((pA + pB) % 6 == 0) next = next.copy(aOnNearEnd = !next.aOnNearEnd)
            }
            return next
        }
        // ---- game finished ----
        val tiebreakJustPlayed = s.inTiebreak || s.matchTiebreak
        var gA = s.gamesA + if (gw == Side.A) 1 else 0
        var gB = s.gamesB + if (gw == Side.B) 1 else 0
        if (s.matchTiebreak) { // a match tie-break counts as a 1-0 set
            gA = if (gw == Side.A) 1 else 0
            gB = if (gw == Side.B) 1 else 0
        }
        // rotation for the next game
        val nextServerSide: Side
        val nextServerPlayer: Int
        var nextPlayerA = s.nextPlayerA
        var nextPlayerB = s.nextPlayerB
        if (tiebreakJustPlayed) {
            // the team that received first in the tie-break serves first next set
            nextServerSide = s.tiebreakFirstServer.other
            nextServerPlayer = if (nextServerSide == Side.A) nextPlayerA else nextPlayerB
        } else {
            // after a regular game the server's team advances its player index
            if (s.server == Side.A) nextPlayerA = 1 - s.serverPlayer else nextPlayerB = 1 - s.serverPlayer
            nextServerSide = s.server.other
            nextServerPlayer = if (!rules.doubles) 0 else if (nextServerSide == Side.A) nextPlayerA else nextPlayerB
        }
        val sw = setWinner(gA, gB, tiebreakJustPlayed)
        val totalGamesInSet = gA + gB
        if (sw == null) {
            val startTiebreak = rules.tiebreakEnabled && gA == rules.gamesPerSet && gB == rules.gamesPerSet
            val changeEnds = totalGamesInSet % 2 == 1
            return s.copy(
                gamesA = gA, gamesB = gB, pointsA = 0, pointsB = 0,
                inTiebreak = startTiebreak,
                server = nextServerSide,
                serverPlayer = if (rules.doubles) nextServerPlayer else 0,
                tiebreakFirstServer = if (startTiebreak) nextServerSide else s.tiebreakFirstServer,
                tiebreakFirstServerPlayer = if (startTiebreak) nextServerPlayer else s.tiebreakFirstServerPlayer,
                nextPlayerA = nextPlayerA, nextPlayerB = nextPlayerB,
                aOnNearEnd = if (changeEnds) !s.aOnNearEnd else s.aOnNearEnd,
            )
        }
        // ---- set finished ----
        val newSets = s.sets + (gA to gB)
        val newTb = s.tiebreakScores + (if (tiebreakJustPlayed) (pA to pB) else null)
        val setsA = newSets.count { it.first > it.second }
        val setsB = newSets.count { it.second > it.first }
        val matchWinner = when {
            setsA >= rules.setsToWin -> Side.A
            setsB >= rules.setsToWin -> Side.B
            else -> null
        }
        val changeEnds = tiebreakJustPlayed || totalGamesInSet % 2 == 1
        var next = TennisState(
            sets = newSets, tiebreakScores = newTb,
            server = nextServerSide,
            serverPlayer = if (rules.doubles) nextServerPlayer else 0,
            tiebreakFirstServer = nextServerSide,
            tiebreakFirstServerPlayer = nextServerPlayer,
            nextPlayerA = nextPlayerA, nextPlayerB = nextPlayerB,
            aOnNearEnd = if (changeEnds) !s.aOnNearEnd else s.aOnNearEnd,
            winner = matchWinner,
        )
        if (matchWinner == null && rules.finalSetMatchTiebreak && isDecidingSet(next)) {
            next = next.copy(matchTiebreak = true)
        }
        return next
    }

    /** Replays every event and returns the final state plus the per-rally context. */
    fun replay(firstServer: Side, events: List<ScoringEvent>): Pair<TennisState, List<TennisPointContext>> {
        var s = initial(firstServer)
        val ctx = ArrayList<TennisPointContext>()
        events.forEachIndexed { i, e ->
            val before = s
            s = apply(s, e)
            if (e is ScoringEvent.Rally && !before.isOver) {
                val receiver = before.server.other
                ctx += TennisPointContext(
                    index = i, before = before, after = s, winner = e.winner,
                    setIndex = before.sets.size,
                    isBreakPoint = !before.inTiebreak && !before.matchTiebreak &&
                        wouldWinGame(before, receiver),
                    isSetPoint = wouldWinSet(before, Side.A) || wouldWinSet(before, Side.B),
                    isMatchPoint = wouldWinMatch(before, Side.A) || wouldWinMatch(before, Side.B),
                    gameWon = (s.gamesA + s.gamesB != before.gamesA + before.gamesB) || s.sets.size != before.sets.size,
                    setWon = s.sets.size != before.sets.size,
                )
            }
        }
        return s to ctx
    }

    private fun wouldWinGame(s: TennisState, side: Side): Boolean {
        val pA = s.pointsA + if (side == Side.A) 1 else 0
        val pB = s.pointsB + if (side == Side.B) 1 else 0
        return gameWinner(s, pA, pB) == side
    }

    private fun wouldWinSet(s: TennisState, side: Side): Boolean {
        val after = rally(s, side)
        return after.sets.size > s.sets.size
    }

    private fun wouldWinMatch(s: TennisState, side: Side): Boolean = rally(s, side).winner == side

    companion object {
        private val CALLS = listOf("0", "15", "30", "40")

        /** Human readable current game score, e.g. "30-15", "40-40", "AD-40", "5-4" (tie-break). */
        fun gameScoreLabel(s: TennisState, rules: TennisRules): String {
            if (s.inTiebreak || s.matchTiebreak) return "${s.pointsA}-${s.pointsB}"
            val a = s.pointsA
            val b = s.pointsB
            if (a >= 3 && b >= 3 && !rules.noAd) {
                return when {
                    a == b -> "40-40"
                    a > b -> "AD-40"
                    else -> "40-AD"
                }
            }
            return "${CALLS[a.coerceAtMost(3)]}-${CALLS[b.coerceAtMost(3)]}"
        }

        fun setsLabel(s: TennisState): String {
            val parts = s.sets.mapIndexed { i, (a, b) ->
                val tb = s.tiebreakScores.getOrNull(i)
                if (tb != null) "$a-$b(${minOf(tb.first, tb.second)})" else "$a-$b"
            }.toMutableList()
            if (!s.isOver) parts += "${s.gamesA}-${s.gamesB}"
            return parts.joinToString("  ")
        }
    }
}
