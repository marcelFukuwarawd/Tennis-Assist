package com.courtvision.analysis

import com.courtvision.scoring.ScoringEvent
import com.courtvision.scoring.Side
import com.courtvision.scoring.Sport
import com.courtvision.scoring.TennisEngine
import com.courtvision.scoring.TennisRules
import com.courtvision.vision.CallResult
import com.courtvision.vision.CourtModel
import com.courtvision.vision.MovementStats
import com.courtvision.vision.PlayerSample
import com.courtvision.vision.ShotType
import kotlin.math.abs

/** A ratio that keeps its sample size so the UI can show "n/a" instead of inventing values. */
data class Ratio(val hits: Int, val total: Int) {
    val value: Double? get() = if (total == 0) null else hits.toDouble() / total
}

data class SideStats(
    val side: Side,
    val pointsWon: Int = 0,
    val pointsLost: Int = 0,
    val aces: Int = 0,
    val doubleFaults: Int = 0,
    val winners: Int = 0,
    val forcedErrors: Int = 0,
    val unforcedErrors: Int = 0,
    val firstServeIn: Ratio = Ratio(0, 0),
    val firstServeWon: Ratio = Ratio(0, 0),
    val secondServeWon: Ratio = Ratio(0, 0),
    val servicePointsWon: Ratio = Ratio(0, 0),
    val returnPointsWon: Ratio = Ratio(0, 0),
    val breakPoints: Ratio = Ratio(0, 0),
    val shots: Int = 0,
    val shotsByType: Map<ShotType, Int> = emptyMap(),
    val accuracyByType: Map<ShotType, Ratio> = emptyMap(),
    val accuracy: Ratio = Ratio(0, 0),
    val maxSpeedKmh: Double? = null,
    val avgSpeedKmh: Double? = null,
    val serveSpeedMaxKmh: Double? = null,
    val serveSpeedAvgKmh: Double? = null,
    /** Fraction of in-bounds shots landing in the deepest third of the opponent half. */
    val deepShots: Ratio = Ratio(0, 0),
    val distanceMeters: Double? = null,
    val maxRunSpeedKmh: Double? = null,
)

data class MatchStats(
    val a: SideStats,
    val b: SideStats,
    val totalPoints: Int,
    val totalShots: Int,
    val rallies: Int,
    val avgRallyShots: Double?,
    val longestRallyShots: Int?,
    val avgRallySeconds: Double?,
    val pendingReview: Int,
)

object StatisticsCalculator {

    fun compute(
        sport: Sport,
        court: CourtModel,
        tennisRules: TennisRules,
        firstServer: Side,
        points: List<PointRecord>,
        shots: List<ShotRecord>,
        rallies: List<RallyRecord>,
        movement: Map<Side, List<PlayerSample>> = emptyMap(),
    ): MatchStats {
        val ordered = points.sortedBy { it.order }
        val sides = mutableMapOf(Side.A to SideStats(Side.A), Side.B to SideStats(Side.B))
        // serving context from the tennis engine (pickleball: serving side handled similarly)
        val contexts = if (sport == Sport.TENNIS) {
            TennisEngine(tennisRules).replay(firstServer, ordered.map { ScoringEvent.Rally(it.winner) }).second
        } else emptyList()

        val fsIn = mutableMapOf(Side.A to 0, Side.B to 0)
        val served = mutableMapOf(Side.A to 0, Side.B to 0)
        val fsKnown = mutableMapOf(Side.A to 0, Side.B to 0)
        val fsWon = mutableMapOf(Side.A to 0, Side.B to 0)
        val ssPlayed = mutableMapOf(Side.A to 0, Side.B to 0)
        val ssWon = mutableMapOf(Side.A to 0, Side.B to 0)
        val srvWon = mutableMapOf(Side.A to 0, Side.B to 0)
        val retWon = mutableMapOf(Side.A to 0, Side.B to 0)
        val bpTotal = mutableMapOf(Side.A to 0, Side.B to 0)
        val bpWon = mutableMapOf(Side.A to 0, Side.B to 0)

        ordered.forEachIndexed { i, p ->
            val w = p.winner; val l = w.other
            sides[w] = sides[w]!!.let { s ->
                s.copy(
                    pointsWon = s.pointsWon + 1,
                    aces = s.aces + if (p.reason == PointReason.ACE) 1 else 0,
                    winners = s.winners + if (p.reason == PointReason.WINNER) 1 else 0,
                )
            }
            sides[l] = sides[l]!!.let { s ->
                s.copy(
                    pointsLost = s.pointsLost + 1,
                    doubleFaults = s.doubleFaults + if (p.reason == PointReason.DOUBLE_FAULT) 1 else 0,
                    forcedErrors = s.forcedErrors + if (p.reason == PointReason.FORCED_ERROR) 1 else 0,
                    unforcedErrors = s.unforcedErrors + if (p.reason == PointReason.UNFORCED_ERROR) 1 else 0,
                )
            }
            val ctx = contexts.getOrNull(i) ?: return@forEachIndexed
            val server = ctx.before.server
            val receiver = server.other
            served[server] = served[server]!! + 1
            if (w == server) srvWon[server] = srvWon[server]!! + 1 else retWon[receiver] = retWon[receiver]!! + 1
            val sn = if (p.reason == PointReason.DOUBLE_FAULT) 2 else p.serveNumber
            if (sn != null) {
                fsKnown[server] = fsKnown[server]!! + 1
                if (sn == 1) {
                    fsIn[server] = fsIn[server]!! + 1
                    if (w == server) fsWon[server] = fsWon[server]!! + 1
                } else {
                    ssPlayed[server] = ssPlayed[server]!! + 1
                    if (w == server) ssWon[server] = ssWon[server]!! + 1
                }
            }
            if (ctx.isBreakPoint) {
                bpTotal[receiver] = bpTotal[receiver]!! + 1
                if (w == receiver) bpWon[receiver] = bpWon[receiver]!! + 1
            }
        }
        for (s in Side.entries) {
            sides[s] = sides[s]!!.copy(
                firstServeIn = Ratio(fsIn[s]!!, fsKnown[s]!!),
                firstServeWon = Ratio(fsWon[s]!!, fsIn[s]!!),
                secondServeWon = Ratio(ssWon[s]!!, ssPlayed[s]!!),
                servicePointsWon = Ratio(srvWon[s]!!, served[s]!!),
                returnPointsWon = Ratio(retWon[s]!!, served[s.other]!!),
                breakPoints = Ratio(bpWon[s]!!, bpTotal[s]!!),
            )
        }
        // shots
        for (s in Side.entries) {
            val mine = shots.filter { it.side == s }
            val speeds = mine.mapNotNull { it.speedKmh }
            val serveSpeeds = mine.filter { it.type == ShotType.SERVE }.mapNotNull { it.speedKmh }
            val called = mine.filter { it.call == CallResult.IN || it.call == CallResult.OUT }
            val inShots = mine.filter { it.call == CallResult.IN && it.bounce != null }
            val deep = inShots.count { sh ->
                val d = abs(sh.bounce!!.y - court.netY)
                d >= court.netY * 2.0 / 3.0
            }
            val byType = mine.groupingBy { it.type }.eachCount()
            val accByType = mine.groupBy { it.type }.mapValues { (_, l) ->
                val c = l.filter { it.call == CallResult.IN || it.call == CallResult.OUT }
                Ratio(c.count { it.call == CallResult.IN }, c.size)
            }
            val mv = movement[s]?.let { MovementStats.compute(it) }
            sides[s] = sides[s]!!.copy(
                shots = mine.size,
                shotsByType = byType,
                accuracyByType = accByType,
                accuracy = Ratio(called.count { it.call == CallResult.IN }, called.size),
                maxSpeedKmh = speeds.maxOrNull(),
                avgSpeedKmh = if (speeds.isEmpty()) null else speeds.average(),
                serveSpeedMaxKmh = serveSpeeds.maxOrNull(),
                serveSpeedAvgKmh = if (serveSpeeds.isEmpty()) null else serveSpeeds.average(),
                deepShots = Ratio(deep, inShots.size),
                distanceMeters = mv?.takeIf { it.samples >= 2 }?.distanceMeters,
                maxRunSpeedKmh = mv?.maxSpeedKmh,
            )
        }
        val rs = rallies.filter { it.shots > 0 }
        return MatchStats(
            a = sides[Side.A]!!, b = sides[Side.B]!!,
            totalPoints = ordered.size, totalShots = shots.size,
            rallies = rs.size,
            avgRallyShots = if (rs.isEmpty()) null else rs.map { it.shots }.average(),
            longestRallyShots = rs.maxOfOrNull { it.shots },
            avgRallySeconds = if (rs.isEmpty()) null else rs.map { (it.endMs - it.startMs) / 1000.0 }.average(),
            pendingReview = ordered.count { it.needsReview },
        )
    }
}
