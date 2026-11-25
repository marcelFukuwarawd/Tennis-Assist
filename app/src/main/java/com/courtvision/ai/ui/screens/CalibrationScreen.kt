package com.courtvision.ai.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.courtvision.ai.ui.CornerEditor
import com.courtvision.ai.ui.app
import com.courtvision.ai.ui.defaultCorners
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatPct
import com.courtvision.vision.CourtAutoDetector
import com.courtvision.vision.CourtCalibration
import com.courtvision.vision.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CalibrationScreen(nav: NavHostController, id: Long) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.db.sessions().observe(id).collectAsState(initial = null)
    val segments by app.db.segments().observe(id).collectAsState(initial = emptyList())
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var timeFrac by remember { mutableFloatStateOf(0.02f) }
    var corners by remember { mutableStateOf<List<Vec2>?>(null) }
    var support by remember { mutableStateOf<Double?>(null) }
    var autoFailed by remember { mutableStateOf(false) }
    val s = session
    LaunchedEffect(s?.id) { if (s != null && corners == null) corners = s.calibration()?.let { d -> d.xs.indices.map { Vec2(d.xs[it], d.ys[it]) } } ?: defaultCorners }
    LaunchedEffect(segments, timeFrac) {
        val seg = segments.firstOrNull { it.valid } ?: return@LaunchedEffect
        frame = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(seg.uri))
                val w = 960
                val src = r.getFrameAtTime((seg.durationMs * timeFrac * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                src?.let { Bitmap.createScaledBitmap(it, w, (w * it.height / it.width.toFloat()).toInt(), true) }
            } catch (_: Exception) { null } finally { runCatching { r.release() } }
        }
    }
    if (s == null) return
    val court = s.court()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.calibrate_court), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.calibration_hint), style = MaterialTheme.typography.bodySmall)
        val f = frame
        val c = corners
        if (f != null && c != null) {
            CornerEditor(f, court, c, { corners = it; support = null })
            Text(stringResource(R.string.frame_at, formatDuration(((segments.firstOrNull()?.durationMs ?: 0) * timeFrac).toLong())), style = MaterialTheme.typography.labelMedium)
            Slider(timeFrac, { timeFrac = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        val res = withContext(Dispatchers.Default) {
                            val px = IntArray(f.width * f.height); f.getPixels(px, 0, f.width, 0, 0, f.width, f.height)
                            CourtAutoDetector(court).detect(px, f.width, f.height)
                        }
                        if (res == null) autoFailed = true else {
                            autoFailed = false
                            corners = res.corners.map { Vec2(it.x / f.width, it.y / f.height) }
                            support = res.lineSupport
                        }
                    }
                }) { Text(stringResource(R.string.auto_detect)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        val px = IntArray(f.width * f.height); f.getPixels(px, 0, f.width, 0, 0, f.width, f.height)
                        val det = CourtAutoDetector(court)
                        val cal = CourtCalibration.fromCorners(court, c.map { Vec2(it.x * f.width, it.y * f.height) }, f.width, f.height)
                        support = det.lineSupport(cal, det.lineMask(px, f.width, f.height), f.width, f.height)
                    }
                }) { Text(stringResource(R.string.check_alignment)) }
            }
            if (autoFailed) Text(stringResource(R.string.auto_failed), color = MaterialTheme.colorScheme.error)
            support?.let { Text(stringResource(R.string.line_support, formatPct(it)), color = if (it >= 0.5) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Button(onClick = {
                scope.launch {
                    val data = CalibrationData(c.map { it.x }, c.map { it.y }, auto = false, lineSupport = support)
                    app.db.sessions().setCalibration(id, AppJson.encodeToString(CalibrationData.serializer(), data))
                    // calibration changed: analysis must be redone from the start
                    AnalysisScheduler.reprocessFrom(context, id, 0)
                    nav.popBackStack()
                }
            }) { Text(stringResource(R.string.save_and_analyze)) }
        } else Text(stringResource(R.string.loading))
    }
}
