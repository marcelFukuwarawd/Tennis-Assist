package com.courtvision.ai.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CaptureRequest
import android.os.PowerManager
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.camera.CameraController
import com.courtvision.ai.camera.LensOption
import com.courtvision.ai.camera.LiveAnalyzer
import com.courtvision.ai.camera.ManualControls
import com.courtvision.ai.data.AppJson
import com.courtvision.ai.data.AppSettings
import com.courtvision.ai.data.BallColor
import com.courtvision.ai.data.CalibrationData
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.PrecisionAttemptEntity
import com.courtvision.ai.data.SessionMode
import com.courtvision.ai.data.SessionStatus
import com.courtvision.ai.data.calibration
import com.courtvision.ai.data.court
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.data.scoringSession
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.processing.AnalysisScheduler
import com.courtvision.ai.ui.CornerEditor
import com.courtvision.ai.ui.InGreen
import com.courtvision.ai.ui.Lime
import com.courtvision.ai.ui.OutRed
import com.courtvision.ai.ui.Routes
import com.courtvision.ai.ui.Unsure
import com.courtvision.ai.ui.app
import com.courtvision.ai.ui.defaultCorners
import com.courtvision.ai.util.Storage
import com.courtvision.ai.util.formatDuration
import com.courtvision.scoring.Side
import com.courtvision.vision.BallColorProfile
import com.courtvision.vision.CallResult
import com.courtvision.vision.CourtAutoDetector
import com.courtvision.vision.LineCaller
import com.courtvision.vision.PlacementAdvice
import com.courtvision.vision.PlacementAdvisor
import com.courtvision.vision.PlayArea
import com.courtvision.vision.Vec2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingScreen(nav: NavHostController, sessionId: Long) {
    val app = app()
    val context = LocalContext.current
    val activity = context as? Activity
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsState(initial = AppSettings())
    val session by app.db.sessions().observe(sessionId).collectAsState(initial = null)
    val points by app.db.points().observe(sessionId).collectAsState(initial = emptyList())

    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasCamera = r[Manifest.permission.CAMERA] == true || hasCamera
    }
    LaunchedEffect(Unit) {
        val req = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        if (app.settings.current().recordAudio) req += Manifest.permission.RECORD_AUDIO
        permLauncher.launch(req.toTypedArray())
    }

    // landscape + keep screen on while this screen is visible
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
        }
    }

    val controller = remember { CameraController(context) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    var lenses by remember { mutableStateOf<List<LensOption>>(emptyList()) }
    var lensId by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf<Quality>(Quality.FHD) }
    var fps by remember { mutableIntStateOf(30) }
    var zoom by remember { mutableFloatStateOf(0f) }
    var ev by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(ManualControls()) }
    var showSettings by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var segmentBase by remember { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var luma by remember { mutableStateOf<Double?>(null) }
    var roll by remember { mutableStateOf<Double?>(null) }
    var pitch by remember { mutableStateOf<Double?>(null) }
    var thermal by remember { mutableIntStateOf(PowerManager.THERMAL_STATUS_NONE) }
    var lastCall by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var calibFrame by remember { mutableStateOf<Bitmap?>(null) }
    var corners by remember { mutableStateOf(defaultCorners) }
    var bindError by remember { mutableStateOf(false) }
    val redo = remember { mutableStateListOf<PointEntity>() }
    val court = session?.court()
    val precision = session?.mode == SessionMode.TRAINING_PRECISION
    val zones by app.db.training().observeZones().collectAsState(initial = emptyList())

    val analyzer = remember(court) {
        court?.let { c ->
            LiveAnalyzer(
                c,
                when (settings.ballColor) { BallColor.OPTIC_YELLOW -> BallColorProfile.TENNIS_OPTIC_YELLOW; BallColor.ORANGE -> BallColorProfile.PICKLEBALL_ORANGE; BallColor.GREEN -> BallColorProfile.PICKLEBALL_GREEN },
                onLuma = { luma = it },
            )
        }
    }
    // bounce handling needs the latest state, so it is attached after creation
    LaunchedEffect(analyzer, zones, settings, session) {
        val s = session ?: return@LaunchedEffect
        val c = court ?: return@LaunchedEffect
        val a = analyzer ?: return@LaunchedEffect
        a.calibration = s.calibration()
        a.trackingEnabled = (settings.liveAnalysis || precision) && thermal < PowerManager.THERMAL_STATUS_SEVERE
        val caller = LineCaller(c, settings.callThreshold)
        val area = c.area(if (s.mode.isDoubles) PlayArea.DOUBLES else PlayArea.SINGLES)
        a.bounceListener = { p, sigma, _ ->
            scope.launch {
                if (precision) {
                    val zone = zones.firstOrNull { z -> p.x in z.minX..z.maxX && p.y in z.minY..z.maxY }
                    val outOfCourt = caller.call(p, sigma, area).result == CallResult.OUT
                    val ref = zones.firstOrNull()
                    val result = when {
                        zone != null -> "HIT"
                        outOfCourt -> "OUT"
                        ref != null && kotlin.math.abs(p.y - c.netY) < kotlin.math.abs((ref.minY + ref.maxY) / 2 - c.netY) - 1 -> "SHORT"
                        ref != null && kotlin.math.abs(p.y - c.netY) > kotlin.math.abs((ref.minY + ref.maxY) / 2 - c.netY) + 1 -> "LONG"
                        else -> "MISS"
                    }
                    app.db.training().insertAttempt(PrecisionAttemptEntity(sessionId = sessionId, timeMs = segmentBase + elapsed, zoneId = zone?.id, x = p.x, y = p.y, result = result))
                    val txt = context.getString(
                        when (result) { "HIT" -> R.string.voice_hit; "OUT" -> R.string.voice_out; "SHORT" -> R.string.voice_short; "LONG" -> R.string.voice_long; else -> R.string.voice_miss },
                    )
                    lastCall = txt to if (result == "HIT") InGreen else OutRed
                    if (settings.voiceCalls) app.voice.say(txt)
                } else {
                    val call = caller.call(p, sigma, area)
                    val (txt, col) = when (call.result) {
                        CallResult.IN -> context.getString(R.string.call_in) to InGreen
                        CallResult.OUT -> context.getString(R.string.call_out) to OutRed
                        CallResult.INCONCLUSIVE -> context.getString(R.string.call_inconclusive) to Unsure
                    }
                    lastCall = txt to col
                    if (settings.voiceCalls) app.voice.say(txt)
                }
                delay(2500); lastCall = null
            }
        }
    }

    suspend fun bind() {
        bindError = false
        runCatching {
            controller.bind(owner, previewView, lensId, quality, fps, analyzer)
        }.onFailure { bindError = true }
    }
    LaunchedEffect(hasCamera) {
        if (!hasCamera) return@LaunchedEffect
        val st = app.settings.current()
        quality = when (st.resolution) { "UHD" -> Quality.UHD; "HD" -> Quality.HD; "SD" -> Quality.SD; else -> Quality.FHD }
        fps = st.fps
        lenses = runCatching { controller.lenses() }.getOrDefault(emptyList())
        bind()
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    // sensors for camera level / tilt
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (gx, gy, gz) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                @Suppress("DEPRECATION")
                val rot = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                val (sx, sy) = when (rot) { Surface.ROTATION_90 -> -gy to gx; Surface.ROTATION_180 -> -gx to -gy; Surface.ROTATION_270 -> gy to -gx; else -> gx to gy }
                roll = Math.toDegrees(atan2(sx, sy))
                pitch = Math.toDegrees(atan2(gz, hypot(sx, sy)))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        val pm = context.getSystemService(PowerManager::class.java)
        val tl = PowerManager.OnThermalStatusChangedListener { thermal = it }
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(context), tl)
        onDispose { sm.unregisterListener(listener); pm.removeThermalStatusListener(tl) }
    }

    fun stop(finish: Boolean) {
        controller.stopRecording()
        recording = false
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
        if (finish) scope.launch {
            delay(800) // let the last segment finalise
            app.db.sessions().get(sessionId)?.let { app.db.sessions().update(it.copy(status = SessionStatus.READY)) }
            AnalysisScheduler.enqueue(context, sessionId)
            nav.popBackStack()
            nav.navigate(Routes.session(sessionId))
        }
    }

    // safety: critical temperature or almost no storage stops the recording cleanly
    LaunchedEffect(thermal, recording) {
        if (recording && thermal >= PowerManager.THERMAL_STATUS_CRITICAL) stop(true)
        analyzer?.trackingEnabled = (settings.liveAnalysis || precision) && thermal < PowerManager.THERMAL_STATUS_SEVERE
    }
    LaunchedEffect(recording) {
        while (recording) {
            delay(10_000)
            if (Storage.freeBytes(context) < 300L * 1024 * 1024) stop(true)
        }
    }

    fun startRecording() = scope.launch {
        for (i in settings.countdownSec downTo 1) { countdown = i; delay(1000) }
        countdown = 0
        val dir = app.repo.sessionDir(sessionId)
        segmentBase = app.db.segments().forSession(sessionId).filter { it.valid }.sumOf { it.durationMs }
        controller.startRecording(dir, settings.segmentMinutes, settings.recordAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            onSegment = { uri, _ ->
                scope.launch {
                    app.repo.addSegment(sessionId, uri, app.repo.probe(uri))
                    segmentBase = app.db.segments().forSession(sessionId).filter { it.valid }.sumOf { it.durationMs }
                    elapsed = 0
                }
            },
            onStatus = { elapsed = it },
        )
        recording = true
        if (settings.dimScreen) activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 0.08f } }
    }

    fun addPoint(winner: Side) = scope.launch {
        val now = segmentBase + elapsed
        val prevEnd = points.lastOrNull()?.endMs ?: 0
        app.db.points().replaceAll(sessionId, points + PointEntity(sessionId = sessionId, order = points.size, winnerA = winner == Side.A, startMs = maxOf(prevEnd, now - 15_000), endMs = now))
        redo.clear()
        val s = session ?: return@launch
        if (settings.voiceScore) {
            val board = s.scoringSession().board((points + PointEntity(sessionId = sessionId, order = 0, winnerA = winner == Side.A)).toScoringEvents())
            app.voice.say(board.callout.replace("-", " "))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) AndroidView({ previewView }, Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { o -> controller.focusAt(previewView, o.x, o.y, false) }, onLongPress = { o -> controller.focusAt(previewView, o.x, o.y, true); manual = manual.copy(aeLock = true); controller.applyManual(manual) })
        })
        else Text(stringResource(R.string.camera_permission_needed), Modifier.align(Alignment.Center), color = Color.White)

        // ---- top status bar ----
        Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording) Icon(Icons.Filled.FiberManualRecord, null, tint = OutRed)
                Text(formatDuration(segmentBase + elapsed), color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.minutes_left, Storage.minutesLeft(context, if (quality == Quality.UHD) "UHD" else "FHD")), color = Color.White, style = MaterialTheme.typography.labelMedium)
                if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) Text("  🌡 " + stringResource(if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) R.string.thermal_severe else R.string.thermal_moderate), color = Unsure, style = MaterialTheme.typography.labelMedium)
            }
            if (Storage.minutesLeft(context, "FHD") < 15) Text(stringResource(R.string.low_storage), color = OutRed, fontWeight = FontWeight.Bold)
            if (bindError) Text(stringResource(R.string.camera_error), color = OutRed)
            if (controller.analysisBound.not() && hasCamera && (settings.liveAnalysis || precision)) Text(stringResource(R.string.live_unavailable), color = Unsure, style = MaterialTheme.typography.labelSmall)
            if (!recording) {
                val c = session?.calibration()
                val advice = PlacementAdvisor.advise(roll, pitch, luma, c?.let { d -> d.xs.indices.map { Vec2(d.xs[it] * 1000, d.ys[it] * 1000) } }, 1000, 1000)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    advice.forEach { a ->
                        Text(
                            adviceLabel(a), color = if (a == PlacementAdvice.OK) InGreen else Unsure,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        lastCall?.let { (t, c) ->
            Text(t, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.large).padding(24.dp), color = c, fontSize = 48.sp, fontWeight = FontWeight.Black)
        }
        if (countdown > 0) Text("$countdown", Modifier.align(Alignment.Center), color = Lime, fontSize = 96.sp, fontWeight = FontWeight.Black)

        // ---- right controls ----
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { if (!recording) showSettings = true }) { Icon(Icons.Filled.Tune, null, tint = Color.White) }
            IconButton(onClick = { analyzer?.latestFrame?.let { calibFrame = it; corners = session?.calibration()?.let { d -> d.xs.indices.map { i -> Vec2(d.xs[i], d.ys[i]) } } ?: defaultCorners } }) {
                Icon(Icons.Filled.CropFree, null, tint = Color.White)
            }
            Box(
                Modifier.size(84.dp).background(if (recording) Color.White else OutRed, CircleShape).pointerInput(recording) {
                    detectTapGestures { if (recording) stop(true) else if (countdown == 0) startRecording() }
                },
                contentAlignment = Alignment.Center,
            ) { Icon(if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null, tint = if (recording) OutRed else Color.White, modifier = Modifier.size(40.dp)) }
            IconButton(onClick = { locked = true }) { Icon(Icons.Filled.Lock, null, tint = Color.White) }
        }

        // ---- scoreboard with big buttons ----
        val s = session
        if (s != null && s.mode.isMatch) {
            val board = s.scoringSession().board(points.toScoringEvents())
            Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.large).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { addPoint(Side.A) }, Modifier.height(72.dp).width(150.dp)) { Text("+ " + s.nameA(), maxLines = 1) }
                Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text((if (board.server == Side.A) "● " else "") + s.nameA() + "  " + board.setsOrGamesA.joinToString(" ") + "  " + board.currentA, color = Color.White, fontWeight = FontWeight.Bold)
                    Text((if (board.server == Side.B) "● " else "") + s.nameB() + "  " + board.setsOrGamesB.joinToString(" ") + "  " + board.currentB, color = Color.White, fontWeight = FontWeight.Bold)
                    Row {
                        IconButton(onClick = { scope.launch { points.lastOrNull()?.let { last -> redo += last; app.db.points().replaceAll(sessionId, points.dropLast(1)) } } }, enabled = points.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Filled.Undo, null, tint = Color.White)
                        }
                        TextButton(onClick = { scope.launch { redo.removeLastOrNull()?.let { p -> app.db.points().replaceAll(sessionId, points + p) } } }, enabled = redo.isNotEmpty()) { Text(stringResource(R.string.redo)) }
                    }
                }
                Button(onClick = { addPoint(Side.B) }, Modifier.height(72.dp).width(150.dp)) { Text("+ " + s.nameB(), maxLines = 1) }
            }
        }

        // ---- calibration overlay ----
        val frame = calibFrame
        if (frame != null && court != null) {
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).padding(8.dp)) {
                Text(stringResource(R.string.calibration_hint), color = Color.White)
                Row(Modifier.weight(1f)) {
                    CornerEditor(frame, court, corners, { corners = it }, Modifier.weight(1f))
                    Column(Modifier.width(180.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            val w = frame.width; val h = frame.height
                            val px = IntArray(w * h); frame.getPixels(px, 0, w, 0, 0, w, h)
                            CourtAutoDetector(court).detect(px, w, h)?.let { r -> corners = r.corners.map { Vec2(it.x / w, it.y / h) } }
                        }) { Text(stringResource(R.string.auto_detect)) }
                        Button(onClick = {
                            scope.launch {
                                val data = CalibrationData(corners.map { it.x }, corners.map { it.y }, auto = false)
                                app.db.sessions().setCalibration(sessionId, AppJson.encodeToString(CalibrationData.serializer(), data))
                                analyzer?.calibration = data
                                calibFrame = null
                            }
                        }) { Text(stringResource(R.string.save)) }
                        TextButton(onClick = { calibFrame = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }

        // ---- touch lock: long press to unlock ----
        if (locked) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)).pointerInput(Unit) { detectTapGestures(onLongPress = { locked = false }) },
            contentAlignment = Alignment.TopCenter,
        ) { Text(stringResource(R.string.locked_hint), Modifier.padding(16.dp).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp), color = Color.White) }
    }

    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }) {
        val caps = controller.caps()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lens), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(lensId == null, { lensId = null; scope.launch { bind() } }, { Text(stringResource(R.string.lens_default)) })
                lenses.forEach { l -> FilterChip(lensId == l.cameraId, { lensId = l.cameraId; scope.launch { bind() } }, { Text(stringResource(when (l.kind) { com.courtvision.ai.camera.LensKind.ULTRAWIDE -> R.string.lens_ultrawide; com.courtvision.ai.camera.LensKind.TELE -> R.string.lens_tele; else -> R.string.lens_main }) + " ${l.equivalentMm}mm") }) }
            }
            Text(stringResource(R.string.resolution), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (caps?.qualities ?: listOf(Quality.HD, Quality.FHD)).forEach { q ->
                    val label = when (q) { Quality.UHD -> "4K"; Quality.FHD -> "1080p"; Quality.HD -> "720p"; Quality.SD -> "480p"; else -> q.toString() }
                    FilterChip(quality == q, { quality = q; scope.launch { bind() } }, { Text(label) })
                }
            }
            Text("FPS", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (caps?.fpsOptions ?: listOf(30)).forEach { f -> FilterChip(fps == f, { fps = f; scope.launch { bind() } }, { Text("$f") }) }
            }
            Text(stringResource(R.string.zoom), fontWeight = FontWeight.Bold)
            Slider(zoom, { zoom = it; controller.setZoom(it) })
            caps?.let { c ->
                if (c.exposureRange.upper > c.exposureRange.lower) {
                    Text(stringResource(R.string.exposure) + " ${"%.1f".format(ev * c.exposureStep)} EV", fontWeight = FontWeight.Bold)
                    Slider(ev.toFloat(), { ev = it.toInt(); controller.setExposureIndex(ev) }, valueRange = c.exposureRange.lower.toFloat()..c.exposureRange.upper.toFloat())
                }
                c.isoRange?.let { r ->
                    ToggleRow(stringResource(R.string.manual_iso_shutter), manual.iso != null) { on ->
                        manual = if (on) manual.copy(iso = r.lower.coerceAtLeast(100), shutterNs = 2_000_000L) else manual.copy(iso = null, shutterNs = null)
                        controller.applyManual(manual)
                    }
                    manual.iso?.let { iso ->
                        Text("ISO $iso")
                        Slider(iso.toFloat(), { manual = manual.copy(iso = it.toInt()); controller.applyManual(manual) }, valueRange = r.lower.toFloat()..r.upper.toFloat())
                        c.shutterRange?.let { sr ->
                            val ns = manual.shutterNs ?: 2_000_000L
                            Text(stringResource(R.string.shutter) + " 1/${(1_000_000_000L / ns.coerceAtLeast(1))} s")
                            val lo = sr.lower.coerceAtLeast(100_000L).toFloat(); val hi = minOf(sr.upper, 33_000_000L).toFloat()
                            if (hi > lo) Slider(ns.toFloat().coerceIn(lo, hi), { manual = manual.copy(shutterNs = it.toLong()); controller.applyManual(manual) }, valueRange = lo..hi)
                        }
                    }
                }
                c.minFocusDiopters?.takeIf { it > 0 }?.let { maxD ->
                    ToggleRow(stringResource(R.string.manual_focus), manual.focusDiopters != null) { on -> manual = manual.copy(focusDiopters = if (on) 0.1f else null); controller.applyManual(manual) }
                    manual.focusDiopters?.let { d -> Slider(d, { manual = manual.copy(focusDiopters = it); controller.applyManual(manual) }, valueRange = 0f..maxD) }
                }
            }
            Text(stringResource(R.string.white_balance), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    CaptureRequest.CONTROL_AWB_MODE_AUTO to R.string.wb_auto, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to R.string.wb_daylight,
                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to R.string.wb_cloudy, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to R.string.wb_fluorescent,
                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to R.string.wb_incandescent,
                ).forEach { (m, l) -> FilterChip(manual.awbMode == m, { manual = manual.copy(awbMode = m); controller.applyManual(manual) }, { Text(stringResource(l)) }) }
            }
            ToggleRow(stringResource(R.string.ae_lock), manual.aeLock) { manual = manual.copy(aeLock = it); controller.applyManual(manual) }
            ToggleRow(stringResource(R.string.awb_lock), manual.awbLock) { manual = manual.copy(awbLock = it); controller.applyManual(manual) }
            Text(stringResource(R.string.focus_hint), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun adviceLabel(a: PlacementAdvice): String = stringResource(
    when (a) {
        PlacementAdvice.OK -> R.string.adv_ok
        PlacementAdvice.LEVEL_DEVICE -> R.string.adv_level
        PlacementAdvice.TILT_DOWN -> R.string.adv_tilt_down
        PlacementAdvice.TILT_UP -> R.string.adv_tilt_up
        PlacementAdvice.MOVE_BACK -> R.string.adv_move_back
        PlacementAdvice.CENTER_COURT -> R.string.adv_center
        PlacementAdvice.ZOOM_IN -> R.string.adv_zoom
        PlacementAdvice.LOW_LIGHT -> R.string.adv_low_light
        PlacementAdvice.RAISE_CAMERA -> R.string.adv_raise
        PlacementAdvice.CALIBRATE -> R.string.adv_calibrate
    },
)
