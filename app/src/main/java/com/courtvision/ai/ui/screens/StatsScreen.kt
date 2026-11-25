package com.courtvision.ai.ui.screens

import com.courtvision.ai.util.Feedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.GoalEntity
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.ShotEntity
import com.courtvision.ai.ui.BigStat
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.StatRow
import com.courtvision.ai.ui.app
import com.courtvision.ai.util.formatNum
import com.courtvision.ai.util.formatPct
import kotlinx.coroutines.launch

private const val WEEK = 7L * 24 * 3600 * 1000

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(nav: NavHostController) {
    val app = app()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions by app.db.sessions().observeAll().collectAsState(initial = emptyList())
    val attempts by app.db.training().observeAllAttempts().collectAsState(initial = emptyList())
    val goals by app.db.training().observeGoals().collectAsState(initial = emptyList())
    var shots by remember { mutableStateOf<List<ShotEntity>>(emptyList()) }
    var points by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    LaunchedEffect(sessions) { shots = app.db.analysis().allShots(); points = app.db.points().all() }
    val created = sessions.associate { it.id to it.createdAt }
    val now = System.currentTimeMillis()

    fun inPct(l: List<ShotEntity>) = l.filter { it.sideA != false && (it.call == "IN" || it.call == "OUT") }.let { c -> if (c.isEmpty()) null else c.count { it.call == "IN" }.toDouble() / c.size }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.tab_stats), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            SectionCard(stringResource(R.string.all_time)) {
                val mine = shots.filter { it.sideA != false }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BigStat("${sessions.size}", stringResource(R.string.sessions))
                    BigStat("${mine.size}", stringResource(R.string.shots))
                    BigStat(formatPct(inPct(shots)), stringResource(R.string.in_pct))
                }
                StatRow(stringResource(R.string.shot_forehand), "${mine.count { it.type == "FOREHAND" }}", formatPct(inPct(mine.filter { it.type == "FOREHAND" })))
                StatRow(stringResource(R.string.shot_backhand), "${mine.count { it.type == "BACKHAND" }}", formatPct(inPct(mine.filter { it.type == "BACKHAND" })))
                StatRow(stringResource(R.string.shot_serve), "${mine.count { it.type == "SERVE" }}", formatPct(inPct(mine.filter { it.type == "SERVE" })))
                StatRow(stringResource(R.string.max_speed), formatNum(mine.mapNotNull { it.speedKmh }.maxOrNull()))
                StatRow(stringResource(R.string.points_won), "${points.count { it.winnerA }}", "${points.size}")
            }
        }
        item {
            SectionCard(stringResource(R.string.evolution)) {
                val series = shots.groupBy { it.sessionId }.toList().sortedBy { created[it.first] ?: 0 }.mapNotNull { (_, l) -> inPct(l)?.takeIf { l.size >= 5 }?.toFloat() }.takeLast(20)
                if (series.size < 2) EmptyHint(stringResource(R.string.evolution_hint)) else LineChart(series, MaterialTheme.colorScheme.primary)
            }
        }
        item {
            SectionCard(stringResource(R.string.precision_training)) {
                val valid = attempts.filter { it.result != "INCONCLUSIVE" }
                val hit = valid.count { it.result == "HIT" }
                StatRow(stringResource(R.string.hit_rate), formatPct(if (valid.isEmpty()) null else hit.toDouble() / valid.size), "${valid.size}")
                StatRow(stringResource(R.string.voice_short), "${valid.count { it.result == "SHORT" }}")
                StatRow(stringResource(R.string.voice_long), "${valid.count { it.result == "LONG" }}")
                StatRow(stringResource(R.string.voice_out), "${valid.count { it.result == "OUT" }}")
                val bySession = valid.groupBy { it.sessionId }.toList().sortedBy { created[it.first] ?: 0 }.map { (_, l) -> l.count { it.result == "HIT" }.toFloat() / l.size }
                if (bySession.size >= 2) LineChart(bySession, MaterialTheme.colorScheme.secondary)
                OutlinedButton(onClick = { nav.navigate("zones") }) { Text(stringResource(R.string.edit_targets)) }
            }
        }
        item {
            SectionCard(stringResource(R.string.weekly_goals)) {
                if (goals.isEmpty()) EmptyHint(stringResource(R.string.no_goals))
                goals.forEach { g ->
                    val weekSessions = sessions.filter { now - it.createdAt < WEEK }.map { it.id }.toSet()
                    val weekShots = shots.filter { it.sessionId in weekSessions }
                    val weekAttempts = attempts.filter { it.sessionId in weekSessions && it.result != "INCONCLUSIVE" }
                    val current: Double? = when (g.metric) {
                        "SESSIONS" -> weekSessions.size.toDouble()
                        "IN_PCT" -> inPct(weekShots)?.times(100)
                        "PRECISION" -> if (weekAttempts.isEmpty()) null else weekAttempts.count { it.result == "HIT" } * 100.0 / weekAttempts.size
                        "SHOTS" -> weekShots.count { it.sideA != false }.toDouble()
                        else -> null
                    }
                    Text(goalLabel(g.metric) + ": " + formatNum(current) + " / " + formatNum(g.target), fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { ((current ?: 0.0) / g.target).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    TextButton(onClick = { scope.launch { app.db.training().updateGoal(g.copy(active = false)); Feedback.show(context, R.string.deleted) } }) { Text(stringResource(R.string.remove_goal)) }
                }
                var metric by remember { mutableStateOf("SESSIONS") }
                var target by remember { mutableFloatStateOf(3f) }
                Text(stringResource(R.string.new_goal), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("SESSIONS", "SHOTS", "IN_PCT", "PRECISION").forEach { m -> FilterChip(metric == m, { metric = m; target = if (m == "SESSIONS") 3f else if (m == "SHOTS") 500f else 70f }, { Text(goalLabel(m)) }) }
                }
                val range = when (metric) { "SESSIONS" -> 1f..14f; "SHOTS" -> 50f..3000f; else -> 10f..100f }
                Text(formatNum(target.toDouble()))
                Slider(target, { target = it }, valueRange = range)
                Button(onClick = { scope.launch { app.db.training().insertGoal(GoalEntity(metric = metric, target = target.toDouble(), createdAt = now)); Feedback.show(context) } }) { Text(stringResource(R.string.add_goal)) }
            }
        }
    }
}

@Composable
fun goalLabel(m: String) = stringResource(when (m) { "SESSIONS" -> R.string.goal_sessions; "SHOTS" -> R.string.goal_shots; "IN_PCT" -> R.string.goal_in_pct; else -> R.string.goal_precision })

@Composable
fun LineChart(values: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 6.dp)) {
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        drawLine(color.copy(alpha = 0.2f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        for (i in 1 until values.size) drawLine(color, Offset((i - 1) * step, size.height * (1 - values[i - 1])), Offset(i * step, size.height * (1 - values[i])), 4f)
        values.forEachIndexed { i, v -> drawCircle(color, 6f, Offset(i * step, size.height * (1 - v))) }
    }
}
