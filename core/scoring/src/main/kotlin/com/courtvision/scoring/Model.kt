package com.courtvision.scoring

/** Team / player side. In singles a side is a single player. */
enum class Side {
    A, B;

    val other: Side get() = if (this == A) B else A
}

enum class Sport { TENNIS, PICKLEBALL }

/** Tennis configuration. All rules are explicit so custom formats are possible. */
data class TennisRules(
    /** Number of sets in the match (1, 3 or 5). A side needs bestOf/2+1 sets. */
    val bestOf: Int = 3,
    /** Games needed to win a set. */
    val gamesPerSet: Int = 6,
    /** No-advantage scoring: deciding point at deuce. */
    val noAd: Boolean = false,
    /** A tie-break is played when games reach gamesPerSet-all. */
    val tiebreakEnabled: Boolean = true,
    val tiebreakPoints: Int = 7,
    /** If true the deciding set is replaced by a match tie-break. */
    val finalSetMatchTiebreak: Boolean = false,
    val matchTiebreakPoints: Int = 10,
    val doubles: Boolean = false,
) {
    init {
        require(bestOf >= 1 && bestOf % 2 == 1) { "bestOf must be odd" }
        require(gamesPerSet >= 1)
        require(tiebreakPoints >= 1 && matchTiebreakPoints >= 1)
    }

    val setsToWin: Int get() = bestOf / 2 + 1
}

enum class PickleballScoring { SIDE_OUT, RALLY }

data class PickleballRules(
    val scoring: PickleballScoring = PickleballScoring.SIDE_OUT,
    val pointsToWin: Int = 11,
    val winBy: Int = 2,
    /** Number of games in the match (1, 3 or 5). */
    val bestOf: Int = 1,
    val doubles: Boolean = true,
) {
    init {
        require(bestOf >= 1 && bestOf % 2 == 1) { "bestOf must be odd" }
        require(pointsToWin >= 1 && winBy >= 1)
    }

    val gamesToWin: Int get() = bestOf / 2 + 1
}

/** Ordered scoring input. Score is ALWAYS recomputed from the full list of events. */
sealed interface ScoringEvent {
    /** A rally finished and [winner] won it. */
    data class Rally(val winner: Side) : ScoringEvent

    /** Manual correction of who is serving from this point on. */
    data class SetServer(val side: Side, val playerIndex: Int = 0) : ScoringEvent
}
