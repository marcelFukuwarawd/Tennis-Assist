package com.courtvision.ai.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.courtvision.ai.R
import com.courtvision.ai.data.court
import com.courtvision.ai.data.nameA
import com.courtvision.ai.data.nameB
import com.courtvision.ai.data.sport
import com.courtvision.ai.data.tennisRules
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.InGreen
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.Unsure
import com.courtvision.ai.util.formatDuration
import com.courtvision.ai.util.formatPct
import com.courtvision.analysis.Coach
import com.courtvision.analysis.Drill
import com.courtvision.analysis.Insight
import com.courtvision.analysis.InsightKind
import com.courtvision.analysis.StatisticsCalculator
import com.courtvision.scoring.Side

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoachTab(vm: SessionViewModel) {
    val s by vm.session.collectAsState()
    val points by vm.points.collectAsState()
    val shots by vm.shots.collectAsState()
    val rallies by vm.rallies.collectAsState()
    val session = s ?: return
    val shotRecs = shots.map { it.toRecord() }
    val stats = StatisticsCalculator.compute(session.sport(), session.court(), session.tennisRules(), if (session.firstServerA) Side.A else Side.B, points.map { it.toRecord() }, shotRecs, rallies.map { it.toRecord() })
    val coach = Coach(session.court())
    val sides = if (session.mode.isMatch) listOf(Side.A, Side.B) else listOf(Side.A)
    val insights = sides.flatMap { side -> coach.analyse(side, if (side == Side.A) stats.a else stats.b, if (session.mode.isMatch) shotRecs else shotRecs.map { it.copy(side = Side.A) }) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.coach_info), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (insights.isEmpty()) item { EmptyHint(stringResource(R.string.coach_no_data)) }
        items(insights) { ins ->
            SectionCard((if (ins.side == Side.A) session.nameA() else session.nameB())) {
                Text(insightText(ins), color = if (ins.positive) InGreen else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.sample_size, ins.sampleSize), style = MaterialTheme.typography.labelSmall)
                if (ins.drills.isNotEmpty()) {
                    Text(stringResource(R.string.suggested_drills), style = MaterialTheme.typography.labelLarge, color = Unsure)
                    ins.drills.forEach { Text("• " + drillText(it), style = MaterialTheme.typography.bodySmall) }
                }
                if (ins.evidenceMs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ins.evidenceMs.forEach { t -> OutlinedButton(onClick = { vm.requestSeek(t - 1500) }) { Text("▶ " + formatDuration(t)) } }
                }
            }
        }
    }
}

@Composable
fun insightText(i: Insight): String {
    val v = i.values
    return when (i.kind) {
        InsightKind.BACKHAND_LESS_ACCURATE -> stringResource(R.string.ins_backhand, formatPct(v["backhand"]), formatPct(v["forehand"]))
        InsightKind.FOREHAND_LESS_ACCURATE -> stringResource(R.string.ins_forehand, formatPct(v["forehand"]), formatPct(v["backhand"]))
        InsightKind.LOW_FIRST_SERVE -> stringResource(R.string.ins_first_serve, formatPct(v["first_serve_in"]))
        InsightKind.DOUBLE_FAULTS -> stringResource(R.string.ins_double_faults, v["double_faults"]?.toInt() ?: 0, v["service_points"]?.toInt() ?: 0)
        InsightKind.SECOND_SERVE_CENTRAL -> stringResource(R.string.ins_serve_central, formatPct(v["central_ratio"]))
        InsightKind.CONSISTENCY_DROP -> stringResource(R.string.ins_consistency_drop, formatPct(v["first_half"]), formatPct(v["second_half"]))
        InsightKind.BREAK_POINTS_MISSED -> stringResource(R.string.ins_break_points, formatPct(v["break_points_won"]), v["break_points"]?.toInt() ?: 0)
        InsightKind.STRONG_SERVE -> stringResource(R.string.ins_strong_serve, formatPct(v["service_points_won"]))
        InsightKind.STRONG_RETURN -> stringResource(R.string.ins_strong_return, formatPct(v["return_points_won"]))
        InsightKind.LOW_DEPTH -> stringResource(R.string.ins_low_depth, formatPct(v["deep_ratio"]))
        InsightKind.GOOD_CONSISTENCY -> stringResource(R.string.ins_good_consistency, formatPct(v["first_half"]), formatPct(v["second_half"]))
        InsightKind.SHORT_BALLS -> stringResource(R.string.ins_low_depth, formatPct(v["deep_ratio"]))
    }
}

@Composable
fun drillText(d: Drill): String = stringResource(
    when (d) {
        Drill.CROSSCOURT_BACKHAND -> R.string.drill_cc_backhand
        Drill.CROSSCOURT_FOREHAND -> R.string.drill_cc_forehand
        Drill.SERVE_TARGETS -> R.string.drill_serve_targets
        Drill.SECOND_SERVE_KICK -> R.string.drill_second_serve
        Drill.DEPTH_TARGETS -> R.string.drill_depth
        Drill.ENDURANCE_RALLY -> R.string.drill_endurance
        Drill.PRESSURE_POINTS -> R.string.drill_pressure
        Drill.CONSISTENCY_20 -> R.string.drill_consistency
    },
)
