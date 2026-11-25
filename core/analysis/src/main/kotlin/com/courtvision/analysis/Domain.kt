package com.courtvision.analysis

import com.courtvision.scoring.Side
import com.courtvision.vision.CallResult
import com.courtvision.vision.ShotType
import com.courtvision.vision.Vec2

enum class PointReason { ACE, DOUBLE_FAULT, WINNER, FORCED_ERROR, UNFORCED_ERROR, OUT, NET, DOUBLE_BOUNCE, UNKNOWN }

enum class Source { AUTO, MANUAL }

/** One played point. Ordering is given by [order]; the score is always recomputed from the list. */
data class PointRecord(
    val id: Long = 0,
    val order: Int,
    val winner: Side,
    val reason: PointReason = PointReason.UNKNOWN,
    /** 1 = first serve in play, 2 = second serve, null = unknown. */
    val serveNumber: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** Automatic inference confidence (heuristic 0..1); null for manual points. */
    val confidence: Double? = null,
    val needsReview: Boolean = false,
    val source: Source = Source.MANUAL,
)

data class ShotRecord(
    val id: Long = 0,
    val timeMs: Long,
    val side: Side?,
    val playerId: Int?,
    val type: ShotType,
    val speedKmh: Double?,
    /** Court position of the hitter (metres). */
    val hitterCourt: Vec2?,
    /** Court position where this shot bounced. */
    val bounce: Vec2?,
    val call: CallResult?,
    val rallyIndex: Int,
    val indexInRally: Int,
    val manuallyEdited: Boolean = false,
)

data class BounceRecord(
    val timeMs: Long,
    val court: Vec2,
    val sigma: Vec2,
    val call: CallResult,
    val probabilityIn: Double,
    val nearHalf: Boolean,
)

data class RallyRecord(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val shots: Int,
)

data class PlayerPosition(val side: Side?, val playerId: Int, val timeMs: Long, val court: Vec2)
