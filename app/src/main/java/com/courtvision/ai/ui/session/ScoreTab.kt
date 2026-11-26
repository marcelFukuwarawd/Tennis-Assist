package com.courtvision.ai.ui.session

import com.courtvision.ai.util.Feedback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.courtvision.ai.R
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.Pill
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.Unsure
import com.courtvision.ai.ui.screens.reasonLabel
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatPct
import com.courtvision.scoring.Side

private val quickReasons = listOf("WINNER", "UNFORCED_ERROR", "FORCED_ERROR", "ACE", "DOUBLE_FAULT")
private val reasons = listOf("UNKNOWN", "WINNER", "ACE", "DOUBLE_FAULT", "FORCED_ERROR", "UNFORCED_ERROR", "OUT", "NET", "DOUBLE_BOUNCE")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreTab(vm: SessionViewModel) {
    val s by vm.session.collectAsState()
    val points by vm.points.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    var editing by remember { mutableStateOf<Int?>(null) }
    var onlyReview by remember { mutableStateOf(false) }
    val session = s ?: return
    if (!session.mode.isMatch) {
        Column(Modifier.padding(16.dp)) { EmptyHint(stringResource(R.string.score_training_info)) }
        return
    }
    val board = vm.board(points)
    // score label after each point (always recomputed from the full list)
    val labels = remember(points) { points.indices.map { i -> vm.board(points.subList(0, i + 1))?.callout ?: "" } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard {
                if (board != null) {
                    ScoreLine((if (board.server == Side.A) "● " else "") + session.nameA(), board.setsOrGamesA, board.currentA)
                    ScoreLine((if (board.server == Side.B) "● " else "") + session.nameB(), board.setsOrGamesB, board.currentB)
                    board.winner?.let { Text(stringResource(R.string.winner_is, if (it == Side.A) session.nameA() else session.nameB()), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { vm.edit(R.string.point_added) { it + PointEntity(sessionId = session.id, order = it.size, winnerA = true) } }, Modifier.weight(1f).height(56.dp)) { Text("+ " + session.nameA()) }
                    Button(onClick = { vm.edit(R.string.point_added) { it + PointEntity(sessionId = session.id, order = it.size, winnerA = false) } }, Modifier.weight(1f).height(56.dp)) { Text("+ " + session.nameB()) }
                    IconButton(onClick = { vm.undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = { vm.redo() }, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                }
                val pending = points.count { it.needsReview }
                if (pending > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.pending_review, pending), color = Unsure, modifier = Modifier.weight(1f))
                    FilterChip(onlyReview, { onlyReview = !onlyReview }, { Text(stringResource(R.string.only_pending)) })
                }
                Text(stringResource(R.string.score_edit_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (points.isEmpty()) item { EmptyHint(stringResource(R.string.no_points)) }
        val indexed = points.withIndex().reversed().filter { !onlyReview || it.value.needsReview }
        itemsIndexed(indexed.toList(), key = { _, it -> it.value.id }) { _, iv ->
            val p = iv.value; val i = iv.index
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().clickable { editing = i }, verticalAlignment = Alignment.CenterVertically) {
                    Text("#${i + 1}", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                    Text(if (p.winnerA) session.nameA() else session.nameB(), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (p.auto) Pill(stringResource(R.string.auto) + " " + formatPct(p.confidence), MaterialTheme.colorScheme.secondary)
                    if (p.needsReview) Pill(stringResource(R.string.review), Unsure)
                    Text(labels.getOrElse(i) { "" }, Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
                // one tap to say how the point ended (feeds winners / errors / aces / double faults)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    quickReasons.forEach { r ->
                        FilterChip(
                            p.reason == r,
                            { vm.edit { l -> l.toMutableList().also { it[i] = p.copy(reason = if (p.reason == r) "UNKNOWN" else r, needsReview = false) } } },
                            { Text(reasonLabel(r), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { idx ->
        val p = points.getOrNull(idx) ?: run { editing = null; return }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.edit_point, idx + 1)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.point_winner))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(p.winnerA, { vm.edit { l -> l.toMutableList().also { it[idx] = p.copy(winnerA = true, needsReview = false, auto = false) } } }, { Text(session.nameA()) })
                        FilterChip(!p.winnerA, { vm.edit { l -> l.toMutableList().also { it[idx] = p.copy(winnerA = false, needsReview = false, auto = false) } } }, { Text(session.nameB()) })
                    }
                    Text(stringResource(R.string.point_reason))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        reasons.forEach { r -> FilterChip(p.reason == r, { vm.edit { l -> l.toMutableList().also { it[idx] = p.copy(reason = r, auto = false) } } }, { Text(reasonLabel(r)) }) }
                    }
                    Text(stringResource(R.string.serve))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to R.string.unknown, 1 to R.string.first_serve, 2 to R.string.second_serve).forEach { (v, l) ->
                            FilterChip(p.serveNumber == v, { vm.edit { lst -> lst.toMutableList().also { it[idx] = p.copy(serveNumber = v) } } }, { Text(stringResource(l)) })
                        }
                    }
                    Text(stringResource(R.string.server_correction))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(p.serverOverrideA == true, { vm.edit { l -> l.toMutableList().also { it[idx] = p.copy(serverOverrideA = if (p.serverOverrideA == true) null else true) } } }, { Text(session.nameA()) })
                        FilterChip(p.serverOverrideA == false, { vm.edit { l -> l.toMutableList().also { it[idx] = p.copy(serverOverrideA = if (p.serverOverrideA == false) null else false) } } }, { Text(session.nameB()) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (p.needsReview) TextButton(onClick = { vm.edit { l -> l.toMutableList().also { it[idx] = p.copy(needsReview = false, auto = false) } } }) { Text(stringResource(R.string.mark_reviewed)) }
                        TextButton(onClick = { vm.edit { l -> l.toMutableList().also { it.add(idx, PointEntity(sessionId = session.id, order = idx, winnerA = true, startMs = p.startMs)) } } }) { Text(stringResource(R.string.insert_before)) }
                        TextButton(onClick = { vm.edit(R.string.deleted) { l -> l.toMutableList().also { it.removeAt(idx) } }; editing = null }) { Text(stringResource(R.string.delete)) }
                        p.startMs?.let { st -> TextButton(onClick = { vm.requestSeek(st); editing = null }) { Text(stringResource(R.string.watch) + " " + formatDuration(st)) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
fun ScoreLine(name: String, sets: List<Int>, current: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        sets.forEach { Text("$it", Modifier.width(28.dp), fontSize = 20.sp) }
        Text(current, Modifier.width(56.dp), fontSize = 22.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
    }
}
