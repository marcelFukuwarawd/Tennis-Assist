package com.courtvision.ai.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class SessionMode {
    TENNIS_SINGLES, TENNIS_DOUBLES, PICKLEBALL_SINGLES, PICKLEBALL_DOUBLES,
    TRAINING_SOLO, TRAINING_PARTNER, TRAINING_SERVE, TRAINING_MACHINE, TRAINING_PRECISION, TECHNIQUE;

    val isMatch get() = this in setOf(TENNIS_SINGLES, TENNIS_DOUBLES, PICKLEBALL_SINGLES, PICKLEBALL_DOUBLES)
    val isPickleball get() = this == PICKLEBALL_SINGLES || this == PICKLEBALL_DOUBLES
    val isDoubles get() = this == TENNIS_DOUBLES || this == PICKLEBALL_DOUBLES
}

enum class SessionStatus { RECORDING, READY, RECOVERED, IMPORTED }

enum class AnalysisState { NONE, QUEUED, RUNNING, PAUSED, DONE, FAILED }

@Serializable
data class RulesConfig(
    val bestOf: Int = 3,
    val gamesPerSet: Int = 6,
    val noAd: Boolean = false,
    val tiebreak: Boolean = true,
    val tiebreakPoints: Int = 7,
    val finalSetMatchTiebreak: Boolean = false,
    val matchTiebreakPoints: Int = 10,
    val pickleballRally: Boolean = false,
    val pickleballPoints: Int = 11,
    val pickleballWinBy: Int = 2,
    val pickleballGames: Int = 1,
)

/** Normalised (0..1) image coordinates of the court corners: far-left, far-right, near-right, near-left. */
@Serializable
data class CalibrationData(
    val xs: List<Double>,
    val ys: List<Double>,
    val auto: Boolean,
    val lineSupport: Double? = null,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mode: SessionMode,
    val createdAt: Long,
    val durationMs: Long = 0,
    val playerA: String = "",
    val playerB: String = "",
    val playerA2: String = "",
    val playerB2: String = "",
    val rulesJson: String = "",
    val firstServerA: Boolean = true,
    val favorite: Boolean = false,
    val notes: String = "",
    val status: SessionStatus = SessionStatus.READY,
    val imported: Boolean = false,
    val calibrationJson: String? = null,
    val analysisState: AnalysisState = AnalysisState.NONE,
    val analysisProgress: Float = 0f,
    val analysisStage: String = "",
    val analysisCheckpointMs: Long = 0,
    val analysisError: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val surface: String = "",
    val rightHandedA: Boolean = true,
    val rightHandedB: Boolean = true,
)

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val index: Int,
    val uri: String,
    val durationMs: Long,
    val startOffsetMs: Long,
    val valid: Boolean = true,
)

@Entity(
    tableName = "points",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val order: Int,
    val winnerA: Boolean,
    val reason: String = "UNKNOWN",
    val serveNumber: Int? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val confidence: Double? = null,
    val needsReview: Boolean = false,
    val auto: Boolean = false,
    /** Manual server correction applied before this point (null = none). */
    val serverOverrideA: Boolean? = null,
    val serverOverridePlayer: Int = 0,
)

@Entity(tableName = "shots", indices = [Index("sessionId")])
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val sideA: Boolean?,
    val playerId: Int?,
    val type: String,
    val grip: String? = null,
    val speedKmh: Double? = null,
    val hitterX: Double? = null,
    val hitterY: Double? = null,
    val bounceX: Double? = null,
    val bounceY: Double? = null,
    val call: String? = null,
    val probabilityIn: Double? = null,
    val rallyIndex: Int,
    val indexInRally: Int,
    val support: Double = 0.0,
    val edited: Boolean = false,
)

@Entity(tableName = "bounces", indices = [Index("sessionId")])
data class BounceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val x: Double,
    val y: Double,
    val sigmaX: Double,
    val sigmaY: Double,
    val call: String,
    val probabilityIn: Double,
    val imageX: Double,
    val imageY: Double,
    val calibrated: Boolean = false,
)

@Entity(tableName = "rallies", indices = [Index("sessionId")])
data class RallyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val shots: Int,
)

@Entity(tableName = "ball_track", indices = [Index("sessionId", "timeMs")])
data class BallTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val frame: Long,
    /** Normalised image coordinates 0..1 */
    val x: Double,
    val y: Double,
    val observed: Boolean,
    val confidence: Double,
    val radius: Double,
)

@Entity(tableName = "player_samples", indices = [Index("sessionId", "timeMs")])
data class PlayerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val playerId: Int,
    val timeMs: Long,
    val courtX: Double?,
    val courtY: Double?,
    /** Normalised box */
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

@Entity(tableName = "clips", indices = [Index("sessionId")])
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val order: Int,
    val startMs: Long,
    val endMs: Long,
    val speed: Float = 1f,
    val favorite: Boolean = false,
    val label: String = "",
    val enabled: Boolean = true,
)

@Entity(tableName = "markers", indices = [Index("sessionId")])
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val label: String,
)

@Entity(tableName = "target_zones")
data class TargetZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pickleball: Boolean,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
)

@Entity(tableName = "precision_attempts", indices = [Index("sessionId")])
data class PrecisionAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timeMs: Long,
    val zoneId: Long?,
    val x: Double,
    val y: Double,
    /** HIT, MISS, SHORT, LONG, OUT, INCONCLUSIVE */
    val result: String,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metric: String,
    val target: Double,
    val createdAt: Long,
    val active: Boolean = true,
)
