package com.courtvision.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class, SegmentEntity::class, PointEntity::class, ShotEntity::class,
        BounceEntity::class, RallyEntity::class, BallTrackEntity::class, PlayerSampleEntity::class,
        ClipEntity::class, MarkerEntity::class, TargetZoneEntity::class, PrecisionAttemptEntity::class,
        GoalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun segments(): SegmentDao
    abstract fun points(): PointDao
    abstract fun analysis(): AnalysisDao
    abstract fun edits(): EditDao
    abstract fun training(): TrainingDao

    companion object {
        const val NAME = "courtvision.db"
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
