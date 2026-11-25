package com.courtvision.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.courtvision.ai.data.AppDatabase
import com.courtvision.ai.data.SessionRepository
import com.courtvision.ai.data.SettingsRepository
import com.courtvision.ai.util.VoiceAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container (no DI framework needed for this size). */
class CourtVisionApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db by lazy { AppDatabase.build(this) }
    val settings by lazy { SettingsRepository(this) }
    val repo by lazy { SessionRepository(this, db) }
    val voice by lazy { VoiceAnnouncer(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ANALYSIS, getString(R.string.channel_analysis), NotificationManager.IMPORTANCE_LOW))
        // Recover sessions whose recording was interrupted (crash, battery, process killed).
        appScope.launch { repo.recoverInterruptedSessions() }
    }

    companion object {
        const val CHANNEL_ANALYSIS = "analysis"
    }
}
