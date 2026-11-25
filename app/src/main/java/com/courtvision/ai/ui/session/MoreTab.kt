package com.courtvision.ai.ui.session

import com.courtvision.ai.util.Feedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.courtvision.ai.data.SessionMode
import com.courtvision.ai.export.DataExporter
import com.courtvision.ai.ui.Routes
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.screens.ToggleRow
import com.courtvision.ai.ui.screens.modeLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreTab(vm: SessionViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.session.collectAsState()
    val session = s ?: return
    var notes by remember(session.id) { mutableStateOf(session.notes) }
    var a by remember(session.id) { mutableStateOf(session.playerA) }
    var b by remember(session.id) { mutableStateOf(session.playerB) }
    var busy by remember { mutableStateOf<String?>(null) }
    var playerIds by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var mergeFrom by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(session.analysisState) { playerIds = vm.app.db.analysis().players(session.id).groupingBy { it.playerId }.eachCount() }
    val exporter = remember { DataExporter(context) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard(stringResource(R.string.tools)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate(Routes.calibrate(session.id)) }) { Text(stringResource(R.string.calibrate_court)) }
                    FilledTonalButton(onClick = { nav.navigate(Routes.editor(session.id)) }) { Text(stringResource(R.string.video_editor)) }
                    FilledTonalButton(onClick = { nav.navigate(Routes.biomech(session.id)) }) { Text(stringResource(R.string.biomechanics)) }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.export)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { busy = "csv"; exporter.share(exporter.csv(session), "application/zip"); busy = null } }) { Text("CSV") }
                    OutlinedButton(onClick = { scope.launch { busy = "json"; exporter.share(exporter.json(session), "application/json"); busy = null } }) { Text("JSON") }
                    OutlinedButton(onClick = { scope.launch { busy = "pdf"; exporter.share(exporter.pdf(session), "application/pdf"); busy = null } }) { Text("PDF") }
                }
                busy?.let { Text(stringResource(R.string.working)) }
                Text(stringResource(R.string.export_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SectionCard(stringResource(R.string.session_info)) {
                Text(stringResource(R.string.mode))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SessionMode.entries.forEach { m -> FilterChip(session.mode == m, { vm.update(session.copy(mode = m)); Feedback.show(context) }, { Text(modeLabel(m)) }) }
                }
                OutlinedTextField(a, { a = it }, label = { Text(stringResource(R.string.player_a)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(b, { b = it }, label = { Text(stringResource(R.string.player_b)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ToggleRow(stringResource(R.string.left_handed), !session.rightHandedA) { vm.update(session.copy(rightHandedA = !it)); Feedback.show(context) }
                ToggleRow(stringResource(R.string.first_server) + ": " + (if (session.firstServerA) a.ifBlank { "A" } else b.ifBlank { "B" }), session.firstServerA) { vm.update(session.copy(firstServerA = it)); Feedback.show(context) }
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(onClick = { vm.update(session.copy(notes = notes, playerA = a.trim(), playerB = b.trim())); Feedback.show(context) }) { Text(stringResource(R.string.save)) }
            }
        }
        if (playerIds.size > 1) item {
            SectionCard(stringResource(R.string.player_identity)) {
                Text(stringResource(R.string.player_identity_info), style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(stringResource(R.string.merge_from)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    playerIds.forEach { (id, n) -> FilterChip(mergeFrom == id, { mergeFrom = id }, { Text("#$id ($n)") }) }
                }
                mergeFrom?.let { from ->
                    Text(stringResource(R.string.merge_into))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        playerIds.keys.filter { it != from }.forEach { into ->
                            OutlinedButton(onClick = {
                                scope.launch {
                                    vm.app.db.analysis().relabelPlayer(session.id, from, into)
                                    vm.app.db.analysis().shots(session.id).filter { it.playerId == from }.forEach { vm.app.db.analysis().updateShot(it.copy(playerId = into, edited = true)) }
                                    playerIds = vm.app.db.analysis().players(session.id).groupingBy { it.playerId }.eachCount(); mergeFrom = null; Feedback.show(context)
                                }
                            }) { Text("#$into") }
                        }
                    }
                }
            }
        }
    }
}
