package com.courtvision.ai.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.courtvision.ai.data.SegmentEntity
import com.courtvision.vision.CourtCalibration
import com.courtvision.vision.CourtModel
import com.courtvision.vision.Vec2

/** Builds an ExoPlayer with the valid segments of a session as a gapless playlist. */
@Composable
fun rememberSessionPlayer(segments: List<SegmentEntity>): ExoPlayer {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val key = segments.filter { it.valid }.joinToString { it.uri }
    remember(key) {
        player.setMediaItems(segments.filter { it.valid }.map { MediaItem.fromUri(Uri.parse(it.uri)) })
        player.prepare()
        key
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    return player
}

/** Seeks to a session-global timestamp across segments. */
fun ExoPlayer.seekSession(segments: List<SegmentEntity>, globalMs: Long) {
    val valid = segments.filter { it.valid }
    val idx = valid.indexOfLast { it.startOffsetMs <= globalMs }.coerceAtLeast(0)
    if (valid.isEmpty()) return
    seekTo(idx, (globalMs - valid[idx].startOffsetMs).coerceAtLeast(0))
}

fun ExoPlayer.sessionPosition(segments: List<SegmentEntity>): Long {
    val valid = segments.filter { it.valid }
    val off = valid.getOrNull(currentMediaItemIndex)?.startOffsetMs ?: 0
    return off + currentPosition
}

@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayer, modifier: Modifier = Modifier, aspect: Float = 16f / 9f, overlay: @Composable () -> Unit = {}) {
    Box(modifier.fillMaxWidth().aspectRatio(aspect)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(false); setShowPreviousButton(false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        overlay()
    }
}

/** Hides the status and navigation bars while the composable is on screen (swipe to reveal). */
@Composable
fun ImmersiveMode(landscape: Boolean = false) {
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = view.context as? android.app.Activity
    DisposableEffect(Unit) {
        val window = activity?.window
        val ctrl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        ctrl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val oldOrientation = activity?.requestedOrientation
        if (landscape) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            ctrl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (landscape) activity?.requestedOrientation = oldOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/** Extra space around the frame so corners outside the picture can still be placed. */
private const val EDIT_MARGIN = 0.12f

/**
 * Court calibration editor (4 corners: 1 far-left, 2 far-right, 3 near-right, 4 near-left):
 * - drag a corner directly, or drag ANYWHERE else to move the selected corner slowly
 *   (fine "touchpad" mode, the finger never covers the point);
 * - a 3x magnifier follows the selected corner;
 * - the projected court model is drawn live so the user sees the alignment;
 * - corners may be placed outside the picture (useful when the near corners are cut off).
 * Corners are normalised image coordinates.
 */
@Composable
fun CornerEditor(
    frame: Bitmap?,
    court: CourtModel,
    corners: List<Vec2>,
    onChange: (List<Vec2>) -> Unit,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** false = transparent editor drawn over the live camera preview (no picture, no outer margin). */
    showImage: Boolean = true,
    aspectRatio: Float = frame?.let { it.width / it.height.toFloat() } ?: (16f / 9f),
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var fineDrag by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val image = remember(frame) { frame?.asImageBitmap() }
    val latest = androidx.compose.runtime.rememberUpdatedState(corners)
    val latestSel = androidx.compose.runtime.rememberUpdatedState(selected)
    val margin = if (showImage) EDIT_MARGIN else 0f
    val aspect = aspectRatio
    val fw = frame?.width ?: 1280; val fh = frame?.height ?: 720
    fun toCanvas(p: Vec2, size: IntSize) = toCanvas(p, size, margin)
    Box(modifier.aspectRatio(aspect).onSizeChanged { size = it }) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val c = latest.value
                        val idx = c.indices.minByOrNull { (toCanvas(c[it], size) - o).getDistance() } ?: return@detectTapGestures
                        onSelect(idx)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val c = latest.value
                            val near = c.indices.minByOrNull { (toCanvas(c[it], size) - o).getDistance() }
                            fineDrag = near == null || (toCanvas(c[near], size) - o).getDistance() > 48.dp.toPx()
                            if (!fineDrag && near != null) onSelect(near)
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, amount ->
                            change.consume()
                            if (size.width == 0) return@detectDragGestures
                            val k = if (fineDrag) 0.25f else 1f
                            val scaleX = (1 + 2 * margin) / size.width
                            val scaleY = (1 + 2 * margin) / size.height
                            val c = latest.value
                            val i = latestSel.value
                            val n = Vec2(
                                (c[i].x + amount.x * k * scaleX).coerceIn(-EDIT_MARGIN.toDouble(), 1.0 + EDIT_MARGIN),
                                (c[i].y + amount.y * k * scaleY).coerceIn(-EDIT_MARGIN.toDouble(), 1.0 + EDIT_MARGIN),
                            )
                            onChange(c.toMutableList().also { it[i] = n })
                        },
                    )
                },
        ) {
            val w = this.size.width; val h = this.size.height
            val imgTl = toCanvas(Vec2(0.0, 0.0), size); val imgBr = toCanvas(Vec2(1.0, 1.0), size)
            if (showImage) {
                drawRect(Color(0xFF1A1A1A))
                if (image != null) drawImage(image, dstOffset = IntOffset(imgTl.x.toInt(), imgTl.y.toInt()), dstSize = IntSize((imgBr.x - imgTl.x).toInt(), (imgBr.y - imgTl.y).toInt()))
            }
            val segs = courtSegments(court, corners, fw, fh).map { (a, b) -> toCanvas(a, size) to toCanvas(b, size) }
            segs.forEach { (a, b) -> drawLine(Lime.copy(alpha = 0.85f), a, b, 2.dp.toPx()) }
            val pts = corners.map { toCanvas(it, size) }
            pts.forEachIndexed { i, p ->
                val sel = i == selected
                drawCircle(if (sel) Color.Yellow else Color.White, if (sel) 16.dp.toPx() else 12.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                drawLine(Color.Black, Offset(p.x - 6.dp.toPx(), p.y), Offset(p.x + 6.dp.toPx(), p.y), 1.dp.toPx())
                drawLine(Color.Black, Offset(p.x, p.y - 6.dp.toPx()), Offset(p.x, p.y + 6.dp.toPx()), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(
                    "${i + 1}", p.x + 14.dp.toPx(), p.y - 14.dp.toPx(),
                    android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; textSize = 18.dp.toPx(); isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK) },
                )
            }
            // magnifier on the selected corner, placed on the opposite side of the screen
            val sp = pts[selected]
            val box = minOf(w, h) * 0.42f
            val left = if (sp.x < w / 2) w - box - 8.dp.toPx() else 8.dp.toPx()
            val top = 8.dp.toPx()
            val zoom = 3f
            val center = Offset(left + box / 2, top + box / 2)
            fun mag(p: Offset) = center + (p - sp) * zoom
            if (image != null) drawRect(Color.Black, Offset(left, top), Size(box, box))
            if (image != null) clipRect(left, top, left + box, top + box) {
                val tl = mag(imgTl); val br = mag(imgBr)
                drawImage(image, dstOffset = IntOffset(tl.x.toInt(), tl.y.toInt()), dstSize = IntSize((br.x - tl.x).toInt(), (br.y - tl.y).toInt()))
                segs.forEach { (a, b) -> drawLine(Lime, mag(a), mag(b), 1.5.dp.toPx()) }
                drawLine(Color.Yellow, Offset(center.x - box / 2, center.y), Offset(center.x + box / 2, center.y), 1f)
                drawLine(Color.Yellow, Offset(center.x, center.y - box / 2), Offset(center.x, center.y + box / 2), 1f)
            }
            if (image != null) drawRect(Color.Yellow, Offset(left, top), Size(box, box), style = Stroke(2.dp.toPx()))
        }
    }
}

private fun toCanvas(p: Vec2, size: IntSize, margin: Float = EDIT_MARGIN): Offset {
    val k = 1 + 2 * margin
    return Offset(((p.x + margin) / k * size.width).toFloat(), ((p.y + margin) / k * size.height).toFloat())
}

/** Court guide (projected court lines) drawn over the live camera preview. */
@Composable
fun CourtGuideOverlay(court: CourtModel, corners: List<Vec2>, modifier: Modifier = Modifier, aspectRatio: Float = 16f / 9f) {
    Canvas(modifier.aspectRatio(aspectRatio)) {
        val sz = IntSize(size.width.toInt(), size.height.toInt())
        courtSegments(court, corners, 1280, (1280 / aspectRatio).toInt()).forEach { (a, b) ->
            drawLine(Lime.copy(alpha = 0.75f), toCanvas(a, sz, 0f), toCanvas(b, sz, 0f), 2.dp.toPx())
        }
    }
}

/** Court lines projected into normalised image coordinates for the current corner hypothesis. */
private fun courtSegments(court: CourtModel, corners: List<Vec2>, w: Int, h: Int): List<Pair<Vec2, Vec2>> {
    val cal = CourtCalibration.fromCorners(court, corners.map { Vec2(it.x * w, it.y * h) }, w, h)
    val inv = cal.courtToImage ?: return emptyList()
    return court.lines().mapNotNull { l ->
        val a = inv.map(l.a); val b = inv.map(l.b)
        if (!a.x.isFinite() || !b.x.isFinite()) null else Vec2(a.x / w, a.y / h) to Vec2(b.x / w, b.y / h)
    }
}

/** Corner selector + 1-pixel nudge arrows for the calibration editor. */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CornerControls(corners: List<Vec2>, frameWidth: Int, selected: Int, onSelect: (Int) -> Unit, onChange: (List<Vec2>) -> Unit) {
    val names = listOf(
        androidx.compose.ui.res.stringResource(com.courtvision.ai.R.string.corner_1), androidx.compose.ui.res.stringResource(com.courtvision.ai.R.string.corner_2),
        androidx.compose.ui.res.stringResource(com.courtvision.ai.R.string.corner_3), androidx.compose.ui.res.stringResource(com.courtvision.ai.R.string.corner_4),
    )
    fun nudge(dx: Int, dy: Int) {
        val step = 1.0 / frameWidth
        onChange(corners.toMutableList().also { it[selected] = Vec2(it[selected].x + dx * step, it[selected].y + dy * step) })
    }
    androidx.compose.foundation.layout.Column {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            names.forEachIndexed { i, n -> androidx.compose.material3.FilterChip(selected == i, { onSelect(i) }, { androidx.compose.material3.Text("${i + 1}. $n") }) }
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.FilledTonalIconButton(onClick = { nudge(-1, 0) }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.FilledTonalIconButton(onClick = { nudge(0, -1) }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp, null) }
                androidx.compose.material3.FilledTonalIconButton(onClick = { nudge(0, 1) }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, null) }
            }
            androidx.compose.material3.FilledTonalIconButton(onClick = { nudge(1, 0) }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
        }
    }
}

/** Runs automatic detection + fine alignment on a frame; returns normalised corners and line agreement. */
fun autoCalibrate(frame: Bitmap, court: CourtModel, start: List<Vec2>?): Pair<List<Vec2>, Double>? {
    val w = frame.width; val h = frame.height
    val px = IntArray(w * h); frame.getPixels(px, 0, w, 0, 0, w, h)
    val det = com.courtvision.vision.CourtAutoDetector(court)
    val mask = det.lineMask(px, w, h)
    val initial = start?.map { Vec2(it.x * w, it.y * h) } ?: det.detect(px, w, h)?.corners ?: return null
    val (refined, support) = det.refine(initial, mask, w, h)
    return refined.map { Vec2(it.x / w, it.y / h) } to support
}

/** Default corner guess: a centred trapezoid typical of a camera behind the baseline. */
val defaultCorners = listOf(Vec2(0.36, 0.30), Vec2(0.64, 0.30), Vec2(0.92, 0.88), Vec2(0.08, 0.88))
