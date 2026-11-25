package com.courtvision.ai.data

import com.courtvision.analysis.PointReason
import com.courtvision.analysis.PointRecord
import com.courtvision.analysis.RallyRecord
import com.courtvision.analysis.ShotRecord
import com.courtvision.analysis.Source
import com.courtvision.scoring.PickleballRules
import com.courtvision.scoring.PickleballScoring
import com.courtvision.scoring.ScoringEvent
import com.courtvision.scoring.ScoringSession
import com.courtvision.scoring.Side
import com.courtvision.scoring.Sport
import com.courtvision.scoring.TennisRules
import com.courtvision.vision.CallResult
import com.courtvision.vision.CourtCalibration
import com.courtvision.vision.CourtModel
import com.courtvision.vision.ShotType
import com.courtvision.vision.Vec2
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun SessionEntity.rules(): RulesConfig =
    if (rulesJson.isBlank()) RulesConfig() else runCatching { AppJson.decodeFromString(RulesConfig.serializer(), rulesJson) }.getOrDefault(RulesConfig())

fun SessionEntity.sport(): Sport = if (mode.isPickleball) Sport.PICKLEBALL else Sport.TENNIS

fun SessionEntity.court(): CourtModel = if (mode.isPickleball) CourtModel.PICKLEBALL else CourtModel.TENNIS

fun SessionEntity.tennisRules(): TennisRules = rules().let {
    TennisRules(
        bestOf = it.bestOf, gamesPerSet = it.gamesPerSet, noAd = it.noAd, tiebreakEnabled = it.tiebreak,
        tiebreakPoints = it.tiebreakPoints, finalSetMatchTiebreak = it.finalSetMatchTiebreak,
        matchTiebreakPoints = it.matchTiebreakPoints, doubles = mode.isDoubles,
    )
}

fun SessionEntity.pickleballRules(): PickleballRules = rules().let {
    PickleballRules(
        scoring = if (it.pickleballRally) PickleballScoring.RALLY else PickleballScoring.SIDE_OUT,
        pointsToWin = it.pickleballPoints, winBy = it.pickleballWinBy, bestOf = it.pickleballGames,
        doubles = mode.isDoubles,
    )
}

fun SessionEntity.scoringSession() = ScoringSession(
    sport = sport(), tennisRules = tennisRules(), pickleballRules = pickleballRules(),
    firstServer = if (firstServerA) Side.A else Side.B,
)

fun SessionEntity.calibration(): CalibrationData? =
    calibrationJson?.let { runCatching { AppJson.decodeFromString(CalibrationData.serializer(), it) }.getOrNull() }

/** Builds a calibration for an analysis image of size w x h from normalised corners. */
fun CalibrationData.toCalibration(court: CourtModel, w: Int, h: Int): CourtCalibration =
    CourtCalibration.fromCorners(court, xs.indices.map { Vec2(xs[it] * w, ys[it] * h) }, w, h)

fun SessionEntity.nameA(): String = listOf(playerA, playerA2).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "A" }
fun SessionEntity.nameB(): String = listOf(playerB, playerB2).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "B" }

fun List<PointEntity>.toScoringEvents(): List<ScoringEvent> = flatMap { p ->
    val out = ArrayList<ScoringEvent>(2)
    p.serverOverrideA?.let { out += ScoringEvent.SetServer(if (it) Side.A else Side.B, p.serverOverridePlayer) }
    out += ScoringEvent.Rally(if (p.winnerA) Side.A else Side.B)
    out
}

fun PointEntity.toRecord() = PointRecord(
    id = id, order = order, winner = if (winnerA) Side.A else Side.B,
    reason = runCatching { PointReason.valueOf(reason) }.getOrDefault(PointReason.UNKNOWN),
    serveNumber = serveNumber, startMs = startMs, endMs = endMs, confidence = confidence,
    needsReview = needsReview, source = if (auto) Source.AUTO else Source.MANUAL,
)

fun ShotEntity.toRecord() = ShotRecord(
    id = id, timeMs = timeMs, side = sideA?.let { if (it) Side.A else Side.B }, playerId = playerId,
    type = runCatching { ShotType.valueOf(type) }.getOrDefault(ShotType.UNKNOWN), speedKmh = speedKmh,
    hitterCourt = if (hitterX != null && hitterY != null) Vec2(hitterX, hitterY) else null,
    bounce = if (bounceX != null && bounceY != null) Vec2(bounceX, bounceY) else null,
    call = call?.let { runCatching { CallResult.valueOf(it) }.getOrNull() },
    rallyIndex = rallyIndex, indexInRally = indexInRally, manuallyEdited = edited,
)

fun RallyEntity.toRecord() = RallyRecord(index, startMs, endMs, shots)
