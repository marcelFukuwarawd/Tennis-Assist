package com.courtvision.ai.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.courtvision.ai.R
import com.courtvision.ai.data.PlayerSampleEntity
import com.courtvision.ai.data.court
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.ui.CourtCanvas
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.Lime
import com.courtvision.ai.ui.uncertaintyEllipse
import com.courtvision.ai.ui.screens.shotLabel
import com.courtvision.vision.Vec2

private enum class MapMode { BOUNCES, HEAT, PLAYERS, TRAJECTORIES }

@Composable
fun MapTab(vm: SessionViewModel) {
    val s by vm.session.collectAsState()
    val shots by vm.shots.collectAsState()
    val bounces by vm.bounces.collectAsState()
    val session = s ?: return
    val court = session.court()
    var mode by remember { mutableStateOf(MapMode.BOUNCES) }
    var side by remember { mutableStateOf<Boolean?>(null) }
    var type by remember { mutableStateOf<String?>(null) }
    var call by remember { mutableStateOf<String?>(null) }
    var minSpeed by remember { mutableFloatStateOf(0f) }
    var players by remember { mutableStateOf<List<PlayerSampleEntity>>(emptyList()) }
    LaunchedEffect(session.id, mode) { if (mode == MapMode.PLAYERS) players = vm.app.db.analysis().players(session.id) }

    val shotFiltered = shots.filter { sh ->
        (side == null || sh.sideA == side) && (type == null || sh.type == type) && (call == null || sh.call == call) &&
            (minSpeed <= 0f || (sh.speedKmh ?: 0.0) >= minSpeed)
    }
    // bounces without shot association are only shown when no shot filter is active
    val shotFilterActive = side != null || type != null || minSpeed > 0f
    val bounceFiltered = if (shotFilterActive) emptyList() else bounces.filter { call == null || it.call == call }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MapMode.entries.toList()) { m ->
                    FilterChip(mode == m, { mode = m }, { Text(stringResource(when (m) { MapMode.BOUNCES -> R.string.map_bounces; MapMode.HEAT -> R.string.map_heat; MapMode.PLAYERS -> R.string.map_players; MapMode.TRAJECTORIES -> R.string.map_trajectories })) })
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(side == null, { side = null }, { Text(stringResource(R.string.filter_all)) }) }
                item { FilterChip(side == true, { side = true }, { Text(session.nameA()) }) }
                if (session.mode.isMatch) item { FilterChip(side == false, { side = false }, { Text(session.nameB()) }) }
                items(listOf("IN", "OUT", "INCONCLUSIVE")) { c -> FilterChip(call == c, { call = if (call == c) null else c }, { Text(com.courtvision.ai.ui.screens.callLabel(c)) }) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(type == null, { type = null }, { Text(stringResource(R.string.all_shots)) }) }
                items(shots.map { it.type }.distinct()) { t -> FilterChip(type == t, { type = t }, { Text(shotLabel(t)) }) }
            }
            Text(stringResource(R.string.min_speed, minSpeed.toInt()), style = MaterialTheme.typography.labelMedium)
            Slider(minSpeed, { minSpeed = it }, valueRange = 0f..200f)
        }
        item {
            if (shots.isEmpty() && bounces.isEmpty()) EmptyHint(stringResource(R.string.no_data_yet))
            CourtCanvas(
                court,
                onTap = { p ->
                    // tap a marker to play the corresponding video moment
                    val nearShot = shotFiltered.filter { it.bounceX != null }.minByOrNull { Vec2(it.bounceX!!, it.bounceY!!).distanceTo(p) }
                    val nearBounce = bounceFiltered.minByOrNull { Vec2(it.x, it.y).distanceTo(p) }
                    val dShot = nearShot?.let { Vec2(it.bounceX!!, it.bounceY!!).distanceTo(p) } ?: Double.MAX_VALUE
                    val dBounce = nearBounce?.let { Vec2(it.x, it.y).distanceTo(p) } ?: Double.MAX_VALUE
                    if (minOf(dShot, dBounce) < 1.2) vm.requestSeek((if (dShot < dBounce) nearShot!!.timeMs else nearBounce!!.timeMs) - 1500)
                },
            ) { proj ->
                when (mode) {
                    MapMode.BOUNCES -> {
                        bounceFiltered.forEach { b ->
                            val c = proj.toCanvas(Vec2(b.x, b.y))
                            uncertaintyEllipse(c, (b.sigmaX * proj.scale).toFloat().coerceAtLeast(3f), (b.sigmaY * proj.scale).toFloat().coerceAtLeast(3f), callColor(b.call))
                        }
                        shotFiltered.filter { it.bounceX != null }.forEach { sh -> drawCircle(callColor(sh.call), 4.dp.toPx(), proj.toCanvas(Vec2(sh.bounceX!!, sh.bounceY!!))) }
                    }
                    MapMode.HEAT -> {
                        val pts = shotFiltered.mapNotNull { sh -> sh.bounceX?.let { Vec2(it, sh.bounceY!!) } } + bounceFiltered.map { Vec2(it.x, it.y) }
                        heat(pts, court.doublesWidth, court.length, 0.75).forEach { (cell, v) ->
                            val a = proj.toCanvas(cell.first); val b = proj.toCanvas(cell.second)
                            drawRect(heatColor(v), a, Size(b.x - a.x, b.y - a.y))
                        }
                    }
                    MapMode.PLAYERS -> {
                        val pts = players.mapNotNull { p -> if (p.courtX != null && p.courtY != null) Vec2(p.courtX, p.courtY) else null }
                        heat(pts, court.doublesWidth, court.length, 0.75, margin = 4.0).forEach { (cell, v) ->
                            val a = proj.toCanvas(cell.first); val b = proj.toCanvas(cell.second)
                            drawRect(heatColor(v), a, Size(b.x - a.x, b.y - a.y))
                        }
                    }
                    MapMode.TRAJECTORIES -> shotFiltered.filter { it.bounceX != null && it.hitterX != null }.forEach { sh ->
                        drawLine(callColor(sh.call).copy(alpha = 0.7f), proj.toCanvas(Vec2(sh.hitterX!!, sh.hitterY!!)), proj.toCanvas(Vec2(sh.bounceX!!, sh.bounceY!!)), 1.5.dp.toPx())
                        drawCircle(Lime, 2.dp.toPx(), proj.toCanvas(Vec2(sh.hitterX, sh.hitterY)))
                    }
                }
            }
            Text(stringResource(R.string.map_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            // least explored zones of each half (3x3 grid)
            val pts = shotFiltered.mapNotNull { sh -> sh.bounceX?.let { Vec2(it, sh.bounceY!!) } }.filter { it.y < court.netY }
            if (pts.size >= 15) {
                val counts = IntArray(9)
                pts.forEach { p ->
                    val cx = (p.x / court.doublesWidth * 3).toInt().coerceIn(0, 2)
                    val cy = (p.y / court.netY * 3).toInt().coerceIn(0, 2)
                    counts[cy * 3 + cx]++
                }
                val min = counts.withIndex().minBy { it.value }
                val names = listOf(R.string.zone_deep_left, R.string.zone_deep_center, R.string.zone_deep_right, R.string.zone_mid_left, R.string.zone_mid_center, R.string.zone_mid_right, R.string.zone_short_left, R.string.zone_short_center, R.string.zone_short_right)
                Text(stringResource(R.string.least_explored, stringResource(names[min.index]), min.value, pts.size))
            }
        }
    }
}

private fun heat(pts: List<Vec2>, w: Double, l: Double, cell: Double, margin: Double = 1.0): List<Pair<Pair<Vec2, Vec2>, Float>> {
    if (pts.isEmpty()) return emptyList()
    val map = HashMap<Pair<Int, Int>, Int>()
    pts.forEach { p ->
        if (p.x < -margin || p.x > w + margin || p.y < -margin * 2 || p.y > l + margin * 2) return@forEach
        val key = (kotlin.math.floor(p.x / cell)).toInt() to (kotlin.math.floor(p.y / cell)).toInt()
        map[key] = (map[key] ?: 0) + 1
    }
    val max = map.values.maxOrNull()?.toFloat() ?: return emptyList()
    return map.map { (k, v) -> (Vec2(k.first * cell, k.second * cell) to Vec2((k.first + 1) * cell, (k.second + 1) * cell)) to v / max }
}

private fun heatColor(v: Float): Color {
    val t = v.coerceIn(0f, 1f)
    return Color(red = t, green = 1f - kotlin.math.abs(t - 0.5f) * 1.2f, blue = 1f - t, alpha = 0.25f + 0.55f * t)
}

