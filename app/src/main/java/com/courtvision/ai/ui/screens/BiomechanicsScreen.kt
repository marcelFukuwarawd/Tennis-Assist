package com.courtvision.ai.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.SegmentEntity
import com.courtvision.ai.processing.MlModels
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.Lime
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.StatRow
import com.courtvision.ai.ui.Unsure
import com.courtvision.ai.ui.app
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatNum
import com.courtvision.vision.Biomechanics
import com.courtvision.vision.Pose
import com.courtvision.vision.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class FrameState(val bitmap: Bitmap?, val pose: Pose?)

private suspend fun loadFrame(context: android.content.Context, segments: List<SegmentEntity>, t: Long): Bitmap? = withContext(Dispatchers.IO) {
    val seg = segments.filter { it.valid }.lastOrNull { it.startOffsetMs <= t } ?: return@withContext null
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, Uri.parse(seg.uri))
        val src = r.getFrameAtTime((t - seg.startOffsetMs) * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@withContext null
        val w = 960
        Bitmap.createScaledBitmap(src, w, (w * src.height / src.width.toFloat()).toInt(), true)
    } catch (_: Exception) { null } finally { runCatching { r.release() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BiomechanicsScreen(nav: NavHostController, id: Long, t: Long) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.db.sessions().observe(id).collectAsState(initial = null)
    val segments by app.db.segments().observe(id).collectAsState(initial = emptyList())
    val shots by app.db.analysis().observeShots(id).collectAsState(initial = emptyList())
    var tA by remember { mutableStateOf(t) }
    var tB by remember { mutableStateOf<Long?>(null) }
    var a by remember { mutableStateOf(FrameState(null, null)) }
    var b by remember { mutableStateOf(FrameState(null, null)) }
    var busy by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(0) } // 0 none, 1 angle, 2 line
    val anglePts = remember { mutableStateListOf<Offset>() }
    val lines = remember { mutableStateListOf<Pair<Offset, Offset>>() }
    var poseError by remember { mutableStateOf(false) }
    val models = remember { lazy { MlModels(context, preferGpu = false) } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (models.isInitialized()) models.value.close() } }

    LaunchedEffect(segments, tA) { if (segments.isNotEmpty()) a = FrameState(loadFrame(context, segments, tA), null) }
    LaunchedEffect(segments, tB) { tB?.let { tt -> b = FrameState(loadFrame(context, segments, tt), null) } }

    fun detect() = scope.launch {
        busy = true; poseError = false
        withContext(Dispatchers.Default) {
            val m = models.value
            if (!m.available) { poseError = true; return@withContext }
            a.bitmap?.let { bmp -> a = FrameState(bmp, m.poses(bmp).maxByOrNull { it.box.area }) }
            b.bitmap?.let { bmp -> b = FrameState(bmp, m.poses(bmp).maxByOrNull { it.box.area }) }
        }
        busy = false
    }

    val dur = session?.durationMs ?: 0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.biomechanics), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.biomech_disclaimer), style = MaterialTheme.typography.labelSmall, color = Unsure)
        }
        item {
            FrameView(a, anglePts.takeIf { tool == 1 }, lines, tool) { o -> if (tool == 1) { if (anglePts.size >= 3) anglePts.clear(); anglePts += o } }
            Text(formatDuration(tA))
            if (dur > 0) Slider(tA.toFloat(), { tA = it.toLong() }, valueRange = 0f..dur.toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { tA = (tA - 33).coerceAtLeast(0) }) { Text("−1f") }
                OutlinedButton(onClick = { tA += 33 }) { Text("+1f") }
                FilledTonalButton(onClick = { detect() }, enabled = !busy) { Text(stringResource(if (busy) R.string.working else R.string.detect_pose)) }
            }
            if (poseError) Text(stringResource(R.string.pose_unavailable), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(tool == 1, { tool = if (tool == 1) 0 else 1; anglePts.clear() }, { Text(stringResource(R.string.tool_angle)) })
                FilterChip(tool == 2, { tool = if (tool == 2) 0 else 2 }, { Text(stringResource(R.string.tool_lines)) })
                OutlinedButton(onClick = { lines.clear(); anglePts.clear() }) { Text(stringResource(R.string.clear)) }
            }
            if (anglePts.size == 3) Text(stringResource(R.string.measured_angle, formatNum(Biomechanics.angle(anglePts[0].v(), anglePts[1].v(), anglePts[2].v()), 1)), color = Lime)
        }
        a.pose?.let { p -> item { PoseReport(p) } }
        item {
            SectionCard(stringResource(R.string.compare_shots)) {
                Text(stringResource(R.string.compare_info), style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shots.filter { it.type in setOf("FOREHAND", "BACKHAND", "SERVE") }.take(40).forEach { sh ->
                        OutlinedButton(onClick = { if (tB == null) tB = sh.timeMs else tA = sh.timeMs }) { Text(shotLabel(sh.type) + " " + formatDuration(sh.timeMs)) }
                    }
                }
                if (shots.isEmpty()) EmptyHint(stringResource(R.string.compare_no_shots))
                tB?.let { tt ->
                    FrameView(b, null, remember { mutableStateListOf() }, 0) {}
                    Text(formatDuration(tt))
                    Slider(tt.toFloat(), { tB = it.toLong() }, valueRange = 0f..dur.toFloat().coerceAtLeast(1f))
                }
            }
        }
        val pa = a.pose?.let { Biomechanics.normalise(it) }
        val pb = b.pose?.let { Biomechanics.normalise(it) }
        if (pa != null && pb != null) item {
            SectionCard(stringResource(R.string.pose_overlay)) {
                Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                    val sc = size.height / 5f
                    fun o(v: Vec2) = Offset(size.width / 2 + (v.x * sc).toFloat(), size.height * 0.62f + (v.y * sc).toFloat())
                    Pose.BONES.forEach { (i, j) ->
                        drawLine(Lime, o(pa[i]), o(pa[j]), 4f)
                        drawLine(Color(0xFF9AD0FF), o(pb[i]), o(pb[j]), 4f)
                    }
                }
                Text(stringResource(R.string.pose_difference, formatNum(Biomechanics.similarity(pa, pb), 2)))
            }
        }
    }
}

private fun Offset.v() = Vec2(x.toDouble(), y.toDouble())

@Composable
private fun FrameView(f: FrameState, angle: List<Offset>?, lines: MutableList<Pair<Offset, Offset>>, tool: Int, onTap: (Offset) -> Unit) {
    val bmp = f.bitmap ?: run { Text(stringResource(R.string.loading)); return }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxWidth().aspectRatio(bmp.width / bmp.height.toFloat())) {
        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(tool) { detectTapGestures { onTap(it) } }
                .pointerInput(tool) {
                    if (tool == 2) detectDragGestures(
                        onDragStart = { dragStart = it; dragEnd = it },
                        onDragEnd = { val s = dragStart; val e = dragEnd; if (s != null && e != null) lines += s to e; dragStart = null; dragEnd = null },
                        onDrag = { ch, d -> ch.consume(); dragEnd = (dragEnd ?: Offset.Zero) + d },
                    )
                },
        ) {
            val sx = size.width / bmp.width; val sy = size.height / bmp.height
            f.pose?.let { p ->
                Pose.BONES.forEach { (i, j) ->
                    if (p[i].visibility > 0.4 && p[j].visibility > 0.4) drawLine(Lime, Offset((p[i].x * sx).toFloat(), (p[i].y * sy).toFloat()), Offset((p[j].x * sx).toFloat(), (p[j].y * sy).toFloat()), 4f)
                }
                p.landmarks.forEach { l -> if (l.visibility > 0.4) drawCircle(Color.White, 4f, Offset((l.x * sx).toFloat(), (l.y * sy).toFloat())) }
            }
            angle?.forEach { drawCircle(Color.Yellow, 8f, it) }
            if (angle != null && angle.size >= 2) { drawLine(Color.Yellow, angle[0], angle[1], 3f); if (angle.size == 3) drawLine(Color.Yellow, angle[1], angle[2], 3f) }
            lines.forEach { (s, e) -> drawLine(Color.Cyan, s, e, 3f) }
            val s = dragStart; val e = dragEnd
            if (s != null && e != null) drawLine(Color.Cyan.copy(alpha = 0.6f), s, e, 3f)
        }
    }
}

@Composable
private fun PoseReport(p: Pose) {
    val r = Biomechanics.analyse(p)
    SectionCard(stringResource(R.string.joint_angles)) {
        StatRow(stringResource(R.string.left_knee), formatNum(r.leftKnee) + "°")
        StatRow(stringResource(R.string.right_knee), formatNum(r.rightKnee) + "°")
        StatRow(stringResource(R.string.left_elbow), formatNum(r.leftElbow) + "°")
        StatRow(stringResource(R.string.right_elbow), formatNum(r.rightElbow) + "°")
        StatRow(stringResource(R.string.shoulder_hip_sep), formatNum(r.shoulderHipSeparation) + "°")
        StatRow(stringResource(R.string.shoulder_turn), formatNum(r.shoulderTurnRatio, 2))
        StatRow(stringResource(R.string.stance_width), formatNum(r.stanceWidth, 2))
        StatRow(stringResource(R.string.balance), formatNum(r.balanceOffset, 2))
        Text(stringResource(R.string.joint_angles_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
