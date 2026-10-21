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
import com.vh.myrecap.cloud.Account
import com.vh.myrecap.cloud.ShareException
import com.vh.myrecap.cloud.ShareRepo
import com.vh.myrecap.core.ActionItems
import com.vh.myrecap.core.Contact
import com.vh.myrecap.core.ShareNotice
import com.vh.myrecap.core.SharedItem
import com.vh.myrecap.core.ApiException
import com.vh.myrecap.core.BackupException
import com.vh.myrecap.core.Checklist
import com.vh.myrecap.core.ChecklistEntry
import com.vh.myrecap.core.ChecklistTemplate
import com.vh.myrecap.core.ChecklistTemplates
import com.vh.myrecap.core.ClipText
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.core.QuickTemplates
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemTag
import com.vh.myrecap.core.TagFilter
import com.vh.myrecap.core.TagRules
import com.vh.myrecap.core.Recurrence
import com.vh.myrecap.core.canChecklist
import com.vh.myrecap.core.canHaveTag
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Prompts
import com.vh.myrecap.core.ProviderConfig
import com.vh.myrecap.core.Providers
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.ShareText
import com.vh.myrecap.core.TextSearch
import com.vh.myrecap.core.Triage
import com.vh.myrecap.data.ItemStore
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.StorageStats
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.reminder.Reminders
import com.vh.myrecap.recorder.RecordingService
import com.vh.myrecap.settings.AppSettings
import com.vh.myrecap.work.Processing
import java.time.ZoneId
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

/** What a tap in an item's checklist does. */
sealed interface ChecklistOp {
    data class Toggle(val entryId: String) : ChecklistOp
    data object All : ChecklistOp
    data object Reset : ChecklistOp
}

/** The item as it was before a checklist change, with the words for the Undo bar. */
data class ChecklistUndo(val before: Item, val message: String)

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
    /** Saved quick-add lines: list, search, add, edit. */
    data object Templates : Screen
    /** Checklist templates: list, add, edit, delete. */
    data object ChecklistTemplates : Screen
    data object Search : Screen
    data object Spending : Screen
    data object Weekly : Screen
    /** People I linked with; making and entering link codes. */
    data object Contacts : Screen
    data class ContactDetail(val uid: String) : Screen
    /** Read-only view of an item someone shared with me. */
    data class Shared(val id: String) : Screen

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
    fun openTemplates() = push(Screen.Templates)
    fun openChecklistTemplates() = push(Screen.ChecklistTemplates)
    fun openSearch() = push(Screen.Search)
    fun openSpending() = push(Screen.Spending)
    fun openWeekly() = push(Screen.Weekly)
    fun openClip(id: String, index: Int) = push(Screen.Clip(id, index))
    fun openSummary(id: String, jobId: String) = push(Screen.Summary(id, jobId))
    /** Opens my item in the editor, or someone else's shared item read-only. */
    fun openItem(id: String) {
        if (received.value.any { it.id == id }) openShared(id) else push(Screen.ItemEdit(id))
    }
    fun openShared(id: String) = push(Screen.Shared(id))
    fun openContacts() = push(Screen.Contacts)
    fun openContact(uid: String) = push(Screen.ContactDetail(uid))
    fun newItem(type: ItemType) = push(Screen.ItemEdit(null, type))

    /** A reader opened from a notification replaces the reader already open, not stacks on it. */
    fun replaceTop(target: Screen) {
        if (stack.size > 1) stack[stack.size - 1] = target else push(target)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    // ------------------------------------------------------------ sync and sharing

    val account: StateFlow<Account> = app.cloud.account
    val accountProblem: StateFlow<String?> = app.cloud.problem
    val sharing: ShareRepo? = app.cloud.sharing
    val contacts: StateFlow<List<Contact>> = sharing?.contacts ?: MutableStateFlow(emptyList())
    val received: StateFlow<List<SharedItem>> = sharing?.received ?: MutableStateFlow(emptyList())
    val audiences: StateFlow<Map<String, List<String>>> = sharing?.audiences ?: MutableStateFlow(emptyMap())
    val notices: StateFlow<List<ShareNotice>> = sharing?.notices ?: MutableStateFlow(emptyList())

    /** One-line result of a sharing action, shown as a snackbar. */
    var shareMessage by mutableStateOf<String?>(null)
        private set

    fun say(message: String) {
        shareMessage = message
    }

    fun clearShareMessage() {
        shareMessage = null
    }

    fun signIn(activity: android.app.Activity) = viewModelScope.launch {
        app.cloud.signIn(activity)?.let { say(it) }
    }

    fun signOut() = lasting { app.cloud.signOut() }

    /**
     * Saves my item and, when [audience] changed, who it is shared with. Sharing needs the item on the server
     * first (the function reads it there), so this waits for the upload.
     */
    fun saveItemAndShare(item: Item, audience: List<String>) = lasting {
        app.items.upsert(item)
        Reminders.sync(app, item)
        val sharing = sharing ?: return@lasting
        val message = if (app.cloud.sync?.putAndWait(item) != true) {
            "Đã lưu. Chưa chia sẻ được vì đang mất mạng, mở lại mục này để chia sẻ"
        } else {
            try {
                val now = sharing.setAudience(item.id, audience)
                if (now.isEmpty()) "Đã lưu, mục này không còn chia sẻ với ai" else "Đã lưu và chia sẻ với ${now.size} người"
            } catch (e: ShareException) {
                "Đã lưu. Chưa chia sẻ được: ${e.message}"
            }
        }
        withContext(Dispatchers.Main) { say(message) }
    }

    /** A copy of something shared with me, as my own item (my reminder, my edits). */
    fun copyShared(shared: SharedItem) {
        val copy = shared.asItem().copy(
            id = ItemStore.newId(),
            status = ItemStatus.OPEN,
            doneAt = null,
            createdAt = System.currentTimeMillis(),
        )
        saveItem(copy)
        say("Đã chép “${shared.title}” về mục của bạn")
    }

    fun setHomeTab(tab: Int) = app.settings.update { it.copy(homeTab = tab) }

    /** Set by the launcher shortcut; the secretary tab starts the capture (it owns the permission prompt). */
    var quickCapturePending by mutableStateOf(false)
        private set

    /** Title and AI context for the next quick capture, set when it was started from an after-call prompt. */
    private var pendingMemo: Pair<String, String>? = null

    fun requestQuickCapture(title: String? = null, noteContext: String? = null) {
        if (RecorderState.ui.value.active) return // already recording: the recording screen is showing
        pendingMemo = if (title != null && noteContext != null) title to noteContext else null
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
        val call = pendingMemo
        pendingMemo = null
        RecordingService.start(
            app,
            SessionMode.MEMO,
            call?.first ?: ("Ghi nhanh " + clock(System.currentTimeMillis())),
            noteContext = call?.second,
        )
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
            val rules = settings.value.tagRules
            app.items.update(id) { if (it.status == ItemStatus.DRAFT) TagRules.applyToNew(rules, it.copy(status = ItemStatus.OPEN)) else it }
                ?.let { Reminders.sync(app, it) }
        }
    }

    fun saveItem(item: Item) = io {
        // A brand-new item gets the tag its rules give; an edit never re-applies them.
        val toSave = if (app.items.get(item.id) == null) TagRules.applyToNew(settings.value.tagRules, item) else item
        app.items.upsert(toSave)
        Reminders.sync(app, toSave)
    }

    /** Sets or clears the colour tag of a task or appointment (null = none). */
    fun setTag(id: String, tag: ItemTag?) = io {
        app.items.update(id) { if (it.type.canHaveTag()) it.copy(tag = tag?.key) else it }
    }

    /** What the list screens are narrowed to by tag. */
    var tagFilter by mutableStateOf(TagFilter())
        private set

    fun filterByTag(filter: TagFilter) { tagFilter = filter }

    fun setDone(id: String, done: Boolean) = io { applyDone(id, done) }

    /** Last item saved by quick add or a template; the screen offers Undo for a few seconds. */
    var lastAddedItem by mutableStateOf<Item?>(null)
        private set

    /** Saves a typed quick-add as a confirmed item (the user saw what was understood), with its reminder. */
    fun quickAdd(parsed: com.vh.myrecap.core.QuickAdd.Result, checklist: List<String> = emptyList()) = lasting {
        val made = parsed.toItem(com.vh.myrecap.data.ItemStore.newId(), System.currentTimeMillis())
        val item = TagRules.applyToNew(
            settings.value.tagRules,
            if (checklist.isNotEmpty() && made.type.canChecklist()) made.copy(checklist = Checklist.entriesFrom(checklist)) else made,
        )
        app.items.upsert(item)
        Reminders.sync(app, item)
        withContext(Dispatchers.Main) { lastAddedItem = item }
    }

    /** One tap on a saved line without a blank: parsed now, so "mai 9h" means tomorrow from today. */
    fun addFromTemplate(template: QuickTemplate) {
        val parsed = com.vh.myrecap.core.QuickAdd.parse(
            QuickTemplates.normalize(template.text), System.currentTimeMillis(), java.time.ZoneId.systemDefault(),
            history = items.value,
        )
        quickAdd(parsed)
        templateUsed(template)
    }

    /** A new item for the editor to start from (a checklist template without its own time); taken once. */
    private var draftItem: Item? = null

    fun takeDraft(): Item? = draftItem.also { draftItem = null }

    /**
     * One tap on a checklist template. One with its own time or repeat is created at once (with Undo); one
     * without opens the editor, so the day and the customer can be chosen.
     */
    fun addFromChecklistTemplate(t: ChecklistTemplate) {
        val now = System.currentTimeMillis()
        val item = ChecklistTemplates.instantiate(t, com.vh.myrecap.data.ItemStore.newId(), now, ZoneId.systemDefault())
        updateSettings { it.copy(checklistTemplates = ChecklistTemplates.used(it.checklistTemplates, t.id, now)) }
        if (!ChecklistTemplates.isScheduled(t)) {
            draftItem = item
            newItem(item.type)
            return
        }
        lasting {
            val toSave = TagRules.applyToNew(settings.value.tagRules, item)
            app.items.upsert(toSave)
            Reminders.sync(app, toSave)
            withContext(Dispatchers.Main) { lastAddedItem = toSave }
        }
    }

    /** Message for the form when the template is not valid; null once saved. [selfId]: the template being edited. */
    fun saveChecklistTemplate(selfId: String?, name: String, lines: String, type: ItemType, recurrence: Recurrence?, time: String?): String? {
        val check = ChecklistTemplates.validate(settings.value.checklistTemplates, name, lines, type, recurrence, time, selfId)
        val t = check.template ?: return check.error
        updateSettings { s ->
            s.copy(checklistTemplates = if (selfId != null) ChecklistTemplates.update(s.checklistTemplates, t) else ChecklistTemplates.add(s.checklistTemplates, t))
        }
        return null
    }

    /** From the editor: saves the checklist of the item being edited as a template named like the title. */
    fun saveChecklistAsTemplate(title: String, entries: List<ChecklistEntry>) {
        val check = ChecklistTemplates.validate(settings.value.checklistTemplates, title, entries.joinToString("\n") { it.text }, ItemType.TASK, null, null)
        val t = check.template
        if (t == null) return say(check.error ?: "Chưa lưu được mẫu")
        updateSettings { s -> s.copy(checklistTemplates = ChecklistTemplates.add(s.checklistTemplates, t)) }
        say("Đã lưu mẫu “${t.name}”")
    }

    fun removeChecklistTemplate(id: String) =
        updateSettings { s -> s.copy(checklistTemplates = ChecklistTemplates.remove(s.checklistTemplates, id)) }

    fun restoreChecklistTemplate(index: Int, t: ChecklistTemplate) =
        updateSettings { s -> s.copy(checklistTemplates = ChecklistTemplates.restore(s.checklistTemplates, index, t)) }

    fun restoreDefaultChecklistTemplates() =
        updateSettings { s -> s.copy(checklistTemplates = ChecklistTemplates.restoreDefaults(s.checklistTemplates)) }

    /** What to undo after the checklist of an item changed in a way worth undoing (finished by the last tick, "Đặt lại"). */
    var lastChecklistChange by mutableStateOf<ChecklistUndo?>(null)
        private set

    fun checklist(id: String, op: ChecklistOp) = io {
        val before = app.items.get(id) ?: return@io
        val next = when (op) {
            is ChecklistOp.Toggle -> Checklist.toggle(before, op.entryId)
            ChecklistOp.All -> Checklist.tickAll(before)
            ChecklistOp.Reset -> Checklist.reset(before)
        }
        if (next == before) return@io
        val complete = Checklist.progress(next).complete
        when {
            // The last tick finishes it (a repeating one comes back tomorrow with nothing ticked).
            complete && before.status == ItemStatus.OPEN && before.type.canChecklist() -> {
                app.items.upsert(next)
                applyDone(id, true)
                withContext(Dispatchers.Main) { lastChecklistChange = ChecklistUndo(before, "Đã đủ “${before.title}”") }
            }
            // Unticking a line on a finished item opens it again.
            before.status == ItemStatus.DONE && !complete -> {
                val reopened = next.copy(status = ItemStatus.OPEN, doneAt = null)
                app.items.upsert(reopened)
                Reminders.sync(app, reopened)
            }
            else -> {
                app.items.upsert(next)
                if (op == ChecklistOp.Reset) withContext(Dispatchers.Main) { lastChecklistChange = ChecklistUndo(before, "Đã đặt lại “${before.title}”") }
            }
        }
    }

    fun undoChecklistChange(undo: ChecklistUndo) {
        if (lastChecklistChange == undo) lastChecklistChange = null
        saveItem(undo.before)
    }

    fun clearChecklistChange(undo: ChecklistUndo) {
        if (lastChecklistChange == undo) lastChecklistChange = null
    }

    /** Counts a use, so the most used line of each kind is the one the empty box and the widget offer. */
    fun templateUsed(template: QuickTemplate) =
        updateSettings { it.copy(quickTemplates = QuickTemplates.used(it.quickTemplates, template, System.currentTimeMillis())) }

    fun undoAddItem(item: Item) {
        if (lastAddedItem?.id == item.id) lastAddedItem = null
        lasting {
            app.items.delete(item.id)
            Reminders.cancel(app, item.id)
        }
    }

    fun clearAddedItem(item: Item) {
        if (lastAddedItem?.id == item.id) lastAddedItem = null
    }

    /** Saves a new line; false when it is empty or already saved. */
    fun addTemplate(text: String): Boolean {
        val before = settings.value.quickTemplates
        val after = QuickTemplates.add(before, text)
        updateSettings { it.copy(quickTemplates = after) }
        return after.size > before.size
    }

    /** From the quick-add box: saves what is typed, with a toast either way. */
    fun saveTemplate(text: String) {
        val added = addTemplate(text)
        viewModelScope.launch { toast(if (added) "Đã lưu mẫu" else "Mẫu này đã có") }
    }

    /** False when the new text is empty or repeats another line. */
    fun editTemplate(index: Int, text: String): Boolean {
        val before = settings.value.quickTemplates
        val after = QuickTemplates.edit(before, index, text)
        updateSettings { it.copy(quickTemplates = after) }
        return after != before || QuickTemplates.normalize(text) == before.getOrNull(index)?.text
    }

    /** Puts a deleted line back where it was (Undo). */
    fun restoreTemplate(index: Int, template: QuickTemplate) = updateSettings { s ->
        if (s.quickTemplates.any { it.text == template.text }) s
        else s.copy(quickTemplates = s.quickTemplates.toMutableList().also { it.add(index.coerceIn(0, it.size), template) })
    }

    fun removeTemplate(index: Int) =
        updateSettings { s -> s.copy(quickTemplates = s.quickTemplates.filterIndexed { i, _ -> i != index }) }

    /** The summary whose follow-ups are being extracted, to disable its button meanwhile. */
    var extractingActionsFor by mutableStateOf<String?>(null)
        private set

    /**
     * Reads a finished summary and proposes its follow-ups as DRAFT items (shown under "Chờ xác nhận"
     * in the Việc tab). Nothing is saved without the owner confirming.
     */
    fun extractActions(sessionId: String, jobId: String) {
        if (extractingActionsFor != null) return
        extractingActionsFor = jobId
        io {
            try {
                val summary = store.readSummary(sessionId, jobId)?.takeIf { it.isNotBlank() } ?: return@io
                val config = app.settings.current.summaryConfig()
                if (!config.isComplete) return@io toast("Chưa cấu hình AI (API key/model) trong Cài đặt")
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val raw = try {
                    Providers.textGenerator(config).generate(
                        ActionItems.system(),
                        ActionItems.userMessage(summary, java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)),
                    )
                } catch (e: ApiException) {
                    if (e.emptyResult) "" else throw e
                }
                val parsed = ActionItems.parse(raw, zone, now, sessionId, ItemStore::newId)
                    ?: return@io toast("AI trả lời không đọc được, thử lại sau")
                val fresh = ActionItems.withoutDuplicates(parsed, app.items.bySource(sessionId))
                if (fresh.isEmpty()) return@io toast("Không có việc mới cần làm trong bản tóm tắt")
                fresh.forEach { app.items.upsert(it) }
                toast("Đã thêm ${fresh.size} việc chờ xác nhận ở tab Việc")
            } catch (e: ApiException) {
                toast(e.message ?: "Lỗi dịch vụ AI")
            } finally {
                extractingActionsFor = null
            }
        }
    }

    /** One tap of the morning review: moves an overdue task to a new day, or off the calendar. */
    fun triage(id: String, move: Triage.Move) = io {
        app.items.update(id) { Triage.apply(it, move, System.currentTimeMillis(), ZoneId.systemDefault()) }
            ?.let { Reminders.sync(app, it) }
    }

    /** Saves edits and completes (or reopens) in order, so the next instance of a repeating task carries the edits. */
    fun saveAndSetDone(item: Item, done: Boolean) = io {
        app.items.upsert(item)
        applyDone(item.id, done)
    }

    private fun applyDone(id: String, done: Boolean) {
        if (done) {
            val (finished, next) = app.items.complete(id) ?: return
            Reminders.cancel(app, id)
            // A repeating appointment ticked off moved to its next occurrence and is still open.
            if (finished.status == ItemStatus.OPEN) Reminders.sync(app, finished)
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

    /** For writes that must finish even when the screen closes right away (the quick-add sheet does). */
    private fun lasting(block: suspend () -> Unit) {
        app.appScope.launch(Dispatchers.IO) { block() }
    }
}
