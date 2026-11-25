package com.courtvision.scoring

/** Sport-agnostic view of the score used by the UI and overlays. */
data class ScoreBoard(
    val sport: Sport,
    val setsOrGamesA: List<Int>,
    val setsOrGamesB: List<Int>,
    val currentA: String,
    val currentB: String,
    val callout: String,
    val server: Side,
    val serverPlayer: Int,
    val aOnNearEnd: Boolean,
    val winner: Side?,
    val pointsPlayed: Int,
)

/** Facade that replays events for either sport. */
class ScoringSession(
    val sport: Sport,
    val tennisRules: TennisRules = TennisRules(),
    val pickleballRules: PickleballRules = PickleballRules(),
    val firstServer: Side = Side.A,
) {
    fun board(events: List<ScoringEvent>): ScoreBoard = when (sport) {
        Sport.TENNIS -> {
            val engine = TennisEngine(tennisRules)
            val (s, ctx) = engine.replay(firstServer, events)
            val label = TennisEngine.gameScoreLabel(s, tennisRules)
            val parts = label.split("-")
            ScoreBoard(
                sport = sport,
                setsOrGamesA = s.sets.map { it.first } + if (s.isOver) emptyList() else listOf(s.gamesA),
                setsOrGamesB = s.sets.map { it.second } + if (s.isOver) emptyList() else listOf(s.gamesB),
                currentA = if (s.isOver) "" else parts[0],
                currentB = if (s.isOver) "" else parts.getOrElse(1) { "" },
                callout = if (s.isOver) TennisEngine.setsLabel(s) else label,
                server = s.server, serverPlayer = s.serverPlayer,
                aOnNearEnd = s.aOnNearEnd, winner = s.winner, pointsPlayed = ctx.size,
            )
        }
        Sport.PICKLEBALL -> {
            val engine = PickleballEngine(pickleballRules)
            val (s, ctx) = engine.replay(firstServer, events)
            ScoreBoard(
                sport = sport,
                setsOrGamesA = s.games.map { it.first } + if (s.isOver) emptyList() else listOf(s.scoreA),
                setsOrGamesB = s.games.map { it.second } + if (s.isOver) emptyList() else listOf(s.scoreB),
                currentA = if (s.isOver) "" else s.scoreA.toString(),
                currentB = if (s.isOver) "" else s.scoreB.toString(),
                callout = PickleballEngine.call(s, pickleballRules),
                server = s.servingSide, serverPlayer = (s.serverNumber - 1).coerceAtLeast(0),
                aOnNearEnd = s.aOnNearEnd, winner = s.winner, pointsPlayed = ctx.size,
            )
        }
    }
}

/** Generic undo/redo history over an immutable list. Never mutates history entries. */
class History<T>(initial: T, private val limit: Int = 200) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    var current: T = initial
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(next: T) {
        undo.addLast(current)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        current = next
    }

    fun undo(): T {
        if (undo.isNotEmpty()) {
            redo.addLast(current)
            current = undo.removeLast()
        }
        return current
    }

    fun redo(): T {
        if (redo.isNotEmpty()) {
            undo.addLast(current)
            current = redo.removeLast()
        }
        return current
    }
}
