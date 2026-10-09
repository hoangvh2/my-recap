package com.vh.myrecap.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.core.ClipText
import com.vh.myrecap.core.ProviderConfig
import com.vh.myrecap.core.Providers
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.ShareText
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.recorder.RecordingService
import com.vh.myrecap.settings.AppSettings
import com.vh.myrecap.work.Processing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data class Clip(val id: String, val index: Int) : Screen
    data class Summary(val id: String, val jobId: String) : Screen
    data object Settings : Screen
}

/** Result of the Settings "test connection" button. */
data class ConnectionResult(val ok: Boolean, val message: String)

/** A folder with the text of each clip (by index) and of each summary (by job id). */
class FolderDetail(val session: Session, val clipText: Map<Int, String?>, val summaryText: Map<String, String?>)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyRecapApp
    private val store = app.store

    /** Folders deleted from the UI but kept on disk for a few seconds, so the user can undo. */
    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())

    val sessions: StateFlow<List<Session>> = combine(store.version, pendingDeletes) { _, hidden -> hidden }
        .map { hidden -> store.list().filterNot { it.id in hidden } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last folder hidden by [requestDelete]; the home screen offers Undo for it. */
    var lastDeleted by mutableStateOf<Session?>(null)
        private set

    val settings: StateFlow<AppSettings> = app.settings.settings
    val recorder = RecorderState.ui

    /** Incremented on every Activity resume so permission/battery checks refresh. */
    private val _resumeTick = MutableStateFlow(0)
    val resumeTick: StateFlow<Int> = _resumeTick

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    fun onResume() {
        _resumeTick.value++
    }

    fun openSession(id: String) {
        screen = Screen.Detail(id)
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    fun openClip(id: String, index: Int) {
        screen = Screen.Clip(id, index)
    }

    fun openSummary(id: String, jobId: String) {
        screen = Screen.Summary(id, jobId)
    }

    /** One level up: readers return to their folder, everything else to the home screen. */
    fun back() {
        screen = when (val s = screen) {
            is Screen.Clip -> Screen.Detail(s.id)
            is Screen.Summary -> Screen.Detail(s.id)
            else -> Screen.Home
        }
    }

    fun startRecording(mode: SessionMode) {
        RecorderState.clearError()
        app.settings.update { it.copy(defaultMode = mode) }
        RecordingService.start(app, mode, "")
    }

    /** Records more into an existing folder (same interview, after a break). */
    fun recordMore(folderId: String) {
        val folder = store.get(folderId) ?: return
        RecorderState.clearError()
        RecordingService.start(app, folder.mode, folder.title, folderId)
    }

    fun togglePause() = RecordingService.command(app, RecordingService.ACTION_TOGGLE_PAUSE)
    fun bookmark() = RecordingService.command(app, RecordingService.ACTION_BOOKMARK)
    fun stop() = RecordingService.command(app, RecordingService.ACTION_STOP)

    fun detail(id: String): Flow<FolderDetail?> = store.version
        .map {
            store.get(id)?.let { s ->
                FolderDetail(
                    session = s,
                    clipText = s.segments.associate { it.index to store.readTranscript(id, it.index) },
                    summaryText = s.summaries.associate { it.id to store.readSummary(id, it.id) },
                )
            }
        }
        .flowOn(Dispatchers.IO)

    fun transcribeAll(id: String) = io { Processing.retryStt(app, id) }
    fun retranscribe(id: String, index: Int) = io { Processing.retryStt(app, id, index) }
    fun deleteClip(id: String, index: Int) = io { store.deleteClip(id, index) }

    fun summarize(id: String, clipIndexes: List<Int>, mode: SessionMode) = io {
        Processing.requestSummary(app, id, clipIndexes, mode)
    }

    fun retrySummary(id: String, jobId: String) = io { Processing.retrySummary(app, id, jobId) }
    fun deleteSummary(id: String, jobId: String) = io { store.deleteSummary(id, jobId) }

    /** Text of the chosen clips, for sharing or copying. */
    suspend fun clipsText(id: String, indexes: Collection<Int>): String = withContext(Dispatchers.IO) {
        store.get(id)?.let { store.transcript(it, indexes) }.orEmpty()
    }

    /**
     * Whole folder for one-tap sharing: name, newest finished summary, then every transcript.
     * Null when there is nothing beyond the name yet.
     */
    suspend fun folderShareText(id: String): String? = withContext(Dispatchers.IO) {
        val s = store.get(id) ?: return@withContext null
        val latest = s.summaries.filter { it.status == TaskStatus.DONE }.maxByOrNull { it.createdAt }
        val text = ShareText.folder(
            title = s.title,
            summary = latest?.let { store.readSummary(id, it.id) },
            clips = s.segments.map { ClipText(it.number, it.startMs, it.endMs, it.title, store.readTranscript(id, it.index)) },
        )
        text.takeIf { it != s.title.trim() }
    }

    fun rename(id: String, title: String) = io {
        if (title.isNotBlank()) store.update(id) { it.copy(title = title.trim()) }
    }

    /** Hides the folder immediately; [commitDelete] removes it for good unless the user undoes. */
    fun requestDelete(id: String) {
        val session = store.get(id) ?: return
        pendingDeletes.value = pendingDeletes.value + id
        lastDeleted = session
        if (screen !is Screen.Home && screen !is Screen.Settings) screen = Screen.Home
    }

    fun undoDelete(id: String) {
        pendingDeletes.value = pendingDeletes.value - id
        if (lastDeleted?.id == id) lastDeleted = null
    }

    fun commitDelete(id: String) {
        if (id !in pendingDeletes.value) return
        if (lastDeleted?.id == id) lastDeleted = null
        io {
            Processing.cancel(app, id)
            store.delete(id)
            pendingDeletes.value = pendingDeletes.value - id
        }
    }

    override fun onCleared() {
        // Leaving the app before the snackbar ends still deletes what the user deleted.
        pendingDeletes.value.forEach { id ->
            Processing.cancel(app, id)
            store.delete(id)
        }
        super.onCleared()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = app.settings.update(transform)

    suspend fun testConnection(config: ProviderConfig): ConnectionResult = withContext(Dispatchers.IO) {
        if (!config.isComplete) return@withContext ConnectionResult(false, "Thiếu API key hoặc tên model")
        try {
            val models = Providers.testConnection(config)
            if (models.isEmpty() || models.any { it == config.model }) {
                ConnectionResult(true, "Kết nối thành công")
            } else {
                ConnectionResult(
                    false,
                    "Key hợp lệ nhưng không có model \"${config.model}\". Có: " + models.take(5).joinToString(),
                )
            }
        } catch (e: Exception) {
            ConnectionResult(false, e.message ?: "Không kết nối được")
        }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
