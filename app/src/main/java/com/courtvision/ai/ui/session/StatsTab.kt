package com.courtvision.ai.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.courtvision.ai.R
import com.courtvision.ai.data.court
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.data.sport
import com.courtvision.ai.data.tennisRules
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.StatRow
import com.courtvision.ai.ui.screens.shotLabel
import com.courtvision.ai.util.formatNum
import com.courtvision.ai.util.formatPct
import com.courtvision.analysis.MatchStats
import com.courtvision.analysis.Ratio
import com.courtvision.analysis.StatisticsCalculator
import com.courtvision.scoring.Side
import com.courtvision.scoring.Sport
import com.courtvision.scoring.TennisEngine

fun ratioText(r: Ratio) = if (r.total == 0) "—" else "${formatPct(r.value)} (${r.hits}/${r.total})"

@Composable
fun SessionStatsTab(vm: SessionViewModel) {
    val s by vm.session.collectAsState()
    val points by vm.points.collectAsState()
    val shots by vm.shots.collectAsState()
    val rallies by vm.rallies.collectAsState()
    val session = s ?: return
    var setFilter by remember { mutableIntStateOf(-1) }
    // set boundaries in time, from the score engine
    val setOf = remember(points) {
        if (session.sport() != Sport.TENNIS) emptyList() else
            TennisEngine(session.tennisRules()).replay(if (session.firstServerA) Side.A else Side.B, points.toScoringEvents()).second.map { it.setIndex }
    }
    val sets = (setOf.maxOrNull() ?: -1) + 1
    val rallyPts = points.filter { p -> setFilter < 0 || setOf.getOrNull(points.indexOf(p)) == setFilter }
    val range = if (setFilter < 0) null else rallyPts.mapNotNull { it.startMs }.minOrNull()?.let { a -> a..(rallyPts.mapNotNull { it.endMs }.maxOrNull() ?: Long.MAX_VALUE) }
    val shotRecs = shots.map { it.toRecord() }.filter { range == null || it.timeMs in range }
    val stats = StatisticsCalculator.compute(
        session.sport(), session.court(), session.tennisRules(), if (session.firstServerA) Side.A else Side.B,
        points.map { it.toRecord() }, shotRecs,
        rallies.map { it.toRecord() }.filter { range == null || it.startMs in range },
    )
    // point counters for a single set are recomputed on that subset (serve context needs the whole match)
    val filteredStats = if (setFilter < 0) stats else {
        val sub = StatisticsCalculator.compute(
            session.sport(), session.court(), session.tennisRules(), if (session.firstServerA) Side.A else Side.B,
            rallyPts.map { it.toRecord() }, shotRecs, rallies.map { it.toRecord() }.filter { range == null || it.startMs in range },
        )
        sub.copy(
            a = sub.a.copy(firstServeIn = Ratio(0, 0), firstServeWon = Ratio(0, 0), secondServeWon = Ratio(0, 0), servicePointsWon = Ratio(0, 0), returnPointsWon = Ratio(0, 0), breakPoints = Ratio(0, 0)),
            b = sub.b.copy(firstServeIn = Ratio(0, 0), firstServeWon = Ratio(0, 0), secondServeWon = Ratio(0, 0), servicePointsWon = Ratio(0, 0), returnPointsWon = Ratio(0, 0), breakPoints = Ratio(0, 0)),
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sets > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(setFilter < 0, { setFilter = -1 }, { Text(stringResource(R.string.whole_match)) }) }
                items((0 until sets).toList()) { i -> FilterChip(setFilter == i, { setFilter = i }, { Text(stringResource(R.string.set_n, i + 1)) }) }
            }
        }
        item { StatsTables(filteredStats, session.nameA(), session.nameB(), session.mode.isMatch) }
    }
}

@Composable
fun StatsTables(stats: MatchStats, nameA: String, nameB: String, match: Boolean) {
    val a = stats.a; val b = stats.b
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard {
            StatRow("", nameA, if (match) nameB else null)
        }
        if (match) SectionCard(stringResource(R.string.points)) {
            StatRow(stringResource(R.string.points_won), "${a.pointsWon}", "${b.pointsWon}")
            StatRow(stringResource(R.string.reason_ace), "${a.aces}", "${b.aces}")
            StatRow(stringResource(R.string.reason_double_fault), "${a.doubleFaults}", "${b.doubleFaults}")
            StatRow(stringResource(R.string.reason_winner), "${a.winners}", "${b.winners}")
            StatRow(stringResource(R.string.reason_forced), "${a.forcedErrors}", "${b.forcedErrors}")
            StatRow(stringResource(R.string.reason_unforced), "${a.unforcedErrors}", "${b.unforcedErrors}")
            StatRow(stringResource(R.string.first_serve_in), ratioText(a.firstServeIn), ratioText(b.firstServeIn))
            StatRow(stringResource(R.string.first_serve_won), ratioText(a.firstServeWon), ratioText(b.firstServeWon))
            StatRow(stringResource(R.string.second_serve_won), ratioText(a.secondServeWon), ratioText(b.secondServeWon))
            StatRow(stringResource(R.string.service_points_won), ratioText(a.servicePointsWon), ratioText(b.servicePointsWon))
            StatRow(stringResource(R.string.return_points_won), ratioText(a.returnPointsWon), ratioText(b.returnPointsWon))
            StatRow(stringResource(R.string.break_points), ratioText(a.breakPoints), ratioText(b.breakPoints))
            if (stats.pendingReview > 0) Text(stringResource(R.string.pending_review, stats.pendingReview), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.subjective_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard(stringResource(R.string.shots_title)) {
            StatRow(stringResource(R.string.total_shots), "${a.shots}", if (match) "${b.shots}" else null)
            StatRow(stringResource(R.string.accuracy), ratioText(a.accuracy), if (match) ratioText(b.accuracy) else null)
            StatRow(stringResource(R.string.depth), ratioText(a.deepShots), if (match) ratioText(b.deepShots) else null)
            StatRow(stringResource(R.string.max_speed), formatNum(a.maxSpeedKmh), if (match) formatNum(b.maxSpeedKmh) else null)
            StatRow(stringResource(R.string.avg_speed), formatNum(a.avgSpeedKmh), if (match) formatNum(b.avgSpeedKmh) else null)
            StatRow(stringResource(R.string.serve_speed), formatNum(a.serveSpeedMaxKmh), if (match) formatNum(b.serveSpeedMaxKmh) else null)
            Text(stringResource(R.string.speed_info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val types = (a.shotsByType.keys + b.shotsByType.keys).distinct().sortedBy { it.ordinal }
            val maxCount = (a.shotsByType.values + b.shotsByType.values).maxOrNull() ?: 1
            types.forEach { t -> ShotBar(shotLabel(t.name), a.shotsByType[t] ?: 0, if (match) b.shotsByType[t] ?: 0 else null, maxCount, a.accuracyByType[t]) }
        }
        SectionCard(stringResource(R.string.rallies)) {
            StatRow(stringResource(R.string.rally_count), "${stats.rallies}")
            StatRow(stringResource(R.string.avg_rally), formatNum(stats.avgRallyShots, 1))
            StatRow(stringResource(R.string.longest_rally), stats.longestRallyShots?.toString() ?: "—")
            StatRow(stringResource(R.string.avg_rally_sec), formatNum(stats.avgRallySeconds, 1))
        }
    }
}

@Composable
private fun ShotBar(label: String, a: Int, b: Int?, max: Int, acc: Ratio?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label + (acc?.takeIf { it.total > 0 }?.let { "  · " + formatPct(it.value) } ?: ""), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(a / max.toFloat() * if (b != null) 0.5f else 1f).background(MaterialTheme.colorScheme.primary))
            if (b != null) {
                Box(Modifier.width(4.dp))
                Box(Modifier.fillMaxHeight().fillMaxWidth((b / max.toFloat() * 0.5f).coerceAtMost(0.99f)).background(MaterialTheme.colorScheme.secondary))
            }
        }
        Text("$a" + (b?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
    }
}

