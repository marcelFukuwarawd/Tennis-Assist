package com.courtvision.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.courtvision.vision.CourtModel
import com.courtvision.vision.CourtType
import com.courtvision.vision.Vec2

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun StatRow(label: String, a: String, b: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a, Modifier.width(72.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (b != null) Text(b, Modifier.width(72.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Mapping between court metres and a canvas drawn with the far baseline at the top. */
class CourtProjection(val court: CourtModel, val size: Size, private val margin: Float) {
    private val totalW = court.doublesWidth + 2 * margin
    private val totalL = court.length + 2 * margin
    val scale = minOf(size.width / totalW.toFloat(), size.height / totalL.toFloat())
    private val ox = (size.width - totalW.toFloat() * scale) / 2
    private val oy = (size.height - totalL.toFloat() * scale) / 2
    fun toCanvas(p: Vec2) = Offset(ox + ((p.x + margin) * scale).toFloat(), oy + ((p.y + margin) * scale).toFloat())
    fun toCourt(o: Offset) = Vec2((o.x - ox) / scale - margin.toDouble(), (o.y - oy) / scale - margin.toDouble())
}

fun DrawScope.drawCourt(proj: CourtProjection, surface: Color, line: Color) {
    val c = proj.court
    val tl = proj.toCanvas(Vec2(0.0, 0.0)); val br = proj.toCanvas(Vec2(c.doublesWidth, c.length))
    drawRect(surface, tl, Size(br.x - tl.x, br.y - tl.y))
    if (c.type == CourtType.PICKLEBALL) {
        c.nonVolleyZones().forEach {
            val a = proj.toCanvas(Vec2(it.minX, it.minY)); val b = proj.toCanvas(Vec2(it.maxX, it.maxY))
            drawRect(line.copy(alpha = 0.10f), a, Size(b.x - a.x, b.y - a.y))
        }
    }
    for (l in c.lines()) {
        val w = if (l.name == "net") 3.dp.toPx() else 1.5.dp.toPx()
        drawLine(if (l.name == "net") line.copy(alpha = 0.9f) else line, proj.toCanvas(l.a), proj.toCanvas(l.b), w)
    }
}

/** Court diagram with an overlay drawer and optional tap (in court metres). */
@Composable
fun CourtCanvas(
    court: CourtModel,
    modifier: Modifier = Modifier,
    margin: Float = if (court.type == CourtType.TENNIS) 3f else 1.5f,
    onTap: ((Vec2) -> Unit)? = null,
    overlay: DrawScope.(CourtProjection) -> Unit = {},
) {
    val ratio = ((court.doublesWidth + 2 * margin) / (court.length + 2 * margin)).toFloat()
    var size by remember { mutableStateOf(Size.Zero) }
    Box(
        modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.3f, 2f))
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(onTap, size) {
                if (onTap != null) detectTapGestures { o -> if (size.width > 0) onTap(CourtProjection(court, size, margin).toCourt(o)) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val proj = CourtProjection(court, this.size, margin)
            drawRect(Color(0xFF14301F), Offset.Zero, this.size)
            drawCourt(proj, CourtBlue, Color.White)
            overlay(proj)
        }
    }
}

fun DrawScope.uncertaintyEllipse(center: Offset, rx: Float, ry: Float, color: Color) {
    drawOval(color.copy(alpha = 0.25f), Offset(center.x - rx, center.y - ry), Size(2 * rx, 2 * ry))
    drawOval(color, Offset(center.x - rx, center.y - ry), Size(2 * rx, 2 * ry), style = Stroke(1.dp.toPx()))
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(2.dp)
            .then(Modifier),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.18f))) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}

val spaced = Arrangement.spacedBy(12.dp)
