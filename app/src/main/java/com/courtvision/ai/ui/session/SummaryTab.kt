package com.courtvision.ai.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.courtvision.ai.R
import com.courtvision.ai.data.AnalysisState
import com.courtvision.ai.data.court
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.data.sport
import com.courtvision.ai.data.tennisRules
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.StatRow
import com.courtvision.ai.ui.Unsure
import com.courtvision.ai.util.formatNum
import com.courtvision.analysis.StatisticsCalculator
import com.courtvision.scoring.Side

/** First tab: score and the key statistics, with the full tables one tap away. */
@Composable
fun SummaryTab(vm: SessionViewModel, onOpenPoints: () -> Unit, onOpenVideo: () -> Unit) {
    val s by vm.session.collectAsState()
    val points by vm.points.collectAsState()
    val shots by vm.shots.collectAsState()
    val rallies by vm.rallies.collectAsState()
    var full by remember { mutableStateOf(false) }
    val session = s ?: return
    val stats = StatisticsCalculator.compute(
        session.sport(), session.court(), session.tennisRules(), if (session.firstServerA) Side.A else Side.B,
        points.map { it.toRecord() }, shots.map { it.toRecord() }, rallies.map { it.toRecord() },
    )
    val a = stats.a; val b = stats.b
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (session.analysisState != AnalysisState.DONE) item { AnalysisCard(vm, 0) }
        if (session.mode.isMatch) item {
            val board = vm.board(points)
            SectionCard(stringResource(R.string.tab_score)) {
                if (board != null) {
                    ScoreLine((if (board.server == Side.A) "● " else "") + session.nameA(), board.setsOrGamesA, board.currentA)
                    ScoreLine((if (board.server == Side.B) "● " else "") + session.nameB(), board.setsOrGamesB, board.currentB)
                }
                if (points.isEmpty()) Text(stringResource(R.string.no_points), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (session.mode.isMatch) item {
            SectionCard(stringResource(R.string.key_stats)) {
                StatRow("", session.nameA(), session.nameB())
                StatRow(stringResource(R.string.points_won), "${a.pointsWon}", "${b.pointsWon}")
                StatRow(stringResource(R.string.reason_winner), "${a.winners}", "${b.winners}")
                StatRow(stringResource(R.string.reason_unforced), "${a.unforcedErrors}", "${b.unforcedErrors}")
                StatRow(stringResource(R.string.reason_forced), "${a.forcedErrors}", "${b.forcedErrors}")
                StatRow(stringResource(R.string.reason_ace), "${a.aces}", "${b.aces}")
                StatRow(stringResource(R.string.reason_double_fault), "${a.doubleFaults}", "${b.doubleFaults}")
                StatRow(stringResource(R.string.first_serve_in), ratioText(a.firstServeIn), ratioText(b.firstServeIn))
                StatRow(stringResource(R.string.break_points), ratioText(a.breakPoints), ratioText(b.breakPoints))
                val unclassified = points.count { it.reason == "UNKNOWN" }
                if (unclassified > 0) {
                    Text(stringResource(R.string.unclassified_points, unclassified), color = Unsure, style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = onOpenPoints) { Text(stringResource(R.string.classify_points)) }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.shots_title)) {
                StatRow(stringResource(R.string.total_shots), "${a.shots}", if (session.mode.isMatch) "${b.shots}" else null)
                StatRow(stringResource(R.string.accuracy), ratioText(a.accuracy), if (session.mode.isMatch) ratioText(b.accuracy) else null)
                StatRow(stringResource(R.string.max_speed), formatNum(a.maxSpeedKmh), if (session.mode.isMatch) formatNum(b.maxSpeedKmh) else null)
                StatRow(stringResource(R.string.longest_rally), stats.longestRallyShots?.toString() ?: "—")
                if (shots.isEmpty()) Text(stringResource(R.string.shots_need_analysis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedButton(onClick = { full = !full }) { Text(stringResource(if (full) R.string.hide_full_stats else R.string.show_full_stats)) }
        }
        if (full) item { StatsTables(stats, session.nameA(), session.nameB(), session.mode.isMatch) }
        item { OutlinedButton(onClick = onOpenVideo) { Text(stringResource(R.string.watch_video), fontWeight = FontWeight.SemiBold) } }
    }
}
