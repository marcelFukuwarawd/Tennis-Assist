package com.courtvision.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.courtvision.ai.R
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.ui.EmptyHint
import com.courtvision.ai.ui.Routes
import com.courtvision.ai.ui.app
import kotlinx.coroutines.launch

private enum class Sort { DATE, NAME, MODE, PLAYER }
private enum class Filter { ALL, MATCHES, TRAINING, TENNIS, PICKLEBALL, FAVORITES }

@Composable
fun MatchesScreen(nav: NavHostController) {
    val app = app()
    val scope = rememberCoroutineScope()
    val sessions by app.db.sessions().observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.DATE) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var renaming by remember { mutableStateOf<SessionEntity?>(null) }
    var deleting by remember { mutableStateOf<SessionEntity?>(null) }

    val list = sessions.filter { s ->
        val q = query.trim().lowercase()
        (q.isEmpty() || listOf(s.title, s.playerA, s.playerB, s.playerA2, s.playerB2, s.notes).any { it.lowercase().contains(q) }) &&
            when (filter) {
                Filter.ALL -> true
                Filter.MATCHES -> s.mode.isMatch
                Filter.TRAINING -> !s.mode.isMatch
                Filter.TENNIS -> !s.mode.isPickleball
                Filter.PICKLEBALL -> s.mode.isPickleball
                Filter.FAVORITES -> s.favorite
            }
    }.let { l ->
        when (sort) {
            Sort.DATE -> l.sortedByDescending { it.createdAt }
            Sort.NAME -> l.sortedBy { it.title.lowercase() }
            Sort.MODE -> l.sortedBy { it.mode.ordinal }
            Sort.PLAYER -> l.sortedBy { (it.playerB.ifBlank { it.playerA }).lowercase() }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp), leadingIcon = { Icon(Icons.Filled.Search, null) }, label = { Text(stringResource(R.string.search)) }, singleLine = true)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            items(Filter.entries.toList()) { f ->
                FilterChip(filter == f, { filter = f }, {
                    Text(stringResource(when (f) { Filter.ALL -> R.string.filter_all; Filter.MATCHES -> R.string.filter_matches; Filter.TRAINING -> R.string.filter_training; Filter.TENNIS -> R.string.filter_tennis; Filter.PICKLEBALL -> R.string.filter_pickleball; Filter.FAVORITES -> R.string.filter_favorites }))
                })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Sort.entries.toList()) { s ->
                FilterChip(sort == s, { sort = s }, { Text(stringResource(when (s) { Sort.DATE -> R.string.sort_date; Sort.NAME -> R.string.sort_name; Sort.MODE -> R.string.sort_mode; Sort.PLAYER -> R.string.sort_player })) })
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            if (list.isEmpty()) item { EmptyHint(stringResource(R.string.no_sessions)) }
            items(list, key = { it.id }) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { SessionRow(s) { nav.navigate(Routes.session(s.id)) } }
                    IconButton(onClick = { scope.launch { app.db.sessions().update(s.copy(favorite = !s.favorite)) } }) {
                        Icon(if (s.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { renaming = s }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                    IconButton(onClick = { deleting = s }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
        }
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { app.db.sessions().update(s.copy(title = text.ifBlank { s.title })) }; renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_session_q), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(if (s.imported) R.string.delete_imported_info else R.string.delete_info)) },
            confirmButton = { TextButton(onClick = { scope.launch { app.repo.deleteSession(s) }; deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
