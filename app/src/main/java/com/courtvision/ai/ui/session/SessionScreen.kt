package com.courtvision.ai.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.ui.app
import com.courtvision.ai.ui.session.CoachTab
import com.courtvision.ai.ui.session.MapTab
import com.courtvision.ai.ui.session.MoreTab
import com.courtvision.ai.ui.session.ScoreTab
import com.courtvision.ai.ui.session.SessionStatsTab
import com.courtvision.ai.ui.session.SessionViewModel
import com.courtvision.ai.ui.session.VideoTab

@Composable
fun SessionScreen(nav: NavHostController, id: Long, t: Long) {
    val app = app()
    val vm: SessionViewModel = viewModel(key = "session_$id", factory = SessionViewModel.Factory(app, id))
    val session by vm.session.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(t) { if (t >= 0) { vm.requestSeek(t - 1500); tab = 0 } }
    val seek by vm.seek.collectAsState()
    LaunchedEffect(seek) { if (seek != null) tab = 0 }
    val s = session ?: return
    val titles = listOf(R.string.tab_video, R.string.tab_score, R.string.tab_stats, R.string.tab_map, R.string.tab_coach, R.string.tab_more)
    Column(Modifier.fillMaxSize()) {
        Text(s.title, Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(modeLabel(s.mode), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            titles.forEachIndexed { i, r -> Tab(tab == i, { tab = i }, text = { Text(stringResource(r)) }) }
        }
        when (tab) {
            0 -> VideoTab(vm)
            1 -> ScoreTab(vm)
            2 -> SessionStatsTab(vm)
            3 -> MapTab(vm)
            4 -> CoachTab(vm)
            else -> MoreTab(vm, nav)
        }
    }
}
