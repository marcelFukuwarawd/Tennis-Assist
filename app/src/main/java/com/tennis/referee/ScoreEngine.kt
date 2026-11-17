package com.tennis.referee

class ScoreEngine(private val playerA: String, private val playerB: String) {
    private var scoreA = 0
    private var scoreB = 0
    private val scores = arrayOf("0", "15", "30", "40")

    fun pointTo(isPlayerA: Boolean): String {
        if (isPlayerA) scoreA++ else scoreB++

        if (scoreA >= 4 && scoreA - scoreB >= 2) {
            scoreA = 0; scoreB = 0
            return "Game $playerA!"
        } else if (scoreB >= 4 && scoreB - scoreA >= 2) {
            scoreA = 0; scoreB = 0
            return "Game $playerB!"
        }

        val displayA = if (scoreA >= 3 && scoreB >= 3) {
            if (scoreA == scoreB) "40 (Deuce)" else if (scoreA > scoreB) "AD" else "40"
        } else if (scoreA < 4) scores[scoreA] else "AD"

        val displayB = if (scoreA >= 3 && scoreB >= 3) {
            if (scoreA == scoreB) "40 (Deuce)" else if (scoreB > scoreA) "AD" else "40"
        } else if (scoreB < 4) scores[scoreB] else "AD"

        return "$displayA - $displayB"
    }
}
