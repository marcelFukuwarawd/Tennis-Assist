package com.courtvision.ai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class ProcessingMode { ECONOMY, BALANCED, MAX_ACCURACY }
enum class BallColor { OPTIC_YELLOW, ORANGE, GREEN }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val processing: ProcessingMode = ProcessingMode.BALANCED,
    val callThreshold: Double = 0.95,
    val voiceCalls: Boolean = true,
    val voiceScore: Boolean = true,
    val segmentMinutes: Int = 10,
    val resolution: String = "FHD",
    val fps: Int = 30,
    val recordAudio: Boolean = false,
    val countdownSec: Int = 3,
    val dimScreen: Boolean = true,
    val autoScoring: Boolean = true,
    val ballColor: BallColor = BallColor.OPTIC_YELLOW,
    val reviewThreshold: Double = 0.8,
    val overlayScore: Boolean = true,
    val overlaySpeed: Boolean = true,
    val overlayNames: Boolean = true,
    val overlayShotType: Boolean = false,
    val liveAnalysis: Boolean = false,
)

private val Context.store by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val processing = stringPreferencesKey("processing")
        val threshold = doublePreferencesKey("call_threshold")
        val voiceCalls = booleanPreferencesKey("voice_calls")
        val voiceScore = booleanPreferencesKey("voice_score")
        val segment = intPreferencesKey("segment_minutes")
        val resolution = stringPreferencesKey("resolution")
        val fps = intPreferencesKey("fps")
        val audio = booleanPreferencesKey("audio")
        val countdown = intPreferencesKey("countdown")
        val dim = booleanPreferencesKey("dim")
        val autoScoring = booleanPreferencesKey("auto_scoring")
        val ballColor = stringPreferencesKey("ball_color")
        val review = doublePreferencesKey("review_threshold")
        val ovScore = booleanPreferencesKey("ov_score")
        val ovSpeed = booleanPreferencesKey("ov_speed")
        val ovNames = booleanPreferencesKey("ov_names")
        val ovShot = booleanPreferencesKey("ov_shot")
        val live = booleanPreferencesKey("live_analysis")
    }

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            processing = p[K.processing]?.let { runCatching { ProcessingMode.valueOf(it) }.getOrNull() } ?: d.processing,
            callThreshold = p[K.threshold] ?: d.callThreshold,
            voiceCalls = p[K.voiceCalls] ?: d.voiceCalls,
            voiceScore = p[K.voiceScore] ?: d.voiceScore,
            segmentMinutes = p[K.segment] ?: d.segmentMinutes,
            resolution = p[K.resolution] ?: d.resolution,
            fps = p[K.fps] ?: d.fps,
            recordAudio = p[K.audio] ?: d.recordAudio,
            countdownSec = p[K.countdown] ?: d.countdownSec,
            dimScreen = p[K.dim] ?: d.dimScreen,
            autoScoring = p[K.autoScoring] ?: d.autoScoring,
            ballColor = p[K.ballColor]?.let { runCatching { BallColor.valueOf(it) }.getOrNull() } ?: d.ballColor,
            reviewThreshold = p[K.review] ?: d.reviewThreshold,
            overlayScore = p[K.ovScore] ?: d.overlayScore,
            overlaySpeed = p[K.ovSpeed] ?: d.overlaySpeed,
            overlayNames = p[K.ovNames] ?: d.overlayNames,
            overlayShotType = p[K.ovShot] ?: d.overlayShotType,
            liveAnalysis = p[K.live] ?: d.liveAnalysis,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(current())
        context.store.edit { p ->
            p[K.theme] = n.theme.name
            p[K.processing] = n.processing.name
            p[K.threshold] = n.callThreshold
            p[K.voiceCalls] = n.voiceCalls
            p[K.voiceScore] = n.voiceScore
            p[K.segment] = n.segmentMinutes
            p[K.resolution] = n.resolution
            p[K.fps] = n.fps
            p[K.audio] = n.recordAudio
            p[K.countdown] = n.countdownSec
            p[K.dim] = n.dimScreen
            p[K.autoScoring] = n.autoScoring
            p[K.ballColor] = n.ballColor.name
            p[K.review] = n.reviewThreshold
            p[K.ovScore] = n.overlayScore
            p[K.ovSpeed] = n.overlaySpeed
            p[K.ovNames] = n.overlayNames
            p[K.ovShot] = n.overlayShotType
            p[K.live] = n.liveAnalysis
        }
    }
}
