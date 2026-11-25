package com.courtvision.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.courtvision.ai.data.AppDatabase
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.SessionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented test (device/emulator): point replacement keeps order and never touches segments. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    @Test fun replacePointsKeepsOrder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val id = db.sessions().insert(SessionEntity(title = "t", mode = SessionMode.TENNIS_SINGLES, createdAt = 0))
        db.points().replaceAll(id, listOf(PointEntity(sessionId = id, order = 5, winnerA = true), PointEntity(sessionId = id, order = 9, winnerA = false)))
        val p = db.points().forSession(id)
        assertEquals(listOf(0, 1), p.map { it.order })
        assertEquals(listOf(true, false), p.map { it.winnerA })
        db.close()
    }
}
