package com.courtvision.ai.util

import android.content.Context
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object Storage {
    fun moviesRoot(context: Context): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")).apply { mkdirs() }

    fun exportsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }

    fun freeBytes(context: Context): Long = runCatching { StatFs(moviesRoot(context).path).availableBytes }.getOrDefault(0L)

    fun totalBytes(context: Context): Long = runCatching { StatFs(moviesRoot(context).path).totalBytes }.getOrDefault(0L)

    /** Approximate bitrate (bytes/s) of the CameraX recorder for each quality. */
    fun bytesPerSecond(quality: String): Long = when (quality) {
        "UHD" -> 6_000_000L
        "FHD" -> 2_200_000L
        else -> 1_200_000L
    }

    fun minutesLeft(context: Context, quality: String): Long = freeBytes(context) / bytesPerSecond(quality) / 60

    fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1L shl 20).toDouble())
        else -> "${b / 1024} KB"
    }
}

object Thermal {
    /** PowerManager thermal status (THERMAL_STATUS_NONE..SHUTDOWN). */
    fun status(context: Context): Int = context.getSystemService(PowerManager::class.java).currentThermalStatus

    fun isHot(context: Context) = status(context) >= PowerManager.THERMAL_STATUS_SEVERE
    fun isCritical(context: Context) = status(context) >= PowerManager.THERMAL_STATUS_CRITICAL
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun formatPct(v: Double?): String = v?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—"
fun formatNum(v: Double?, digits: Int = 0): String = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "—"

/** Offline text-to-speech for line calls and score (uses the device's local TTS engine). */
class VoiceAnnouncer(context: Context) {
    private val ready = AtomicBoolean(false)
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.set(status == TextToSpeech.SUCCESS) }

    fun say(text: String, locale: Locale = Locale.getDefault()) {
        if (!ready.get()) return
        runCatching { tts.language = locale }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }
}
