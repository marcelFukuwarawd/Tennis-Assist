package com.courtvision.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.AppJson
import com.courtvision.ai.data.RulesConfig
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.SessionMode
import com.courtvision.ai.data.SessionStatus
import com.courtvision.ai.ui.Routes
import com.courtvision.ai.ui.SectionCard
import com.courtvision.ai.ui.app
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val matchModes = listOf(SessionMode.TENNIS_SINGLES, SessionMode.TENNIS_DOUBLES, SessionMode.PICKLEBALL_SINGLES, SessionMode.PICKLEBALL_DOUBLES)
private val trainingModes = listOf(
    SessionMode.TRAINING_SOLO, SessionMode.TRAINING_PARTNER, SessionMode.TRAINING_SERVE, SessionMode.TRAINING_MACHINE,
    SessionMode.TRAINING_PRECISION, SessionMode.TECHNIQUE,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(nav: NavHostController, kind: String) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var training by rememberSaveable { mutableStateOf(kind == "training") }
    var mode by rememberSaveable { mutableStateOf(if (kind == "training") SessionMode.TRAINING_SOLO else SessionMode.TENNIS_SINGLES) }
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    var a2 by rememberSaveable { mutableStateOf("") }
    var b2 by rememberSaveable { mutableStateOf("") }
    var bestOf by rememberSaveable { mutableStateOf(3) }
    var noAd by rememberSaveable { mutableStateOf(false) }
    var matchTb by rememberSaveable { mutableStateOf(false) }
    var shortSets by rememberSaveable { mutableStateOf(false) }
    var pbRally by rememberSaveable { mutableStateOf(false) }
    var pbPoints by rememberSaveable { mutableStateOf(11) }
    var pbGames by rememberSaveable { mutableStateOf(1) }
    var firstA by rememberSaveable { mutableStateOf(true) }
    var surface by rememberSaveable { mutableStateOf("hard") }
    var leftyA by rememberSaveable { mutableStateOf(false) }

    fun start() = scope.launch {
        val rules = RulesConfig(
            bestOf = bestOf, gamesPerSet = if (shortSets) 4 else 6, noAd = noAd, finalSetMatchTiebreak = matchTb,
            pickleballRally = pbRally, pickleballPoints = pbPoints, pickleballGames = pbGames,
        )
        val title = context.getString(if (mode.isMatch) R.string.match else R.string.training) + " " +
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
        val id = app.repo.createSession(
            SessionEntity(
                title = title, mode = mode, createdAt = System.currentTimeMillis(),
                playerA = a.trim(), playerB = b.trim(), playerA2 = a2.trim(), playerB2 = b2.trim(),
                rulesJson = AppJson.encodeToString(RulesConfig.serializer(), rules), firstServerA = firstA,
                status = SessionStatus.RECORDING, surface = surface, rightHandedA = !leftyA,
            ),
        )
        nav.navigate(Routes.camera(id))
    }

    var more by rememberSaveable { mutableStateOf(false) }
    val pickle = mode.isPickleball
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (training) R.string.new_training else R.string.new_match), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!training) {
            SectionCard {
                Text(stringResource(R.string.sport), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!pickle, { mode = if (mode.isDoubles) SessionMode.TENNIS_DOUBLES else SessionMode.TENNIS_SINGLES }, { Text(stringResource(R.string.filter_tennis)) })
                    FilterChip(pickle, { mode = if (mode.isDoubles) SessionMode.PICKLEBALL_DOUBLES else SessionMode.PICKLEBALL_SINGLES }, { Text(stringResource(R.string.filter_pickleball)) })
                }
                Text(stringResource(R.string.format), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!mode.isDoubles, { mode = if (pickle) SessionMode.PICKLEBALL_SINGLES else SessionMode.TENNIS_SINGLES }, { Text(stringResource(R.string.singles)) })
                    FilterChip(mode.isDoubles, { mode = if (pickle) SessionMode.PICKLEBALL_DOUBLES else SessionMode.TENNIS_DOUBLES }, { Text(stringResource(R.string.doubles)) })
                }
            }
        } else {
            SectionCard(stringResource(R.string.training_type)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trainingModes.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(modeLabel(m)) }) }
                }
            }
        }
        SectionCard(stringResource(R.string.players_optional)) {
            OutlinedTextField(a, { a = it }, label = { Text(stringResource(R.string.player_a)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (mode.isDoubles) OutlinedTextField(a2, { a2 = it }, label = { Text(stringResource(R.string.player_a2)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!training || mode == SessionMode.TRAINING_PARTNER) {
                OutlinedTextField(b, { b = it }, label = { Text(stringResource(R.string.player_b)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (mode.isDoubles) OutlinedTextField(b2, { b2 = it }, label = { Text(stringResource(R.string.player_b2)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        Button(onClick = { start() }, Modifier.fillMaxWidth().height(68.dp)) { Text(stringResource(R.string.start_recording), style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = { more = !more }) { Text(stringResource(if (more) R.string.fewer_options else R.string.more_options)) }
        if (more) {
            if (mode.isMatch) SectionCard(stringResource(R.string.rules)) {
                if (!pickle) {
                    Text(stringResource(R.string.best_of))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 3, 5).forEach { n -> FilterChip(bestOf == n, { bestOf = n }, { Text("$n") }) } }
                    ToggleRow(stringResource(R.string.no_ad), noAd) { noAd = it }
                    ToggleRow(stringResource(R.string.match_tiebreak), matchTb) { matchTb = it }
                    ToggleRow(stringResource(R.string.short_sets), shortSets) { shortSets = it }
                } else {
                    ToggleRow(stringResource(R.string.rally_scoring), pbRally) { pbRally = it }
                    Text(stringResource(R.string.points_to_win))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(11, 15, 21).forEach { n -> FilterChip(pbPoints == n, { pbPoints = n }, { Text("$n") }) } }
                    Text(stringResource(R.string.games))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 3, 5).forEach { n -> FilterChip(pbGames == n, { pbGames = n }, { Text("$n") }) } }
                }
                Text(stringResource(R.string.first_server))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(firstA, { firstA = true }, { Text(a.ifBlank { "A" }) })
                    FilterChip(!firstA, { firstA = false }, { Text(b.ifBlank { "B" }) })
                }
            }
            SectionCard(stringResource(R.string.surface)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("clay" to R.string.surface_clay, "hard" to R.string.surface_hard, "grass" to R.string.surface_grass, "synthetic" to R.string.surface_synthetic, "indoor" to R.string.surface_indoor)
                        .forEach { (k, l) -> FilterChip(surface == k, { surface = k }, { Text(stringResource(l)) }) }
                }
                ToggleRow(stringResource(R.string.left_handed), leftyA) { leftyA = it }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
