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

/**
 * Draggable 4-corner editor over a frame (far-left, far-right, near-right, near-left).
 * Corners are normalised 0..1. The projected court model is drawn so the user can check alignment.
 */
@Composable
fun CornerEditor(frame: Bitmap, court: CourtModel, corners: List<Vec2>, onChange: (List<Vec2>) -> Unit, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableIntStateOf(-1) }
    val aspect = frame.width / frame.height.toFloat()
    Box(modifier.fillMaxWidth().aspectRatio(aspect).onSizeChanged { size = it }) {
        Image(frame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(corners) {
                detectDragGestures(
                    onDragStart = { o ->
                        dragging = corners.indices.minByOrNull { i ->
                            val c = Offset((corners[i].x * size.width).toFloat(), (corners[i].y * size.height).toFloat())
                            (c - o).getDistance()
                        } ?: -1
                    },
                    onDragEnd = { dragging = -1 },
                    onDrag = { change, amount ->
                        change.consume()
                        if (dragging >= 0 && size.width > 0) {
                            val c = corners[dragging]
                            val n = Vec2(
                                (c.x + amount.x / size.width).coerceIn(0.0, 1.0),
                                (c.y + amount.y / size.height).coerceIn(0.0, 1.0),
                            )
                            onChange(corners.toMutableList().also { it[dragging] = n })
                        }
                    },
                )
            },
        ) {
            val w = this.size.width; val h = this.size.height
            val pts = corners.map { Offset((it.x * w).toFloat(), (it.y * h).toFloat()) }
            // projected court lines with the current hypothesis
            val cal = CourtCalibration.fromCorners(court, corners.map { Vec2(it.x * w, it.y * h) }, w.toInt(), h.toInt())
            val inv = cal.courtToImage
            if (inv != null) for (l in court.lines()) {
                val a = inv.map(l.a); val b = inv.map(l.b)
                if (a.x.isFinite() && b.x.isFinite()) drawLine(Lime.copy(alpha = 0.8f), Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), 2.dp.toPx())
            }
            val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close() }
            drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
            pts.forEachIndexed { i, p ->
                drawCircle(if (i == dragging) Color.Yellow else Lime, 14.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.4f), 4.dp.toPx(), p)
            }
        }
    }
}

/** Default corner guess: a centred trapezoid typical of a camera behind the baseline. */
val defaultCorners = listOf(Vec2(0.36, 0.30), Vec2(0.64, 0.30), Vec2(0.92, 0.88), Vec2(0.08, 0.88))
