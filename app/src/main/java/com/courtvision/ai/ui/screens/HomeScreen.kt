package com.courtvision.ai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.AnalysisState
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.SessionMode
import com.courtvision.ai.data.ShotEntity
import com.courtvision.ai.processing.AnalysisScheduler
import com.courtvision.ai.ui.BigStat
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.Routes
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.app
import com.courtvision.ai.util.Storage
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatNum
import com.courtvision.ai.util.formatPct
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(nav: NavHostController) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessions by app.db.sessions().observeAll().collectAsState(initial = emptyList())
    var shots by remember { mutableStateOf<List<ShotEntity>>(emptyList()) }
    LaunchedEffect(sessions) { shots = app.db.analysis().allShots() }
    var importError by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val title = context.getString(R.string.imported_video) + " " + DateFormat.getDateInstance().format(Date())
            val id = app.repo.importVideo(uri, title, SessionMode.TENNIS_SINGLES)
            if (id == null) importError = true else nav.navigate(Routes.session(id))
        }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate(Routes.record("match")) }, Modifier.weight(1f).height(64.dp)) {
                    Icon(Icons.Filled.SportsTennis, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.new_match))
                }
                FilledTonalButton(onClick = { nav.navigate(Routes.record("training")) }, Modifier.weight(1f).height(64.dp)) {
                    Icon(Icons.Filled.FitnessCenter, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.new_training))
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { importer.launch(arrayOf("video/*")) }, Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.FileUpload, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.import_video))
            }
            if (importError) Text(stringResource(R.string.import_error), color = MaterialTheme.colorScheme.error)
        }
        val jobs = sessions.filter { it.analysisState in setOf(AnalysisState.QUEUED, AnalysisState.RUNNING, AnalysisState.PAUSED) }
        if (jobs.isNotEmpty()) item {
            SectionCard(stringResource(R.string.processing_jobs)) {
                jobs.forEach { s ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.session(s.id)) }.padding(vertical = 6.dp)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(stageLabel(s), style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { s.analysisProgress }, Modifier.fillMaxWidth().padding(top = 4.dp))
                        if (s.analysisState == AnalysisState.PAUSED) FilledTonalButton(onClick = { scope.launch { AnalysisScheduler.enqueue(context, s.id) } }) { Text(stringResource(R.string.resume)) }
                    }
                }
            }
        }
        item { PerformanceSummary(sessions, shots) }
        item {
            SectionCard(stringResource(R.string.best_plays)) {
                val best = shots.filter { it.speedKmh != null && it.sideA != false }.sortedByDescending { it.speedKmh }.take(3)
                if (best.isEmpty()) EmptyHint(stringResource(R.string.no_data_yet))
                best.forEach { sh ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.session(sh.sessionId, sh.timeMs)) }.padding(vertical = 6.dp)) {
                        Text("${formatNum(sh.speedKmh)} km/h", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(shotLabel(sh.type), Modifier.weight(1f))
                        Text(formatDuration(sh.timeMs))
                    }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.recent_sessions)) {
                if (sessions.isEmpty()) EmptyHint(stringResource(R.string.no_sessions))
                sessions.take(5).forEach { s -> SessionRow(s) { nav.navigate(Routes.session(s.id)) } }
            }
        }
        item {
            val free = Storage.freeBytes(context)
            SectionCard(stringResource(R.string.storage)) {
                Text(stringResource(R.string.storage_free, Storage.formatBytes(free), Storage.minutesLeft(context, "FHD")))
                val total = Storage.totalBytes(context).coerceAtLeast(1)
                LinearProgressIndicator(progress = { 1f - free.toFloat() / total }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PerformanceSummary(sessions: List<SessionEntity>, shots: List<ShotEntity>) {
    SectionCard(stringResource(R.string.performance_summary)) {
        val mine = shots.filter { it.sideA != false && (it.call == "IN" || it.call == "OUT") }
        val inPct = if (mine.isEmpty()) null else mine.count { it.call == "IN" }.toDouble() / mine.size
        val speeds = shots.filter { it.sideA != false }.mapNotNull { it.speedKmh }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigStat(sessions.size.toString(), stringResource(R.string.sessions))
            BigStat(shots.count { it.sideA != false }.toString(), stringResource(R.string.shots))
            BigStat(formatPct(inPct), stringResource(R.string.in_pct))
            BigStat(formatNum(speeds.maxOrNull()), stringResource(R.string.max_kmh))
        }
        // evolution: in% per session (oldest -> newest)
        val perSession = shots.filter { it.sideA != false && (it.call == "IN" || it.call == "OUT") }
            .groupBy { it.sessionId }.filterValues { it.size >= 5 }
            .toSortedMap().values.map { l -> l.count { it.call == "IN" }.toFloat() / l.size }.takeLast(12)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.evolution), style = MaterialTheme.typography.labelLarge)
        if (perSession.size < 2) EmptyHint(stringResource(R.string.evolution_hint)) else {
            val c = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(80.dp).padding(top = 4.dp)) {
                val step = size.width / (perSession.size - 1)
                for (i in 1 until perSession.size) {
                    drawLine(c, Offset((i - 1) * step, size.height * (1 - perSession[i - 1])), Offset(i * step, size.height * (1 - perSession[i])), 3f)
                }
                perSession.forEachIndexed { i, v -> drawCircle(c, 6f, Offset(i * step, size.height * (1 - v))) }
            }
        }
    }
}

@Composable
fun SessionRow(s: SessionEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (s.favorite) "★ " else "") + s.title, fontWeight = FontWeight.SemiBold)
            Text(
                modeLabel(s.mode) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.createdAt)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatDuration(s.durationMs), style = MaterialTheme.typography.labelLarge)
    }
}
