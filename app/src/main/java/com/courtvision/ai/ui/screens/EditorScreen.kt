package com.courtvision.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.AppSettings
import com.courtvision.ai.data.ClipEntity
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.data.scoringSession
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.export.AspectFormat
import com.courtvision.ai.export.DataExporter
import com.courtvision.ai.export.RenderClip
import com.courtvision.ai.export.VideoExporter
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.VideoSurface
import com.courtvision.ai.ui.app
import com.courtvision.ai.ui.rememberSessionPlayer
import com.courtvision.ai.ui.seekSession
import com.courtvision.ai.ui.sessionPosition
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatNum
import com.courtvision.analysis.Highlights
import com.courtvision.scoring.History
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Preset { HIGHLIGHTS, CONDENSED, SERVES, FOREHANDS, BACKHANDS, WINNERS, LONGEST, FASTEST }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(nav: NavHostController, id: Long) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.db.sessions().observe(id).collectAsState(initial = null)
    val segments by app.db.segments().observe(id).collectAsState(initial = emptyList())
    val stored by app.db.edits().observeClips(id).collectAsState(initial = emptyList())
    val settings by app.settings.settings.collectAsState(initial = AppSettings())
    val history = remember { History<List<ClipEntity>>(emptyList()) }
    var clips by remember { mutableStateOf<List<ClipEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(AspectFormat.LANDSCAPE) }
    var exporting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ovScore by remember { mutableStateOf(true) }
    var ovNames by remember { mutableStateOf(true) }
    var ovSpeed by remember { mutableStateOf(true) }
    var ovShot by remember { mutableStateOf(false) }
    var ovLabel by remember { mutableStateOf(false) }
    var ovRally by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { ovScore = settings.overlayScore; ovNames = settings.overlayNames; ovSpeed = settings.overlaySpeed; ovShot = settings.overlayShotType }
    LaunchedEffect(stored) { if (!loaded) { clips = stored; history.push(stored); loaded = true } }
    val player = rememberSessionPlayer(segments)
    var previewEnd by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(previewEnd) {
        val end = previewEnd ?: return@LaunchedEffect
        while (player.sessionPosition(segments) < end) delay(50)
        player.pause(); previewEnd = null
    }

    fun commit(next: List<ClipEntity>) {
        clips = next; history.push(next)
        scope.launch { app.db.edits().replaceClips(id, next) }
    }

    fun generate(p: Preset) = scope.launch {
        val rallies = app.db.analysis().rallies(id).map { it.toRecord() }
        val shots = app.db.analysis().shots(id)
        val points = app.db.points().forSession(id).map { it.toRecord() }
        fun around(t: Long, label: String) = ClipEntity(sessionId = id, order = 0, startMs = (t - 2000).coerceAtLeast(0), endMs = t + 1500, label = label)
        val out: List<ClipEntity> = when (p) {
            Preset.HIGHLIGHTS -> Highlights.select(rallies, shots.map { it.toRecord() }, points).map { ClipEntity(sessionId = id, order = 0, startMs = it.startMs, endMs = it.endMs, label = it.label) }
            Preset.CONDENSED -> Highlights.condensed(rallies).map { ClipEntity(sessionId = id, order = 0, startMs = it.startMs, endMs = it.endMs, label = it.label) }
            Preset.SERVES -> shots.filter { it.type == "SERVE" && it.speedKmh != null }.sortedByDescending { it.speedKmh }.take(8).sortedBy { it.timeMs }.map { around(it.timeMs, "serve ${formatNum(it.speedKmh)} km/h") }
            Preset.FOREHANDS -> shots.filter { it.type == "FOREHAND" && it.call == "IN" }.sortedByDescending { it.speedKmh ?: 0.0 }.take(8).sortedBy { it.timeMs }.map { around(it.timeMs, "forehand") }
            Preset.BACKHANDS -> shots.filter { it.type == "BACKHAND" && it.call == "IN" }.sortedByDescending { it.speedKmh ?: 0.0 }.take(8).sortedBy { it.timeMs }.map { around(it.timeMs, "backhand") }
            Preset.WINNERS -> points.filter { (it.reason.name == "WINNER" || it.reason.name == "ACE") && it.startMs != null }.map { ClipEntity(sessionId = id, order = 0, startMs = it.startMs!!, endMs = (it.endMs ?: it.startMs!!) + 1500, label = it.reason.name.lowercase()) }
            Preset.LONGEST -> rallies.sortedByDescending { it.shots }.take(5).sortedBy { it.startMs }.map { ClipEntity(sessionId = id, order = 0, startMs = (it.startMs - 1500).coerceAtLeast(0), endMs = it.endMs + 1500, label = "${it.shots} shots") }
            Preset.FASTEST -> shots.filter { it.speedKmh != null }.sortedByDescending { it.speedKmh }.take(8).sortedBy { it.timeMs }.map { around(it.timeMs, "${formatNum(it.speedKmh)} km/h") }
        }
        commit(out)
    }

    val s = session ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            VideoSurface(player, aspect = if (s.width > 0) s.width / s.height.toFloat() else 16f / 9f)
            Text(stringResource(R.string.editor_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionCard(stringResource(R.string.auto_edit)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Preset.HIGHLIGHTS to R.string.preset_highlights, Preset.CONDENSED to R.string.preset_condensed,
                        Preset.SERVES to R.string.preset_serves, Preset.FOREHANDS to R.string.preset_forehands, Preset.BACKHANDS to R.string.preset_backhands,
                        Preset.WINNERS to R.string.preset_winners, Preset.LONGEST to R.string.preset_longest, Preset.FASTEST to R.string.preset_fastest,
                    ).forEach { (p, l) -> OutlinedButton(onClick = { generate(p) }) { Text(stringResource(l)) } }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.timeline, clips.count { it.enabled }, formatDuration(clips.filter { it.enabled }.sumOf { ((it.endMs - it.startMs) / it.speed).toLong() })), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { clips = history.undo(); scope.launch { app.db.edits().replaceClips(id, clips) } }, enabled = history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                IconButton(onClick = { clips = history.redo(); scope.launch { app.db.edits().replaceClips(id, clips) } }, enabled = history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
            }
            if (clips.isEmpty()) EmptyHint(stringResource(R.string.no_clips))
        }
        itemsIndexed(clips, key = { i, c -> "${c.startMs}_${c.endMs}_$i" }) { i, c ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${c.label}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(formatDuration(c.startMs) + "–" + formatDuration(c.endMs), style = MaterialTheme.typography.labelMedium)
                }
                Row {
                    IconButton(onClick = { player.seekSession(segments, c.startMs); player.playbackParameters = PlaybackParameters(c.speed); player.play(); previewEnd = c.endMs }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.preview)) }
                    IconButton(onClick = { if (i > 0) commit(clips.toMutableList().also { val t = it[i]; it[i] = it[i - 1]; it[i - 1] = t }) }) { Icon(Icons.Filled.ArrowUpward, stringResource(R.string.move_up)) }
                    IconButton(onClick = { if (i < clips.size - 1) commit(clips.toMutableList().also { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t }) }) { Icon(Icons.Filled.ArrowDownward, stringResource(R.string.move_down)) }
                    IconButton(onClick = {
                        val pos = player.sessionPosition(segments)
                        if (pos > c.startMs + 300 && pos < c.endMs - 300) commit(clips.toMutableList().also { it[i] = c.copy(endMs = pos); it.add(i + 1, c.copy(startMs = pos)) })
                    }) { Icon(Icons.Filled.ContentCut, stringResource(R.string.split_here)) }
                    IconButton(onClick = { commit(clips.toMutableList().also { it[i] = c.copy(favorite = !c.favorite) }) }) { Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite)) }
                    IconButton(onClick = { commit(clips.toMutableList().also { it[i] = c.copy(enabled = !c.enabled) }) }) { Icon(if (c.enabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, stringResource(R.string.include_clip)) }
                }
                val lo = (c.startMs - 10_000).coerceAtLeast(0).toFloat(); val hi = (c.endMs + 10_000).coerceAtMost(s.durationMs.coerceAtLeast(c.endMs)).toFloat()
                if (hi > lo) RangeSlider(
                    value = c.startMs.toFloat()..c.endMs.toFloat(),
                    onValueChange = { r -> clips = clips.toMutableList().also { it[i] = c.copy(startMs = r.start.toLong(), endMs = maxOf(r.endInclusive.toLong(), r.start.toLong() + 300)) } },
                    onValueChangeFinished = { commit(clips) },
                    valueRange = lo..hi,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.25f, 0.5f, 1f).forEach { sp -> FilterChip(c.speed == sp, { commit(clips.toMutableList().also { it[i] = c.copy(speed = sp) }) }, { Text("${sp}x") }) }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.export_video)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AspectFormat.entries.forEach { f -> FilterChip(format == f, { format = f }, { Text(f.label) }) } }
                Text(stringResource(R.string.overlays), fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(ovScore, { ovScore = !ovScore }, { Text(stringResource(R.string.tab_score)) })
                    FilterChip(ovNames, { ovNames = !ovNames }, { Text(stringResource(R.string.ov_names)) })
                    FilterChip(ovSpeed, { ovSpeed = !ovSpeed }, { Text(stringResource(R.string.ov_speed)) })
                    FilterChip(ovShot, { ovShot = !ovShot }, { Text(stringResource(R.string.ov_shot)) })
                    FilterChip(ovRally, { ovRally = !ovRally }, { Text(stringResource(R.string.ov_rally)) })
                    FilterChip(ovLabel, { ovLabel = !ovLabel }, { Text(stringResource(R.string.ov_label)) })
                }
                Button(enabled = !exporting && clips.any { it.enabled }, onClick = {
                    scope.launch {
                        exporting = true; result = null
                        val shots = app.db.analysis().shots(id)
                        val points = app.db.points().forSession(id)
                        val rallies = app.db.analysis().rallies(id)
                        val render = clips.filter { it.enabled }.map { c ->
                            val lines = mutableListOf<String>()
                            if (ovNames) lines += "${s.nameA()} vs ${s.nameB()}"
                            if (ovScore && s.mode.isMatch) lines += s.scoringSession().board(points.filter { (it.endMs ?: Long.MAX_VALUE) <= c.startMs }.toScoringEvents()).callout
                            val inClip = shots.filter { it.timeMs in c.startMs..c.endMs }
                            if (ovSpeed) inClip.mapNotNull { it.speedKmh }.maxOrNull()?.let { lines += "${formatNum(it)} km/h" }
                            if (ovShot) inClip.firstOrNull()?.let { lines += it.type.lowercase() }
                            if (ovRally) rallies.firstOrNull { it.startMs <= c.endMs && it.endMs >= c.startMs }?.let { lines += "${it.shots} shots · ${(it.endMs - it.startMs) / 1000}s" }
                            if (ovLabel && c.label.isNotBlank()) lines += c.label
                            RenderClip(c.startMs, c.endMs, c.speed, lines)
                        }
                        val ex = VideoExporter(context)
                        val r = ex.export(segments, render, format, "courtvision_${id}_${System.currentTimeMillis()}")
                        result = r.fold({ f -> ex.publish(f); DataExporter(context).share(f, "video/mp4"); context.getString(R.string.export_done) }, { e -> context.getString(R.string.export_failed, e.message ?: "") })
                        exporting = false
                    }
                }) { Text(stringResource(if (exporting) R.string.working else R.string.export_mp4)) }
                result?.let { Text(it) }
            }
        }
    }
}
