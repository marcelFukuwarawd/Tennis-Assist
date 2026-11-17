package com.tennis.referee

class ScoreEngine(var playerA: String, var playerB: String) {
    private var scoreA = 0
    private var scoreB = 0
    private val scores = arrayOf("0", "15", "30", "40")

    fun pointTo(isPlayerA: Boolean): String {
        if (isPlayerA) scoreA++ else scoreB++

        // Lógica simplificada de Game
        if (scoreA >= 4 && scoreA - scoreB >= 2) {
            scoreA = 0
            scoreB = 0
            return "Game $playerA!"
        } else if (scoreB >= 4 && scoreB - scoreA >= 2) {
            scoreA = 0
            scoreB = 0
            return "Game $playerB!"
        }

        val displayA = if (scoreA < 4) scores[scoreA] else "AD"
        val displayB = if (scoreB < 4) scores[scoreB] else "AD"
        return "$playerA $displayA - $displayB $playerB"
    }
}
