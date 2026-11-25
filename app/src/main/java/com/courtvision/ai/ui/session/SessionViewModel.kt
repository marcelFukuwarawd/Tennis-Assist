package com.courtvision.ai.ui.session

import com.courtvision.ai.util.Feedback
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.courtvision.ai.CourtVisionApp
import com.courtvision.ai.data.PointEntity
import com.courtvision.ai.data.SessionEntity
import com.courtvision.ai.data.toRecord
import com.courtvision.ai.data.toScoringEvents
import com.courtvision.ai.data.scoringSession
import com.courtvision.scoring.ScoreBoard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Session state + score editing. Every edit stores the full previous point list so undo/redo
 * is exact, and the score is always recomputed from scratch (no accumulated errors).
 * Editing points never touches the video files.
 */
class SessionViewModel(val app: CourtVisionApp, val id: Long) : ViewModel() {
    private val db = app.db
    val session: StateFlow<SessionEntity?> = db.sessions().observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val points = db.points().observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val shots = db.analysis().observeShots(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bounces = db.analysis().observeBounces(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rallies = db.analysis().observeRallies(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val segments = db.segments().observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val markers = db.edits().observeMarkers(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Requested video position (session ms); consumed by the video tab. */
    private val _seek = MutableStateFlow<Long?>(null)
    val seek = _seek.asStateFlow()
    fun requestSeek(ms: Long) { _seek.value = ms.coerceAtLeast(0) }
    fun consumeSeek() { _seek.value = null }

    private val undoStack = ArrayDeque<List<PointEntity>>()
    private val redoStack = ArrayDeque<List<PointEntity>>()
    private val _canUndo = MutableStateFlow(false); val canUndo = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false); val canRedo = _canRedo.asStateFlow()

    private fun refreshFlags() { _canUndo.value = undoStack.isNotEmpty(); _canRedo.value = redoStack.isNotEmpty() }

    fun edit(message: Int = com.courtvision.ai.R.string.saved, transform: (List<PointEntity>) -> List<PointEntity>) = viewModelScope.launch {
        val cur = db.points().forSession(id)
        val next = transform(cur)
        undoStack.addLast(cur); if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear(); refreshFlags()
        db.points().replaceAll(id, next)
        Feedback.show(app, message)
    }

    fun undo() = viewModelScope.launch {
        val prev = undoStack.removeLastOrNull() ?: return@launch
        redoStack.addLast(db.points().forSession(id)); refreshFlags()
        db.points().replaceAll(id, prev)
    }

    fun redo() = viewModelScope.launch {
        val next = redoStack.removeLastOrNull() ?: return@launch
        undoStack.addLast(db.points().forSession(id)); refreshFlags()
        db.points().replaceAll(id, next)
    }

    fun board(list: List<PointEntity>): ScoreBoard? = session.value?.scoringSession()?.board(list.toScoringEvents())

    fun update(s: SessionEntity) = viewModelScope.launch { db.sessions().update(s) }

    fun records() = points.value.map { it.toRecord() }

    class Factory(private val app: CourtVisionApp, private val id: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(app, id) as T
    }
}
