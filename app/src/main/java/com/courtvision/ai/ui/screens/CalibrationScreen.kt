package com.courtvision.ai.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.AppJson
import com.courtvision.ai.data.CalibrationData
import com.courtvision.ai.data.calibration
import com.courtvision.ai.data.court
import com.courtvision.ai.processing.AnalysisScheduler
import com.courtvision.ai.ui.CornerControls
import com.courtvision.ai.ui.CornerEditor
import com.courtvision.ai.ui.ImmersiveMode
import com.courtvision.ai.ui.app
import com.courtvision.ai.ui.autoCalibrate
import com.courtvision.ai.ui.defaultCorners
import com.courtvision.ai.util.Feedback
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatPct
import com.courtvision.vision.CourtModel
import com.courtvision.vision.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen landscape court calibration on a recorded/imported video frame. */
@Composable
fun CalibrationScreen(nav: NavHostController, id: Long) {
    ImmersiveMode(landscape = true)
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.db.sessions().observe(id).collectAsState(initial = null)
    val segments by app.db.segments().observe(id).collectAsState(initial = emptyList())
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var timeFrac by remember { mutableFloatStateOf(0.02f) }
    var corners by remember { mutableStateOf<List<Vec2>?>(null) }
    LaunchedEffect(session?.id) {
        val s = session ?: return@LaunchedEffect
        if (corners == null) corners = s.calibration()?.let { d -> d.xs.indices.map { Vec2(d.xs[it], d.ys[it]) } } ?: defaultCorners
    }
    LaunchedEffect(segments, timeFrac) {
        val seg = segments.firstOrNull { it.valid } ?: return@LaunchedEffect
        frame = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(seg.uri))
                val src = r.getFrameAtTime((seg.durationMs * timeFrac * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                src?.let { Bitmap.createScaledBitmap(it, 1280, (1280 * it.height / it.width.toFloat()).toInt(), true) }
            } catch (_: Exception) { null } finally { runCatching { r.release() } }
        }
    }
    val s = session ?: return
    val c = corners ?: return
    CalibrationLayout(
        frame = frame, court = s.court(), corners = c, onChange = { corners = it },
        extraTop = {
            Text(stringResource(R.string.frame_time) + " " + formatDuration(((segments.firstOrNull()?.durationMs ?: 0) * timeFrac).toLong()), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(timeFrac, { timeFrac = it })
        },
        onSave = { support ->
            scope.launch {
                val data = CalibrationData(c.map { it.x }, c.map { it.y }, auto = false, lineSupport = support)
                app.db.sessions().setCalibration(id, AppJson.encodeToString(CalibrationData.serializer(), data))
                Feedback.show(context)
                AnalysisScheduler.reprocessFrom(context, id, 0)
                nav.popBackStack()
            }
        },
        onCancel = { nav.popBackStack() },
        saveLabel = stringResource(R.string.save_and_analyze),
    )
}

/**
 * Shared full-screen calibration layout: editor filling the screen height and a collapsible
 * control panel on the right (used for video frames and for the live camera).
 */
@Composable
fun CalibrationLayout(
    frame: Bitmap?,
    court: CourtModel,
    corners: List<Vec2>,
    onChange: (List<Vec2>) -> Unit,
    onSave: (Double?) -> Unit,
    onCancel: () -> Unit,
    saveLabel: String,
    extraTop: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(true) }
    var support by remember { mutableStateOf<Double?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun run(useCurrent: Boolean) {
        val f = frame ?: return
        scope.launch {
            busy = true; failed = false
            val r = withContext(Dispatchers.Default) { autoCalibrate(f, court, if (useCurrent) corners else null) }
            if (r == null) failed = true else { onChange(r.first); support = r.second }
            busy = false
        }
    }
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (frame != null) CornerEditor(frame, court, corners, { onChange(it); support = null }, selected, { selected = it }, Modifier.fillMaxHeight())
            else Text(stringResource(R.string.loading), color = Color.White)
            if (!panel) IconButton(onClick = { panel = true }, Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)) {
                Icon(Icons.AutoMirrored.Filled.MenuOpen, stringResource(R.string.show_panel), tint = Color.White)
            }
        }
        if (panel) Column(
            Modifier.width(250.dp).fillMaxHeight().background(Color(0xEE101312)).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.calibrate_court), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { panel = false }) { Icon(Icons.Filled.Close, stringResource(R.string.hide_panel), tint = Color.White) }
            }
            Text(stringResource(R.string.calib_help), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            extraTop()
            CornerControls(corners, frame?.width ?: 1280, selected, { selected = it }, { onChange(it); support = null })
            FilledTonalButton(onClick = { run(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.auto_detect)) }
            FilledTonalButton(onClick = { run(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (busy) R.string.working else R.string.fine_adjust)) }
            if (failed) Text(stringResource(R.string.auto_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            support?.let { Text(stringResource(R.string.line_support, formatPct(it)), color = if (it >= 0.5) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
            Button(onClick = { onSave(support) }, modifier = Modifier.fillMaxWidth()) { Text(saveLabel) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}
