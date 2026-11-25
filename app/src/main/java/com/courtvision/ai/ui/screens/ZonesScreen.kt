package com.courtvision.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.TargetZoneEntity
import com.courtvision.ai.ui.CourtCanvas
import com.courtvision.ai.ui.Lime
import com.courtvision.ai.ui.app
import com.courtvision.vision.CourtModel
import com.courtvision.vision.Vec2
import kotlinx.coroutines.launch

/** Target zones for precision training (court metres; far half = opponent side). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZonesScreen(nav: NavHostController) {
    val app = app()
    val scope = rememberCoroutineScope()
    val zones by app.db.training().observeZones().collectAsState(initial = emptyList())
    var pickle by remember { mutableStateOf(false) }
    var firstCorner by remember { mutableStateOf<Vec2?>(null) }
    val court = if (pickle) CourtModel.PICKLEBALL else CourtModel.TENNIS
    val mine = zones.filter { it.pickleball == pickle }
    fun add(name: String, a: Vec2, b: Vec2) = scope.launch {
        app.db.training().insertZone(TargetZoneEntity(name = name, pickleball = pickle, minX = minOf(a.x, b.x), minY = minOf(a.y, b.y), maxX = maxOf(a.x, b.x), maxY = maxOf(a.y, b.y)))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.edit_targets), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!pickle, { pickle = false }, { Text(stringResource(R.string.filter_tennis)) })
                FilterChip(pickle, { pickle = true }, { Text(stringResource(R.string.filter_pickleball)) })
            }
            Text(stringResource(R.string.zones_hint), style = MaterialTheme.typography.labelMedium)
        }
        item {
            CourtCanvas(court, onTap = { p ->
                val f = firstCorner
                if (f == null) firstCorner = p else { add("Zone ${mine.size + 1}", f, p); firstCorner = null }
            }) { proj ->
                mine.forEach { z ->
                    val a = proj.toCanvas(Vec2(z.minX, z.minY)); val b = proj.toCanvas(Vec2(z.maxX, z.maxY))
                    drawRect(Lime.copy(alpha = 0.35f), a, Size(b.x - a.x, b.y - a.y))
                }
                firstCorner?.let { drawCircle(Lime, 8f, proj.toCanvas(it)) }
            }
        }
        item {
            val n = court.netY; val w = court.doublesWidth; val c = w / 2
            val sd = court.serviceDistance
            val inset = (court.doublesWidth - court.singlesWidth) / 2
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!pickle) {
                    OutlinedButton(onClick = { add("T (deuce)", Vec2(c - 1.0, n - sd), Vec2(c, n - sd + 1.5)) }) { Text(stringResource(R.string.zone_serve_t)) }
                    OutlinedButton(onClick = { add("Wide (deuce)", Vec2(inset, n - sd), Vec2(inset + 1.2, n - sd + 1.5)) }) { Text(stringResource(R.string.zone_serve_wide)) }
                }
                OutlinedButton(onClick = { add("Deep cross", Vec2(inset, 0.0), Vec2(c, 3.0)) }) { Text(stringResource(R.string.zone_deep_cross)) }
                OutlinedButton(onClick = { add("Deep line", Vec2(w - inset - 2.0, 0.0), Vec2(w - inset, 3.0)) }) { Text(stringResource(R.string.zone_deep_line)) }
                OutlinedButton(onClick = { add("Depth", Vec2(inset, 0.0), Vec2(w - inset, n / 3)) }) { Text(stringResource(R.string.zone_depth)) }
            }
        }
        items(mine, key = { it.id }) { z ->
            Row(Modifier.fillMaxWidth().clickable { }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(z.name, Modifier.weight(1f))
                Text("%.1f×%.1f m".format(z.maxX - z.minX, z.maxY - z.minY), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { scope.launch { app.db.training().deleteZone(z) } }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
            }
        }
    }
}
