package com.vh.myrecap.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vh.myrecap.MyRecapApp
import androidx.compose.runtime.mutableStateListOf
import com.vh.myrecap.backup.BackupManager
import com.vh.myrecap.backup.BackupResult
import com.vh.myrecap.backup.RestoreResult
import com.vh.myrecap.core.BackupException
import com.vh.myrecap.core.ClipText
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Prompts
import com.vh.myrecap.core.ProviderConfig
import com.vh.myrecap.core.Providers
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.ShareText
import com.vh.myrecap.core.TextSearch
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.StorageStats
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.reminder.Reminders
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
    /** A quick capture: its words, the AI proposals to confirm and the items saved from it. */
    data class Review(val id: String) : Screen
    /** An item, or a new one of [type] when [id] is null. */
    data class ItemEdit(val id: String?, val type: ItemType = ItemType.TASK) : Screen
    data object Settings : Screen
    data object Search : Screen

    /** Whether this screen shows folder/capture [folderId] (closed when that is deleted). */
    fun shows(folderId: String): Boolean = when (this) {
        is Detail -> id == folderId
        is Clip -> id == folderId
        is Summary -> id == folderId
        is Review -> id == folderId
        else -> false
    }
}

/** Screen plus its depth in the back stack, so transitions know the direction. */
data class NavEntry(val screen: Screen, val depth: Int)

/** A quick capture with its text, the items taken from it and the audio still kept. */
class MemoDetail(val session: Session, val text: String, val items: List<Item>, val audioBytes: Long)

private val VI: java.util.Locale = java.util.Locale.forLanguageTag("vi")

/** A transcript passage that matched a search: folder, clip (null for a folder-title match) and excerpt. */
class ClipHit(val session: Session, val index: Int?, val label: String, val snippet: String)

class SearchResults(
    val query: String,
    val items: List<Item>,
    val memos: List<Pair<Session, String>>,
    val clips: List<ClipHit>,
) {
    val isEmpty: Boolean get() = items.isEmpty() && memos.isEmpty() && clips.isEmpty()
}

/** "4,2 MB" */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(VI, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(VI, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** Result of the Settings "test connection" button. */
data class ConnectionResult(val ok: Boolean, val message: String)

/** A folder with the text of each clip (by index) and of each summary (by job id). */
class FolderDetail(
    val session: Session,
    val clipText: Map<Int, String?>,
    val summaryText: Map<String, String?>,
    val audioBytes: Long,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MyRecapApp
    private val store = app.store

    /** Folders deleted from the UI but kept on disk for a few seconds, so the user can undo. */
    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())

    private val all: StateFlow<List<Session>> = combine(store.version, pendingDeletes) { _, hidden -> hidden }
        .map { hidden -> store.list().filterNot { it.id in hidden } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Recording folders (interviews, meetings…). */
    val sessions: StateFlow<List<Session>> = all.map { list -> list.filterNot { it.isMemo } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Quick captures, newest first. */
    val memos: StateFlow<List<Session>> = all.map { list -> list.filter { it.isMemo } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val items: StateFlow<List<Item>> = app.items.items
    val itemsReady: StateFlow<Boolean> = app.items.ready

    /** Last folder hidden by [requestDelete]; the home screen offers Undo for it. */
    var lastDeleted by mutableStateOf<Session?>(null)
        private set

    val settings: StateFlow<AppSettings> = app.settings.settings
    val recorder = RecorderState.ui

    /** Incremented on every Activity resume so permission/battery checks refresh. */
    private val _resumeTick = MutableStateFlow(0)
    val resumeTick: StateFlow<Int> = _resumeTick

    /** Back stack; the home screen is always at the bottom. */
    private val stack = mutableStateListOf<Screen>(Screen.Home)

    val screen: Screen get() = stack.last()
    val nav: NavEntry get() = NavEntry(stack.last(), stack.size)

    fun onResume() {
        _resumeTick.value++
        // Repeating appointments that are over move to their next date (and keep reminding).
        io { app.items.rollRecurring().forEach { Reminders.sync(app, it) } }
    }

    private fun push(target: Screen) {
        if (stack.last() != target) stack.add(target)
    }

    /** Opens a folder, or the review screen for a quick capture. */
    fun openSession(id: String) {
        val session = store.get(id) ?: return
        push(if (session.isMemo) Screen.Review(id) else Screen.Detail(id))
    }

    fun openSettings() = push(Screen.Settings)
    fun openSearch() = push(Screen.Search)
    fun openClip(id: String, index: Int) = push(Screen.Clip(id, index))
    fun openSummary(id: String, jobId: String) = push(Screen.Summary(id, jobId))
    fun openItem(id: String) = push(Screen.ItemEdit(id))
    fun newItem(type: ItemType) = push(Screen.ItemEdit(null, type))

    /** A reader opened from a notification replaces the reader already open, not stacks on it. */
    fun replaceTop(target: Screen) {
        if (stack.size > 1) stack[stack.size - 1] = target else push(target)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    fun setHomeTab(tab: Int) = app.settings.update { it.copy(homeTab = tab) }

    /** Set by the launcher shortcut; the secretary tab starts the capture (it owns the permission prompt). */
    var quickCapturePending by mutableStateOf(false)
        private set

    fun requestQuickCapture() {
        if (RecorderState.ui.value.active) return // already recording: the recording screen is showing
        stack.clear()
        stack.add(Screen.Home)
        setHomeTab(0)
        quickCapturePending = true
    }

    fun consumeQuickCapture() {
        quickCapturePending = false
    }

    private fun closeScreensOf(id: String) {
        stack.removeAll { it.shows(id) }
        if (stack.isEmpty()) stack.add(Screen.Home)
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

    /** Quick capture: one tap, speak, tap Xong; the AI proposes items afterwards. */
    fun startMemo() {
        RecorderState.clearError()
        RecordingService.start(app, SessionMode.MEMO, "Ghi nhanh " + clock(System.currentTimeMillis()))
    }

    fun discardRecording() = RecordingService.command(app, RecordingService.ACTION_DISCARD)
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
                    audioBytes = store.audioBytes(s),
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
        closeScreensOf(id)
    }

    // ---- Audio storage ----

    /** Deletes the audio of transcribed clips (all when [indexes] is null); text stays. */
    fun deleteAudio(id: String, indexes: Collection<Int>? = null) = io {
        val freed = store.deleteAudio(id, indexes)
        if (freed > 0) toast("Đã giải phóng ${formatBytes(freed)}")
    }

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) { store.storageStats() }

    /** Settings "clean up now": audio of every transcribed clip in every folder and capture. */
    suspend fun deleteAllTranscribedAudio(): Long = withContext(Dispatchers.IO) {
        store.list().filterNot { it.isRecording }.sumOf { store.deleteAudio(it.id) }
    }

    // ---- Secretary items ----

    fun memoDetail(id: String): Flow<MemoDetail?> = combine(store.version, app.items.items) { _, items -> items }
        .map { items ->
            store.get(id)?.let { s ->
                MemoDetail(s, store.memoText(s), items.filter { it.sourceId == id }, store.audioBytes(s))
            }
        }
        .flowOn(Dispatchers.IO)

    /** Captures, by id, for linking items to where they came from. */
    fun memo(id: String): Session? = memos.value.firstOrNull { it.id == id }

    fun audioParts(session: Session): List<Pair<java.io.File, Long>> =
        session.audioClips.sortedBy { it.index }.map { store.audioFile(session.id, it) to it.durationMs }

    /** Confirms AI proposals; only then do they count and remind. */
    fun confirm(ids: Collection<String>) = io {
        ids.forEach { id ->
            app.items.update(id) { if (it.status == ItemStatus.DRAFT) it.copy(status = ItemStatus.OPEN) else it }
                ?.let { Reminders.sync(app, it) }
        }
    }

    fun saveItem(item: Item) = io {
        app.items.upsert(item)
        Reminders.sync(app, item)
    }

    fun setDone(id: String, done: Boolean) = io { applyDone(id, done) }

    /** Saves edits and completes (or reopens) in order, so the next instance of a repeating task carries the edits. */
    fun saveAndSetDone(item: Item, done: Boolean) = io {
        app.items.upsert(item)
        applyDone(item.id, done)
    }

    private fun applyDone(id: String, done: Boolean) {
        if (done) {
            val (_, next) = app.items.complete(id) ?: return
            Reminders.cancel(app, id)
            next?.let { Reminders.sync(app, it) }
        } else {
            app.items.update(id) { it.copy(status = ItemStatus.OPEN, doneAt = null) }?.let { Reminders.sync(app, it) }
        }
    }

    /** Last item deleted from the UI; screens offer Undo for a few seconds. */
    var lastDeletedItem by mutableStateOf<Item?>(null)
        private set

    fun deleteItem(id: String) = io {
        val item = app.items.get(id)
        app.items.delete(id)
        Reminders.cancel(app, id)
        withContext(Dispatchers.Main) { lastDeletedItem = item }
    }

    fun undoDeleteItem(item: Item) {
        if (lastDeletedItem?.id == item.id) lastDeletedItem = null
        saveItem(item)
    }

    fun clearDeletedItem(item: Item) {
        if (lastDeletedItem?.id == item.id) lastDeletedItem = null
    }

    /** Runs the AI analysis of a capture again, replacing its unconfirmed proposals. */
    fun reanalyze(id: String) = io {
        store.update(id) { it.copy(extract = TaskStatus.PENDING, error = null) }
        Processing.retryStt(app, id)
    }

    /** Deletes a capture (audio and text) and its unconfirmed proposals; confirmed items stay. */
    fun deleteMemo(id: String) {
        closeScreensOf(id)
        io {
            Processing.cancel(app, id)
            app.items.deleteDrafts(id)
            store.delete(id)
        }
    }

    // ---- Transcript editing ----

    /** Replaces one clip's transcript (fixing speech-to-text); summaries notes made from it are dropped. */
    fun editClipText(id: String, index: Int, text: String) = io {
        if (text.isBlank()) return@io
        store.writeTranscript(id, index, text)
        store.update(id) { s -> s.copy(segments = s.segments.map { if (it.index == index) it.copy(stt = TaskStatus.DONE, error = null) else it }) }
    }

    /** Replaces what a quick capture said, then analyses it again (its unconfirmed proposals are replaced). */
    fun editMemoText(id: String, text: String) = io {
        val s = store.get(id) ?: return@io
        val segs = s.segments.sortedBy { it.index }
        if (segs.isEmpty() || text.isBlank()) return@io
        store.writeTranscript(id, segs.first().index, text)
        segs.drop(1).forEach { store.writeTranscript(id, it.index, Prompts.NO_SPEECH) }
        store.update(id) { m ->
            m.copy(segments = m.segments.map { it.copy(stt = TaskStatus.DONE, error = null) }, extract = TaskStatus.PENDING, error = null)
        }
        Processing.enqueue(app, id)
    }

    // ---- Backup ----

    /** What the backup screen is doing right now ("Đang sao lưu…"), or null when idle. */
    var backupBusy by mutableStateOf<String?>(null)
        private set

    suspend fun backupTo(uri: android.net.Uri, includeAudio: Boolean): kotlin.Result<BackupResult> {
        backupBusy = "Đang sao lưu…"
        return try {
            withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { BackupManager(app).export(it, includeAudio) }
                        ?: throw BackupException("Không mở được tệp để ghi")
                }
            }
        } finally {
            backupBusy = null
        }
    }

    suspend fun restoreFrom(uri: android.net.Uri): kotlin.Result<RestoreResult> {
        backupBusy = "Đang khôi phục…"
        return try {
            withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { BackupManager(app).restore(it) }
                        ?: throw BackupException("Không mở được tệp sao lưu")
                }
            }
        } finally {
            backupBusy = null
        }
    }

    fun enableAutoBackup(treeUri: android.net.Uri) = io { BackupManager.enableAuto(app, treeUri) }
    fun disableAutoBackup() = io { BackupManager.disableAuto(app) }

    /** Runs the weekly backup right away (to check the folder works). */
    suspend fun autoBackupNow(): kotlin.Result<String> {
        backupBusy = "Đang sao lưu…"
        return try {
            withContext(Dispatchers.IO) {
                runCatching { BackupManager(app).autoBackup() }
                    .onSuccess { app.settings.update { s -> s.copy(autoBackupError = "") } }
                    .onFailure { e -> app.settings.update { s -> s.copy(autoBackupError = e.message ?: "Sao lưu thất bại") } }
            }
        } finally {
            backupBusy = null
        }
    }

    // ---- Search ----

    /** Accent-insensitive search over items, quick captures, folder titles and transcripts. */
    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val tokens = TextSearch.tokens(query)
        if (tokens.isEmpty()) return@withContext SearchResults(query, emptyList(), emptyList(), emptyList())
        val items = app.items.search(query)
        val memos = mutableListOf<Pair<Session, String>>()
        val clips = mutableListOf<ClipHit>()
        for (s in store.list().filterNot { it.id in pendingDeletes.value }) {
            if (s.isMemo) {
                val text = store.memoText(s)
                if (TextSearch.matches(TextSearch.fold(text), tokens)) memos += s to TextSearch.snippet(text, tokens)
                continue
            }
            if (TextSearch.matches(TextSearch.fold(s.title), tokens)) {
                clips += ClipHit(s, null, s.mode.label + " · " + formatDate(s.createdAt), s.title)
            }
            // A few passages per folder keep the list readable; the folder holds the rest.
            var hits = 0
            for (seg in s.segments.sortedBy { it.index }) {
                if (hits >= 3) break
                val text = store.readTranscript(s.id, seg.index) ?: continue
                val haystack = TextSearch.fold((seg.title ?: "") + " " + text)
                if (TextSearch.matches(haystack, tokens)) {
                    clips += ClipHit(s, seg.index, "Đoạn ${seg.number}" + (seg.title?.let { " · $it" } ?: ""), TextSearch.snippet(text, tokens))
                    hits++
                }
            }
        }
        SearchResults(query, items, memos, clips)
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show()
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
