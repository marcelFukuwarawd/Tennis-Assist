package com.courtvision.analysis

import com.courtvision.scoring.Side
import com.courtvision.vision.CallResult
import com.courtvision.vision.CourtModel
import com.courtvision.vision.ShotType
import kotlin.math.abs

enum class InsightKind {
    BACKHAND_LESS_ACCURATE, FOREHAND_LESS_ACCURATE, LOW_FIRST_SERVE, DOUBLE_FAULTS, SECOND_SERVE_CENTRAL,
    SHORT_BALLS, CONSISTENCY_DROP, BREAK_POINTS_MISSED, STRONG_SERVE, STRONG_RETURN, LOW_DEPTH, GOOD_CONSISTENCY,
}

enum class Drill { CROSSCOURT_BACKHAND, CROSSCOURT_FOREHAND, SERVE_TARGETS, SECOND_SERVE_KICK, DEPTH_TARGETS, ENDURANCE_RALLY, PRESSURE_POINTS, CONSISTENCY_20 }

/**
 * Evidence-based insight. [values] contains the real numbers that justify it and
 * [evidenceMs] points to video timestamps that illustrate it.
 */
data class Insight(
    val kind: InsightKind,
    val side: Side,
    val positive: Boolean,
    val values: Map<String, Double>,
    val sampleSize: Int,
    val evidenceMs: List<Long>,
    val drills: List<Drill>,
)

/** Local rule-based coach. It only speaks when there is enough data (minimum sample sizes). */
class Coach(private val court: CourtModel, private val minSample: Int = 8) {

    fun analyse(side: Side, stats: SideStats, shots: List<ShotRecord>): List<Insight> {
        val out = ArrayList<Insight>()
        val mine = shots.filter { it.side == side }

        // forehand vs backhand accuracy
        val fh = stats.accuracyByType[ShotType.FOREHAND]
        val bh = stats.accuracyByType[ShotType.BACKHAND]
        if (fh != null && bh != null && fh.total >= minSample && bh.total >= minSample) {
            val diff = fh.value!! - bh.value!!
            if (abs(diff) >= 0.10) {
                val weaker = if (diff > 0) ShotType.BACKHAND else ShotType.FOREHAND
                out += Insight(
                    if (diff > 0) InsightKind.BACKHAND_LESS_ACCURATE else InsightKind.FOREHAND_LESS_ACCURATE,
                    side, false,
                    mapOf("forehand" to fh.value!!, "backhand" to bh.value!!),
                    fh.total + bh.total,
                    mine.filter { it.type == weaker && it.call == CallResult.OUT }.map { it.timeMs }.take(5),
                    listOf(if (diff > 0) Drill.CROSSCOURT_BACKHAND else Drill.CROSSCOURT_FOREHAND),
                )
            }
        }
        // first serve
        stats.firstServeIn.takeIf { it.total >= minSample }?.value?.let { v ->
            if (v < 0.55) out += Insight(InsightKind.LOW_FIRST_SERVE, side, false, mapOf("first_serve_in" to v), stats.firstServeIn.total, emptyList(), listOf(Drill.SERVE_TARGETS))
        }
        stats.servicePointsWon.takeIf { it.total >= minSample }?.value?.let { v ->
            if (v >= 0.65) out += Insight(InsightKind.STRONG_SERVE, side, true, mapOf("service_points_won" to v), stats.servicePointsWon.total, emptyList(), emptyList())
        }
        stats.returnPointsWon.takeIf { it.total >= minSample }?.value?.let { v ->
            if (v >= 0.45) out += Insight(InsightKind.STRONG_RETURN, side, true, mapOf("return_points_won" to v), stats.returnPointsWon.total, emptyList(), emptyList())
        }
        val served = stats.servicePointsWon.total
        if (served >= minSample && stats.doubleFaults >= 3 && stats.doubleFaults.toDouble() / served >= 0.08) {
            out += Insight(InsightKind.DOUBLE_FAULTS, side, false, mapOf("double_faults" to stats.doubleFaults.toDouble(), "service_points" to served.toDouble()), served, emptyList(), listOf(Drill.SECOND_SERVE_KICK))
        }
        stats.breakPoints.takeIf { it.total >= 4 }?.value?.let { v ->
            if (v < 0.3) out += Insight(InsightKind.BREAK_POINTS_MISSED, side, false, mapOf("break_points_won" to v, "break_points" to stats.breakPoints.total.toDouble()), stats.breakPoints.total, emptyList(), listOf(Drill.PRESSURE_POINTS))
        }
        // serve placement: second serves landing in the central third of the box
        val serves = mine.filter { it.type == ShotType.SERVE && it.bounce != null && it.call == CallResult.IN }
        if (serves.size >= minSample) {
            val centre = court.doublesWidth / 2
            val central = serves.count { abs(it.bounce!!.x - centre) < (court.singlesWidth / 2) / 3 }
            val r = central.toDouble() / serves.size
            if (r >= 0.6) out += Insight(InsightKind.SECOND_SERVE_CENTRAL, side, false, mapOf("central_ratio" to r), serves.size, serves.take(5).map { it.timeMs }, listOf(Drill.SERVE_TARGETS))
        }
        // depth
        stats.deepShots.takeIf { it.total >= minSample * 2 }?.value?.let { v ->
            if (v < 0.25) out += Insight(InsightKind.LOW_DEPTH, side, false, mapOf("deep_ratio" to v), stats.deepShots.total, emptyList(), listOf(Drill.DEPTH_TARGETS))
        }
        // consistency over time: first half vs second half of the session
        val called = mine.filter { it.call == CallResult.IN || it.call == CallResult.OUT }.sortedBy { it.timeMs }
        if (called.size >= minSample * 4) {
            val half = called.size / 2
            val a = called.take(half).count { it.call == CallResult.IN }.toDouble() / half
            val b = called.drop(half).count { it.call == CallResult.IN }.toDouble() / (called.size - half)
            if (a - b >= 0.10) out += Insight(InsightKind.CONSISTENCY_DROP, side, false, mapOf("first_half" to a, "second_half" to b), called.size, called.drop(half).filter { it.call == CallResult.OUT }.take(5).map { it.timeMs }, listOf(Drill.ENDURANCE_RALLY))
            else if (minOf(a, b) >= 0.8) out += Insight(InsightKind.GOOD_CONSISTENCY, side, true, mapOf("first_half" to a, "second_half" to b), called.size, emptyList(), emptyList())
        }
        return out
    }
}

/** Automatic highlight selection; every clip refers to real detected events. */
object Highlights {
    data class Clip(val startMs: Long, val endMs: Long, val label: String, val score: Double)

    fun select(
        rallies: List<RallyRecord>, shots: List<ShotRecord>, points: List<PointRecord>,
        maxClips: Int = 10, preMs: Long = 1500, postMs: Long = 1200,
    ): List<Clip> {
        val clips = ArrayList<Clip>()
        rallies.sortedByDescending { it.shots }.take(3).forEach { clips += Clip(it.startMs - preMs, it.endMs + postMs, "longest_rally", it.shots.toDouble()) }
        shots.filter { it.speedKmh != null }.sortedByDescending { it.speedKmh }.take(3).forEach {
            clips += Clip(it.timeMs - preMs, it.timeMs + postMs, if (it.type == ShotType.SERVE) "fastest_serve" else "fastest_shot", it.speedKmh!!)
        }
        points.filter { (it.reason == PointReason.WINNER || it.reason == PointReason.ACE) && it.startMs != null && it.endMs != null }
            .forEach { clips += Clip(it.startMs!! - preMs, it.endMs!! + postMs, it.reason.name.lowercase(), 1.0) }
        // merge overlapping clips, keep chronological order
        val merged = ArrayList<Clip>()
        for (c in clips.map { it.copy(startMs = it.startMs.coerceAtLeast(0)) }.sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && c.startMs <= last.endMs) merged[merged.size - 1] = last.copy(endMs = maxOf(last.endMs, c.endMs), score = maxOf(last.score, c.score))
            else merged += c
        }
        return merged.take(maxClips)
    }

    /** Removes dead time: keeps only rally windows. Original video is never modified. */
    fun condensed(rallies: List<RallyRecord>, preMs: Long = 1500, postMs: Long = 1500): List<Clip> =
        rallies.map { Clip((it.startMs - preMs).coerceAtLeast(0), it.endMs + postMs, "rally_${it.index + 1}", it.shots.toDouble()) }
            .fold(ArrayList()) { acc, c ->
                val last = acc.lastOrNull()
                if (last != null && c.startMs <= last.endMs) acc[acc.size - 1] = last.copy(endMs = maxOf(last.endMs, c.endMs)) else acc += c
                acc
            }
}
