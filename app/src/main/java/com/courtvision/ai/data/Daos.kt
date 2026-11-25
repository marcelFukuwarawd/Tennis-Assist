package com.courtvision.ai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status = 'RECORDING'")
    suspend fun interrupted(): List<SessionEntity>

    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Delete suspend fun delete(s: SessionEntity)

    @Query("UPDATE sessions SET analysisState = :state, analysisProgress = :progress, analysisStage = :stage, analysisError = :error WHERE id = :id")
    suspend fun setAnalysis(id: Long, state: AnalysisState, progress: Float, stage: String, error: String?)

    @Query("UPDATE sessions SET analysisCheckpointMs = :ms WHERE id = :id")
    suspend fun setCheckpoint(id: Long, ms: Long)

    @Query("UPDATE sessions SET calibrationJson = :json WHERE id = :id")
    suspend fun setCalibration(id: Long, json: String?)
}

@Dao
interface SegmentDao {
    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY `index`")
    suspend fun forSession(sessionId: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY `index`")
    fun observe(sessionId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments")
    suspend fun all(): List<SegmentEntity>

    @Insert suspend fun insert(s: SegmentEntity): Long
    @Update suspend fun update(s: SegmentEntity)
}

@Dao
interface PointDao {
    @Query("SELECT * FROM points WHERE sessionId = :sessionId ORDER BY `order`")
    fun observe(sessionId: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE sessionId = :sessionId ORDER BY `order`")
    suspend fun forSession(sessionId: Long): List<PointEntity>

    @Query("SELECT * FROM points")
    suspend fun all(): List<PointEntity>

    @Query("DELETE FROM points WHERE sessionId = :sessionId")
    suspend fun clear(sessionId: Long)

    @Insert suspend fun insertAll(p: List<PointEntity>)

    /** Atomically replaces the point list (used by undo/redo and corrections). */
    @Transaction
    suspend fun replaceAll(sessionId: Long, points: List<PointEntity>) {
        clear(sessionId)
        insertAll(points.mapIndexed { i, p -> p.copy(id = 0, sessionId = sessionId, order = i) })
    }
}

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM shots WHERE sessionId = :id ORDER BY timeMs") fun observeShots(id: Long): Flow<List<ShotEntity>>
    @Query("SELECT * FROM shots WHERE sessionId = :id ORDER BY timeMs") suspend fun shots(id: Long): List<ShotEntity>
    @Query("SELECT * FROM shots") suspend fun allShots(): List<ShotEntity>
    @Query("SELECT * FROM bounces WHERE sessionId = :id ORDER BY timeMs") fun observeBounces(id: Long): Flow<List<BounceEntity>>
    @Query("SELECT * FROM bounces WHERE sessionId = :id ORDER BY timeMs") suspend fun bounces(id: Long): List<BounceEntity>
    @Query("SELECT * FROM rallies WHERE sessionId = :id ORDER BY `index`") fun observeRallies(id: Long): Flow<List<RallyEntity>>
    @Query("SELECT * FROM rallies WHERE sessionId = :id ORDER BY `index`") suspend fun rallies(id: Long): List<RallyEntity>
    @Query("SELECT * FROM rallies") suspend fun allRallies(): List<RallyEntity>
    @Query("SELECT * FROM ball_track WHERE sessionId = :id ORDER BY timeMs") suspend fun track(id: Long): List<BallTrackEntity>
    @Query("SELECT * FROM ball_track WHERE sessionId = :id AND timeMs BETWEEN :from AND :to ORDER BY timeMs") suspend fun trackRange(id: Long, from: Long, to: Long): List<BallTrackEntity>
    @Query("SELECT * FROM player_samples WHERE sessionId = :id ORDER BY timeMs") suspend fun players(id: Long): List<PlayerSampleEntity>
    @Query("SELECT * FROM player_samples WHERE sessionId = :id AND timeMs BETWEEN :from AND :to") suspend fun playersRange(id: Long, from: Long, to: Long): List<PlayerSampleEntity>

    @Insert suspend fun insertShots(s: List<ShotEntity>)
    @Update suspend fun updateShot(s: ShotEntity)
    @Insert suspend fun insertBounces(b: List<BounceEntity>)
    @Insert suspend fun insertRallies(r: List<RallyEntity>)
    @Insert suspend fun insertTrack(t: List<BallTrackEntity>)
    @Insert suspend fun insertPlayers(p: List<PlayerSampleEntity>)

    @Query("UPDATE player_samples SET playerId = :into WHERE sessionId = :id AND playerId = :from")
    suspend fun relabelPlayer(id: Long, from: Int, into: Int)

    @Query("DELETE FROM ball_track WHERE sessionId = :id AND timeMs >= :fromMs") suspend fun deleteTrackFrom(id: Long, fromMs: Long)
    @Query("DELETE FROM player_samples WHERE sessionId = :id AND timeMs >= :fromMs") suspend fun deletePlayersFrom(id: Long, fromMs: Long)
    @Query("DELETE FROM shots WHERE sessionId = :id AND edited = 0") suspend fun clearAutoShots(id: Long)
    @Query("DELETE FROM bounces WHERE sessionId = :id") suspend fun clearBounces(id: Long)
    @Query("DELETE FROM rallies WHERE sessionId = :id") suspend fun clearRallies(id: Long)
}

@Dao
interface EditDao {
    @Query("SELECT * FROM clips WHERE sessionId = :id ORDER BY `order`") fun observeClips(id: Long): Flow<List<ClipEntity>>
    @Query("SELECT * FROM clips WHERE sessionId = :id ORDER BY `order`") suspend fun clips(id: Long): List<ClipEntity>
    @Query("DELETE FROM clips WHERE sessionId = :id") suspend fun clearClips(id: Long)
    @Insert suspend fun insertClips(c: List<ClipEntity>)

    @Transaction
    suspend fun replaceClips(id: Long, clips: List<ClipEntity>) {
        clearClips(id)
        insertClips(clips.mapIndexed { i, c -> c.copy(id = 0, sessionId = id, order = i) })
    }

    @Query("SELECT * FROM markers WHERE sessionId = :id ORDER BY timeMs") fun observeMarkers(id: Long): Flow<List<MarkerEntity>>
    @Insert suspend fun insertMarker(m: MarkerEntity): Long
    @Delete suspend fun deleteMarker(m: MarkerEntity)
}

@Dao
interface TrainingDao {
    @Query("SELECT * FROM target_zones ORDER BY id") fun observeZones(): Flow<List<TargetZoneEntity>>
    @Query("SELECT * FROM target_zones ORDER BY id") suspend fun zones(): List<TargetZoneEntity>
    @Insert suspend fun insertZone(z: TargetZoneEntity): Long
    @Delete suspend fun deleteZone(z: TargetZoneEntity)
    @Insert suspend fun insertAttempt(a: PrecisionAttemptEntity): Long
    @Query("SELECT * FROM precision_attempts WHERE sessionId = :id ORDER BY timeMs") fun observeAttempts(id: Long): Flow<List<PrecisionAttemptEntity>>
    @Query("SELECT * FROM precision_attempts ORDER BY timeMs") fun observeAllAttempts(): Flow<List<PrecisionAttemptEntity>>
    @Query("SELECT * FROM precision_attempts") suspend fun allAttempts(): List<PrecisionAttemptEntity>

    @Query("SELECT * FROM goals WHERE active = 1 ORDER BY createdAt DESC") fun observeGoals(): Flow<List<GoalEntity>>
    @Insert suspend fun insertGoal(g: GoalEntity): Long
    @Update suspend fun updateGoal(g: GoalEntity)
}
