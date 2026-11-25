package com.courtvision.scoring

data class PickleballState(
    val games: List<Pair<Int, Int>> = emptyList(),
    val scoreA: Int = 0,
    val scoreB: Int = 0,
    val servingSide: Side = Side.A,
    /** 1 or 2 in side-out doubles. The very first service turn of a game starts at server 2. */
    val serverNumber: Int = 2,
    /** True when the first server of the game is still on its "first turn exception". */
    val winner: Side? = null,
    /** Whether A is on the near end of the court. */
    val aOnNearEnd: Boolean = true,
) {
    val gamesWonA: Int get() = games.count { it.first > it.second }
    val gamesWonB: Int get() = games.count { it.second > it.first }
    val isOver: Boolean get() = winner != null
}

data class PickleballPointContext(
    val index: Int,
    val before: PickleballState,
    val after: PickleballState,
    val winner: Side,
    val scored: Boolean,
    val sideOut: Boolean,
)

/** Pure pickleball scoring state machine (side-out or rally scoring, singles or doubles). */
class PickleballEngine(val rules: PickleballRules) {

    fun initial(firstServer: Side = Side.A): PickleballState = PickleballState(
        servingSide = firstServer,
        serverNumber = if (rules.doubles && rules.scoring == PickleballScoring.SIDE_OUT) 2 else 1,
    )

    fun apply(s: PickleballState, e: ScoringEvent): PickleballState {
        if (s.isOver) return s
        return when (e) {
            is ScoringEvent.SetServer -> s.copy(
                servingSide = e.side,
                serverNumber = if (rules.doubles && rules.scoring == PickleballScoring.SIDE_OUT) (e.playerIndex.coerceIn(0, 1) + 1) else 1,
            )
            is ScoringEvent.Rally -> rally(s, e.winner)
        }
    }

    private fun rally(s: PickleballState, w: Side): PickleballState {
        var a = s.scoreA
        var b = s.scoreB
        var serving = s.servingSide
        var number = s.serverNumber
        when (rules.scoring) {
            PickleballScoring.RALLY -> {
                if (w == Side.A) a++ else b++
                serving = w
                number = 1
            }
            PickleballScoring.SIDE_OUT -> {
                if (w == s.servingSide) {
                    if (w == Side.A) a++ else b++
                } else if (rules.doubles && s.serverNumber == 1) {
                    number = 2
                } else {
                    serving = s.servingSide.other
                    number = 1
                }
            }
        }
        val target = rules.pointsToWin
        val gameWinner = when {
            a >= target && a - b >= rules.winBy -> Side.A
            b >= target && b - a >= rules.winBy -> Side.B
            else -> null
        }
        if (gameWinner == null) {
            return s.copy(scoreA = a, scoreB = b, servingSide = serving, serverNumber = number)
        }
        val games = s.games + (a to b)
        val wonA = games.count { it.first > it.second }
        val wonB = games.count { it.second > it.first }
        val matchWinner = when {
            wonA >= rules.gamesToWin -> Side.A
            wonB >= rules.gamesToWin -> Side.B
            else -> null
        }
        // loser of the previous game serves first in the next one
        return PickleballState(
            games = games,
            servingSide = gameWinner.other,
            serverNumber = if (rules.doubles && rules.scoring == PickleballScoring.SIDE_OUT) 2 else 1,
            winner = matchWinner,
            aOnNearEnd = !s.aOnNearEnd,
        )
    }

    fun replay(firstServer: Side, events: List<ScoringEvent>): Pair<PickleballState, List<PickleballPointContext>> {
        var s = initial(firstServer)
        val ctx = ArrayList<PickleballPointContext>()
        events.forEachIndexed { i, e ->
            val before = s
            s = apply(s, e)
            if (e is ScoringEvent.Rally && !before.isOver) {
                val scored = (s.scoreA + s.scoreB != before.scoreA + before.scoreB) || s.games.size != before.games.size
                ctx += PickleballPointContext(i, before, s, e.winner, scored, s.servingSide != before.servingSide && s.games.size == before.games.size)
            }
        }
        return s to ctx
    }

    companion object {
        /** "4-2-1" style call: serving team score, receiving score, server number (doubles). */
        fun call(s: PickleballState, rules: PickleballRules): String {
            val (srv, rcv) = if (s.servingSide == Side.A) s.scoreA to s.scoreB else s.scoreB to s.scoreA
            return if (rules.doubles && rules.scoring == PickleballScoring.SIDE_OUT) "$srv-$rcv-${s.serverNumber}" else "$srv-$rcv"
        }
    }
}
