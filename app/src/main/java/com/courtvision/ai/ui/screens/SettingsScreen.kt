package com.courtvision.ai.ui.screens

import com.courtvision.ai.util.Feedback
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavHostController
import com.courtvision.ai.BuildConfig
import com.courtvision.ai.R
import com.courtvision.ai.data.AppSettings
import com.courtvision.ai.data.BallColor
import com.courtvision.ai.data.ProcessingMode
import com.courtvision.ai.data.ThemeMode
import com.courtvision.ai.export.DataExporter
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.app
import com.courtvision.ai.util.Storage
import com.courtvision.ai.util.formatPct
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.settings.settings.collectAsState(initial = AppSettings())
    fun set(notify: Boolean = true, f: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(f); if (notify) Feedback.show(context, R.string.setting_saved) }
    var backupVideos by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val exporter = remember { DataExporter(context) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            message = context.getString(R.string.working)
            message = runCatching { exporter.backup(uri, backupVideos) }.fold({ context.getString(R.string.backup_done) }, { context.getString(R.string.backup_failed, it.message ?: "") })
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching { exporter.restore(uri) }.getOrDefault(false)
            if (ok) {
                // restart the app so the restored database is opened
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
                exitProcess(0)
            } else message = context.getString(R.string.restore_failed)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            SectionCard(stringResource(R.string.appearance)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.SYSTEM to R.string.theme_system).forEach { (m, l) -> FilterChip(st.theme == m, { set { it.copy(theme = m) } }, { Text(stringResource(l)) }) }
                }
                Text(stringResource(R.string.language))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("pt-BR" to "Português (Brasil)", "en" to "English", "" to stringResource(R.string.theme_system)).forEach { (tag, l) ->
                        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                        FilterChip(current == tag, { Feedback.show(context, R.string.setting_saved); AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)) }, { Text(l) })
                    }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.processing)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ProcessingMode.ECONOMY to R.string.proc_economy, ProcessingMode.BALANCED to R.string.proc_balanced, ProcessingMode.MAX_ACCURACY to R.string.proc_max).forEach { (m, l) -> FilterChip(st.processing == m, { set { it.copy(processing = m) } }, { Text(stringResource(l)) }) }
                }
                Text(stringResource(when (st.processing) { ProcessingMode.ECONOMY -> R.string.proc_economy_info; ProcessingMode.BALANCED -> R.string.proc_balanced_info; ProcessingMode.MAX_ACCURACY -> R.string.proc_max_info }), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.ball_color))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BallColor.OPTIC_YELLOW to R.string.ball_yellow, BallColor.ORANGE to R.string.ball_orange, BallColor.GREEN to R.string.ball_green).forEach { (c, l) -> FilterChip(st.ballColor == c, { set { it.copy(ballColor = c) } }, { Text(stringResource(l)) }) }
                }
                ToggleRow(stringResource(R.string.auto_scoring), st.autoScoring) { v -> set { it.copy(autoScoring = v) } }
                Text(stringResource(R.string.review_threshold, formatPct(st.reviewThreshold)))
                Slider(st.reviewThreshold.toFloat(), { v -> set(false) { it.copy(reviewThreshold = v.toDouble()) } }, valueRange = 0.5f..0.99f, onValueChangeFinished = { Feedback.show(context, R.string.setting_saved) })
            }
        }
        item {
            SectionCard(stringResource(R.string.line_calling)) {
                Text(stringResource(R.string.call_threshold, formatPct(st.callThreshold)))
                Slider(st.callThreshold.toFloat(), { v -> set(false) { it.copy(callThreshold = v.toDouble()) } }, valueRange = 0.80f..0.995f, onValueChangeFinished = { Feedback.show(context, R.string.setting_saved) })
                Text(stringResource(R.string.call_threshold_info), style = MaterialTheme.typography.labelSmall)
                ToggleRow(stringResource(R.string.voice_calls), st.voiceCalls) { v -> set { it.copy(voiceCalls = v) } }
                ToggleRow(stringResource(R.string.voice_score), st.voiceScore) { v -> set { it.copy(voiceScore = v) } }
                ToggleRow(stringResource(R.string.live_analysis), st.liveAnalysis) { v -> set { it.copy(liveAnalysis = v) } }
            }
        }
        item {
            SectionCard(stringResource(R.string.recording)) {
                Text(stringResource(R.string.default_resolution))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("HD" to "720p", "FHD" to "1080p", "UHD" to "4K").forEach { (k, l) -> FilterChip(st.resolution == k, { set { it.copy(resolution = k) } }, { Text(l) }) } }
                Text("FPS")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(30, 60).forEach { f -> FilterChip(st.fps == f, { set { it.copy(fps = f) } }, { Text("$f") }) } }
                Text(stringResource(R.string.segment_length))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(5, 10, 15, 20).forEach { m -> FilterChip(st.segmentMinutes == m, { set { it.copy(segmentMinutes = m) } }, { Text("$m min") }) } }
                Text(stringResource(R.string.countdown))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0, 3, 5, 10).forEach { c -> FilterChip(st.countdownSec == c, { set { it.copy(countdownSec = c) } }, { Text("$c s") }) } }
                ToggleRow(stringResource(R.string.record_audio), st.recordAudio) { v -> set { it.copy(recordAudio = v) } }
                ToggleRow(stringResource(R.string.dim_screen), st.dimScreen) { v -> set { it.copy(dimScreen = v) } }
            }
        }
        item {
            SectionCard(stringResource(R.string.overlays)) {
                ToggleRow(stringResource(R.string.tab_score), st.overlayScore) { v -> set { it.copy(overlayScore = v) } }
                ToggleRow(stringResource(R.string.ov_speed), st.overlaySpeed) { v -> set { it.copy(overlaySpeed = v) } }
                ToggleRow(stringResource(R.string.ov_names), st.overlayNames) { v -> set { it.copy(overlayNames = v) } }
                ToggleRow(stringResource(R.string.ov_shot), st.overlayShotType) { v -> set { it.copy(overlayShotType = v) } }
            }
        }
        item {
            SectionCard(stringResource(R.string.storage_backup)) {
                Text(stringResource(R.string.storage_free, Storage.formatBytes(Storage.freeBytes(context)), Storage.minutesLeft(context, "FHD")))
                Text(stringResource(R.string.storage_location, Storage.moviesRoot(context).absolutePath), style = MaterialTheme.typography.labelSmall)
                ToggleRow(stringResource(R.string.backup_include_videos), backupVideos) { backupVideos = it }
                Button(onClick = { backupLauncher.launch("courtvision_backup.zip") }) { Text(stringResource(R.string.backup)) }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip")) }) { Text(stringResource(R.string.restore)) }
                message?.let { Text(it) }
            }
        }
        item {
            SectionCard(stringResource(R.string.about)) {
                Text("Court Vision AI ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text(stringResource(R.string.about_info), style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { nav.navigate("privacy") }) { Text(stringResource(R.string.privacy_policy)) }
                    OutlinedButton(onClick = { nav.navigate("licenses") }) { Text(stringResource(R.string.licenses)) }
                }
            }
        }
    }
}
