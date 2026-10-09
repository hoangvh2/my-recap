package com.vh.myrecap.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.core.Prompts
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.SummaryJob
import com.vh.myrecap.data.TaskStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clips that can go into a summary: transcribed and containing speech. */
private fun selectable(seg: Segment, text: String?): Boolean =
    seg.stt == TaskStatus.DONE && !text.isNullOrBlank() && text != Prompts.NO_SPEECH && text != Prompts.INTERVIEWER_ONLY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: AppViewModel, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detailFlow = remember(id) { vm.detail(id) }
    val detail by detailFlow.collectAsState(initial = null)
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable(id) { mutableStateOf(listOf<Int>()) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    val recordMore = rememberRecordAction(onDenied = { micDenied = true }) { vm.recordMore(id) }

    suspend fun shareFolder() {
        val title = detail?.session?.title ?: return
        val text = vm.folderShareText(id)
        if (text == null) {
            Toast.makeText(context, "Chưa có tóm tắt hoặc transcript để chia sẻ", Toast.LENGTH_SHORT).show()
        } else {
            Sharing.shareText(context, title, text)
        }
    }

    val d = detail
    // Drop selections of clips that were deleted or are no longer selectable.
    val selectableIndexes = d?.session?.segments?.filter { selectable(it, d.clipText[it.index]) }?.map { it.index }.orEmpty()
    val chosen = selected.filter { it in selectableIndexes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        d?.session?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(onClick = { scope.launch { shareFolder() } }) {
                        Icon(Icons.Filled.Share, contentDescription = "Chia sẻ cả folder")
                    }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Đổi tên folder") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Thêm") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Chia sẻ cả folder (tóm tắt + transcript)") },
                            onClick = {
                                menu = false
                                scope.launch { shareFolder() }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Chia sẻ file ghi âm") },
                            onClick = {
                                menu = false
                                d?.let { Sharing.shareAudio(context, MyRecapApp.from(context).store, it.session) }
                            },
                        )
                        DropdownMenuItem(text = { Text("Xoá folder") }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        bottomBar = {
            if (d != null && tab == 0 && d.session.segments.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pad = PaddingValues(horizontal = 8.dp)
                        Button(
                            onClick = { summarizing = true },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.weight(1.4f).height(56.dp),
                            contentPadding = pad,
                        ) { Text("Tóm tắt (${chosen.size})", fontSize = 16.sp, maxLines = 1) }
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    Sharing.shareText(context, d.session.title, d.session.title + "\n\n" + vm.clipsText(id, chosen))
                                }
                            },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(56.dp),
                            contentPadding = pad,
                        ) { Text("Chia sẻ", fontSize = 16.sp, maxLines = 1) }
                        FilledTonalButton(
                            onClick = { scope.launch { Sharing.copy(context, d.session.title, vm.clipsText(id, chosen)) } },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.weight(0.8f).height(56.dp),
                            contentPadding = pad,
                        ) { Text("Chép", fontSize = 16.sp, maxLines = 1) }
                    }
                }
            }
        },
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Không tìm thấy folder") }
            return@Scaffold
        }
        val s = d.session
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "${formatDate(s.createdAt)} · ${s.mode.label} · ${s.segments.size} đoạn · ${TimeFormat.clock(s.audioMs)}" +
                        if (s.bookmarksMs.isNotEmpty()) " · ⭐ ${s.bookmarksMs.size}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { StatusCard(vm, s, settings.sttConfig().isComplete, statusText(s, settings)) }
            if (micDenied) {
                item { MessageCard("Cần quyền micro để ghi âm.", isError = true) }
            }
            if (!s.isRecording) {
                item {
                    OutlinedButton(
                        onClick = recordMore,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("●  Ghi tiếp vào folder này", fontSize = 16.sp) }
                }
            }
            item {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Đoạn (${s.segments.size})", maxLines = 1) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Tóm tắt (${s.summaries.size})", maxLines = 1) })
                }
            }
            if (tab == 0) {
                if (s.segments.isEmpty()) {
                    item { Text("Chưa có đoạn nào.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    item {
                        val allChosen = selectableIndexes.isNotEmpty() && chosen.size == selectableIndexes.size
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = selectableIndexes.isNotEmpty()) {
                                    selected = if (allChosen) emptyList() else selectableIndexes
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = allChosen,
                                onCheckedChange = { all -> selected = if (all) selectableIndexes else emptyList() },
                                enabled = selectableIndexes.isNotEmpty(),
                            )
                            Text(
                                if (chosen.isEmpty()) "Chọn tất cả · chọn đoạn để tóm tắt/chia sẻ" else "Đã chọn ${chosen.size}/${selectableIndexes.size}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(s.segments.sortedBy { it.index }, key = { "clip-${it.index}" }) { seg ->
                        val text = d.clipText[seg.index]
                        ClipCard(
                            seg = seg,
                            text = text,
                            bookmarks = s.bookmarksMs.count { it >= seg.startMs && it <= seg.endMs },
                            checked = seg.index in chosen,
                            selectable = selectable(seg, text),
                            onCheck = { on -> selected = if (on) chosen + seg.index else chosen - seg.index },
                            onRetranscribe = { vm.retranscribe(id, seg.index) },
                            onDelete = { vm.deleteClip(id, seg.index) },
                        )
                    }
                }
            } else {
                val jobs = s.summaries.sortedByDescending { it.createdAt }
                if (jobs.isEmpty()) {
                    item {
                        Text(
                            "Chưa có tóm tắt. Ở tab \"Đoạn\", chọn các đoạn cần thiết rồi bấm Tóm tắt.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(jobs, key = { "sum-${it.id}" }) { job ->
                    SummaryCard(
                        job = job,
                        text = d.summaryText[job.id],
                        initiallyExpanded = job == jobs.firstOrNull(),
                        onShare = { t -> Sharing.shareText(context, s.title, s.title + "\n\n" + t) },
                        onCopy = { t -> Sharing.copy(context, s.title, t) },
                        onRetry = { vm.retrySummary(id, job.id) },
                        onDelete = { vm.deleteSummary(id, job.id) },
                    )
                }
            }
        }
    }

    val current = detail ?: return
    if (renaming) {
        var title by remember { mutableStateOf(current.session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Đổi tên folder") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.rename(id, title); renaming = false }) { Text("Lưu") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Huỷ") } },
        )
    }
    if (summarizing) {
        var mode by remember { mutableStateOf(current.session.mode) }
        val clips = current.session.segments.filter { it.index in chosen }
        AlertDialog(
            onDismissRequest = { summarizing = false },
            title = { Text("Tóm tắt ${clips.size} đoạn") },
            text = {
                Column {
                    Text(
                        "Âm thanh: ${TimeFormat.clock(clips.sumOf { it.durationMs })} · chỉ gửi văn bản (đã có), không gửi lại âm thanh.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SessionMode.entries.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clickable { mode = m }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = { mode = m })
                            Text("Mẫu: ${m.label}")
                        }
                    }
                    if (mode == SessionMode.CUSTOM) {
                        Text(
                            "Mẫu 'Tự do' dùng yêu cầu bạn viết trong Cài đặt → AI tóm tắt.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.summarize(id, chosen, mode)
                    summarizing = false
                    tab = 1
                }) { Text("Tóm tắt") }
            },
            dismissButton = { TextButton(onClick = { summarizing = false }) { Text("Huỷ") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Xoá folder?") },
            text = { Text("Xoá toàn bộ ghi âm, transcript và tóm tắt trong folder. Không thể hoàn tác.") },
            confirmButton = { Button(onClick = { deleting = false; vm.delete(id) }) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun StatusCard(vm: AppViewModel, s: Session, sttReady: Boolean, status: Pair<String, Boolean>) {
    val (text, isError) = status
    val canTranscribe = !s.isRecording && s.sttBusy && s.segments.none { it.stt == TaskStatus.RUNNING }
    MessageCard(text, isError) {
        when {
            !sttReady && s.sttBusy -> Button(onClick = vm::openSettings, modifier = Modifier.padding(top = 8.dp)) { Text("Mở Cài đặt") }
            s.segments.any { it.stt == TaskStatus.ERROR } ->
                Button(onClick = { vm.transcribeAll(s.id) }, modifier = Modifier.padding(top = 8.dp)) { Text("Thử lại các đoạn lỗi") }
            canTranscribe -> Button(onClick = { vm.transcribeAll(s.id) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Chuyển văn bản ngay")
            }
        }
    }
}

@Composable
private fun ClipCard(
    seg: Segment,
    text: String?,
    bookmarks: Int,
    checked: Boolean,
    selectable: Boolean,
    onCheck: (Boolean) -> Unit,
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(seg.index) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (checked) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Checkbox(checked = checked, onCheckedChange = onCheck, enabled = selectable)
            Column(
                Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded }
                    .padding(top = 12.dp, end = 4.dp),
            ) {
                val time = if (seg.recordedAt > 0) clockOf(seg.recordedAt) + " · " else ""
                Text(
                    "Đoạn ${seg.number} · $time${TimeFormat.clock(seg.durationMs)}" + if (bookmarks > 0) " · ⭐ $bookmarks" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                seg.title?.let { Text(it, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall) }
                when (seg.stt) {
                    TaskStatus.DONE -> {
                        val body = text.orEmpty()
                        if (expanded) {
                            SelectionContainer { Text(body, style = MaterialTheme.typography.bodyMedium) }
                        } else {
                            Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TaskStatus.PENDING -> Text("⏳ Chờ chuyển văn bản", style = MaterialTheme.typography.bodyMedium)
                    TaskStatus.RUNNING -> Text("⏳ Đang chuyển văn bản…", style = MaterialTheme.typography.bodyMedium)
                    TaskStatus.ERROR -> Text(
                        "⚠️ ${seg.error ?: "Lỗi"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Tuỳ chọn đoạn") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Chuyển văn bản lại") }, onClick = { menu = false; onRetranscribe() })
                    DropdownMenuItem(text = { Text("Xoá đoạn") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xoá đoạn ${seg.number}?") },
            text = { Text("Xoá âm thanh và transcript của đoạn này.") },
            confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") } },
        )
    }
}

@Composable
private fun SummaryCard(
    job: SummaryJob,
    text: String?,
    initiallyExpanded: Boolean,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable(job.id) { mutableStateOf(initiallyExpanded) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Text(
                    "Tóm tắt · ${job.mode.label} · ${job.clipIndexes.size} đoạn",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatDate(job.createdAt) + " · đoạn " + job.clipIndexes.joinToString(", ") { (it + 1).toString() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (job.status) {
                TaskStatus.PENDING, TaskStatus.RUNNING -> Text("⏳ Đang tóm tắt…")
                TaskStatus.ERROR -> {
                    Text("⚠️ ${job.error ?: "Lỗi"}", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Thử lại") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Xoá") }
                    }
                }
                TaskStatus.DONE -> {
                    val body = text.orEmpty()
                    if (expanded) {
                        SelectionContainer { MarkdownText(body) }
                    } else {
                        Text(body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onShare(body) }) { Text("Chia sẻ") }
                        FilledTonalButton(onClick = { onCopy(body) }) { Text("Chép") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Xoá") }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xoá bản tóm tắt này?") },
            confirmButton = { Button(onClick = { confirmDelete = false; onDelete() }) { Text("Xoá") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Huỷ") } },
        )
    }
}

private fun clockOf(ms: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(ms))
