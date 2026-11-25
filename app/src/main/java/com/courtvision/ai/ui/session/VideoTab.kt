package com.courtvision.ai.ui.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import com.courtvision.ai.R
import com.courtvision.ai.data.AnalysisState
import com.courtvision.ai.data.BallTrackEntity
import com.courtvision.ai.data.MarkerEntity
import com.courtvision.ai.processing.AnalysisScheduler
import com.courtvision.ai.ui.InGreen
import com.courtvision.ai.ui.Lime
import com.courtvision.ai.ui.OutRed
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.Unsure
import com.courtvision.ai.ui.VideoSurface
import com.courtvision.ai.ui.rememberSessionPlayer
import com.courtvision.ai.ui.screens.callLabel
import com.courtvision.ai.ui.screens.errorLabel
import com.courtvision.ai.ui.screens.stageLabel
import com.courtvision.ai.ui.seekSession
import com.courtvision.ai.ui.sessionPosition
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatPct
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun callColor(c: String?) = when (c) { "IN" -> InGreen; "OUT" -> OutRed; else -> Unsure }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoTab(vm: SessionViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.session.collectAsState()
    val segments by vm.segments.collectAsState()
    val bounces by vm.bounces.collectAsState()
    val seek by vm.seek.collectAsState()
    val session = s ?: return
    val player = rememberSessionPlayer(segments)
    var pos by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableStateOf(false) }
    var showTrack by remember { mutableStateOf(true) }
    var window by remember { mutableStateOf<List<BallTrackEntity>>(emptyList()) }
    var windowRange by remember { mutableStateOf(LongRange.EMPTY) }

    LaunchedEffect(seek, segments) {
        val t = seek ?: return@LaunchedEffect
        if (segments.isEmpty()) return@LaunchedEffect
        player.seekSession(segments, t); player.play(); vm.consumeSeek()
    }
    LaunchedEffect(player, segments) {
        while (true) {
            pos = player.sessionPosition(segments)
            if (pos !in windowRange) {
                windowRange = (pos - 20_000)..(pos + 40_000)
                window = vm.app.db.analysis().trackRange(session.id, windowRange.first, windowRange.last)
            }
            delay(40)
        }
    }
    val frameMs = if (session.fps > 1) (1000 / session.fps).toLong() else 33L
    val aspect = if (session.width > 0 && session.height > 0) session.width / session.height.toFloat() else 16f / 9f
    val recentBounce = bounces.lastOrNull { it.timeMs <= pos && pos - it.timeMs < 3000 }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val origin = recentBounce?.let { TransformOrigin(it.imageX.toFloat(), it.imageY.toFloat()) } ?: TransformOrigin.Center
            VideoSurface(
                player,
                Modifier.graphicsLayer { if (zoom) { scaleX = 2.5f; scaleY = 2.5f; transformOrigin = origin }; clip = true },
                aspect,
            ) {
                if (showTrack) Canvas(Modifier.fillMaxSize()) {
                    val pts = window.filter { it.timeMs in (pos - 900)..pos }
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]; val b = pts[i]
                        if (b.timeMs - a.timeMs > 200) continue
                        val o1 = Offset((a.x * size.width).toFloat(), (a.y * size.height).toFloat())
                        val o2 = Offset((b.x * size.width).toFloat(), (b.y * size.height).toFloat())
                        // estimated (predicted) segments are drawn dashed and orange
                        if (a.observed && b.observed) drawLine(Lime, o1, o2, 3.dp.toPx())
                        else drawLine(Unsure, o1, o2, 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }
                    bounces.filter { it.timeMs in (pos - 2500)..pos }.forEach { bc ->
                        drawCircle(callColor(bc.call), 7.dp.toPx(), Offset((bc.imageX * size.width).toFloat(), (bc.imageY * size.height).toFloat()))
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.pause(); player.seekSession(segments, pos - frameMs) }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.frame_back)) }
                IconButton(onClick = { player.pause(); player.seekSession(segments, pos + frameMs) }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.frame_forward)) }
                IconButton(onClick = { player.seekSession(segments, pos - 5000); speed = 0.25f; player.playbackParameters = PlaybackParameters(0.25f); player.play() }) {
                    Icon(Icons.Filled.SlowMotionVideo, stringResource(R.string.instant_replay))
                }
                IconButton(onClick = { zoom = !zoom }) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_bounce), tint = if (zoom) Lime else MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick = { scope.launch { vm.app.db.edits().insertMarker(MarkerEntity(sessionId = session.id, timeMs = pos, label = formatDuration(pos))) } }) {
                    Icon(Icons.Filled.BookmarkAdd, stringResource(R.string.add_marker))
                }
                Text(formatDuration(pos), Modifier.padding(start = 8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.125f, 0.25f, 0.5f, 1f, 2f).forEach { v -> FilterChip(speed == v, { speed = v; player.playbackParameters = PlaybackParameters(v) }, { Text("${v}x") }) }
                FilterChip(showTrack, { showTrack = !showTrack }, { Text(stringResource(R.string.show_trajectory)) })
            }
            Text(stringResource(R.string.trajectory_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { AnalysisCard(vm, pos) }
        val markers = vm.markers.value
        if (markers.isNotEmpty()) item {
            SectionCard(stringResource(R.string.markers)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    markers.forEach { m -> OutlinedButton(onClick = { vm.requestSeek(m.timeMs) }) { Text(m.label) } }
                }
            }
        }
        if (bounces.isNotEmpty()) item {
            Text(stringResource(R.string.line_calls), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.line_calls_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(bounces.asReversed().take(300)) { b ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    player.seekSession(segments, b.timeMs - 1500); speed = 0.25f; player.playbackParameters = PlaybackParameters(0.25f); player.play()
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(callLabel(b.call), Modifier.weight(1f), color = callColor(b.call), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.p_in, formatPct(b.probabilityIn)), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("±${(maxOf(b.sigmaX, b.sigmaY) * 100).toInt()} cm", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text(formatDuration(b.timeMs))
            }
        }
    }
}

@Composable
private fun AnalysisCard(vm: SessionViewModel, pos: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.session.collectAsState()
    val session = s ?: return
    SectionCard(stringResource(R.string.analysis)) {
        when (session.analysisState) {
            AnalysisState.NONE -> {
                Text(stringResource(R.string.analysis_none), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { scope.launch { AnalysisScheduler.enqueue(context, session.id) } }) { Text(stringResource(R.string.analyze)) }
            }
            AnalysisState.QUEUED, AnalysisState.RUNNING -> {
                Text(stageLabel(session))
                LinearProgressIndicator(progress = { session.analysisProgress }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { scope.launch { AnalysisScheduler.pause(context, session.id) } }) { Text(stringResource(R.string.pause)) }
                    OutlinedButton(onClick = { scope.launch { AnalysisScheduler.cancel(context, session.id) } }) { Text(stringResource(R.string.cancel)) }
                }
            }
            AnalysisState.PAUSED -> {
                Text(stageLabel(session))
                session.analysisError?.let { Text(errorLabel(it), color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { AnalysisScheduler.enqueue(context, session.id) } }) { Text(stringResource(R.string.resume)) }
                    OutlinedButton(onClick = { scope.launch { AnalysisScheduler.cancel(context, session.id) } }) { Text(stringResource(R.string.cancel)) }
                }
            }
            AnalysisState.FAILED -> {
                Text(errorLabel(session.analysisError), color = MaterialTheme.colorScheme.error)
                Button(onClick = { scope.launch { AnalysisScheduler.enqueue(context, session.id) } }) { Text(stringResource(R.string.retry)) }
            }
            AnalysisState.DONE -> {
                Text(stringResource(R.string.analysis_done), color = InGreen)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { AnalysisScheduler.reprocessFrom(context, session.id, pos) } }) { Text(stringResource(R.string.reprocess_from_here)) }
                    OutlinedButton(onClick = { scope.launch { AnalysisScheduler.reprocessFrom(context, session.id, 0) } }) { Text(stringResource(R.string.reprocess_all)) }
                }
            }
        }
    }
}

