package com.courtvision.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackParameters
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.BallTrackEntity
import com.courtvision.ai.ui.ImmersiveMode
import com.courtvision.ai.ui.Lime
import com.courtvision.ai.ui.Routes
import com.courtvision.ai.ui.VideoSurface
import com.courtvision.ai.ui.app
import com.courtvision.ai.ui.rememberSessionPlayer
import com.courtvision.ai.ui.seekSession
import com.courtvision.ai.ui.session.CoachTab
import com.courtvision.ai.ui.session.MapTab
import com.courtvision.ai.ui.session.MoreTab
import com.courtvision.ai.ui.session.ScoreTab
import com.courtvision.ai.ui.session.SessionViewModel
import com.courtvision.ai.ui.session.SummaryTab
import com.courtvision.ai.ui.session.TrajectoryOverlay
import com.courtvision.ai.ui.session.VideoTab
import com.courtvision.ai.ui.sessionPosition
import kotlinx.coroutines.delay

@Composable
fun SessionScreen(nav: NavHostController, id: Long, t: Long) {
    val app = app()
    val vm: SessionViewModel = viewModel(key = "session_$id", factory = SessionViewModel.Factory(app, id))
    val session by vm.session.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(t) { if (t >= 0) { vm.requestSeek(t - 1500); tab = 1 } }
    val seek by vm.seek.collectAsState()
    LaunchedEffect(seek) { if (seek != null) tab = 1 }
    val s = session ?: return
    val titles = listOf(R.string.tab_summary, R.string.tab_video, R.string.tab_points, R.string.tab_more)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Column {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(modeLabel(s.mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, r -> Tab(tab == i, { tab = i }, text = { Text(stringResource(r), maxLines = 1) }) }
        }
        when (tab) {
            0 -> SummaryTab(vm, onOpenPoints = { tab = 2 }, onOpenVideo = { tab = 1 })
            1 -> VideoTab(vm) { ms -> nav.navigate(Routes.player(id, ms)) }
            2 -> ScoreTab(vm)
            else -> MoreTab(vm, nav)
        }
    }
}

enum class SubScreen { MAP, COACH }

/** Map and coach as their own screens; tapping a marker opens the full-screen player there. */
@Composable
fun SessionSubScreen(nav: NavHostController, id: Long, which: SubScreen) {
    val app = app()
    val vm: SessionViewModel = viewModel(key = "sub_${which}_$id", factory = SessionViewModel.Factory(app, id))
    val seek by vm.seek.collectAsState()
    LaunchedEffect(seek) { seek?.let { vm.consumeSeek(); nav.navigate(Routes.player(id, it)) } }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(if (which == SubScreen.MAP) R.string.tab_map else R.string.tab_coach), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        if (which == SubScreen.MAP) MapTab(vm) else CoachTab(vm)
    }
}

/** Full-screen landscape player with floating controls (speed, frame step, trajectory). */
@Composable
fun FullScreenPlayer(nav: NavHostController, id: Long, t: Long) {
    ImmersiveMode(landscape = true)
    val app = app()
    val vm: SessionViewModel = viewModel(key = "player_$id", factory = SessionViewModel.Factory(app, id))
    val s by vm.session.collectAsState()
    val segments by vm.segments.collectAsState()
    val bounces by vm.bounces.collectAsState()
    val player = rememberSessionPlayer(segments)
    var pos by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var showTrack by remember { mutableStateOf(true) }
    var window by remember { mutableStateOf<List<BallTrackEntity>>(emptyList()) }
    var range by remember { mutableStateOf(LongRange.EMPTY) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(segments) {
        if (segments.isEmpty() || started) return@LaunchedEffect
        started = true
        player.seekSession(segments, t.coerceAtLeast(0)); player.play()
    }
    LaunchedEffect(player, segments) {
        while (true) {
            pos = player.sessionPosition(segments)
            if (pos !in range) { range = (pos - 20_000)..(pos + 40_000); window = app.db.analysis().trackRange(id, range.first, range.last) }
            delay(40)
        }
    }
    val session = s ?: return
    val aspect = if (session.width > 0 && session.height > 0) session.width / session.height.toFloat() else 16f / 9f
    val frameMs = if (session.fps > 1) (1000 / session.fps).toLong() else 33L
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoSurface(player, Modifier, aspect) { if (showTrack) TrajectoryOverlay(window, bounces, pos) }
        Row(
            Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            IconButton(onClick = { player.pause(); player.seekSession(segments, pos - frameMs) }) { Icon(androidx.compose.material.icons.Icons.Filled.SkipPrevious, stringResource(R.string.frame_back), tint = Color.White) }
            IconButton(onClick = { player.pause(); player.seekSession(segments, pos + frameMs) }) { Icon(androidx.compose.material.icons.Icons.Filled.SkipNext, stringResource(R.string.frame_forward), tint = Color.White) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listOf(0.25f, 0.5f, 1f, 2f)) { v -> FilterChip(speed == v, { speed = v; player.playbackParameters = PlaybackParameters(v) }, { Text("${v}x", color = Color.White) }) }
                item { FilterChip(showTrack, { showTrack = !showTrack }, { Text(stringResource(R.string.show_trajectory), color = if (showTrack) Lime else Color.White) }) }
            }
        }
    }
}
