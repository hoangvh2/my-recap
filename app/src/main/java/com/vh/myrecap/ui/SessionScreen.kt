package com.vh.myrecap.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.core.Prompts
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.ShareText
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.SummaryJob
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.ui.theme.Brand
import kotlinx.coroutines.launch

/** Clips that can go into a summary: transcribed and containing speech. */
fun selectable(seg: Segment, text: String?): Boolean =
    seg.stt == TaskStatus.DONE && !text.isNullOrBlank() && text != Prompts.NO_SPEECH && text != Prompts.INTERVIEWER_ONLY

/** Clock time a clip started, or its position in the recording for old data without one. */
fun clipTime(seg: Segment): String = if (seg.recordedAt > 0) clock(seg.recordedAt) else TimeFormat.clock(seg.startMs)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: AppViewModel, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detailFlow = remember(id) { vm.detail(id) }
    val detail by detailFlow.collectAsState(initial = null)
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var summarizing by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }
    val recordMore = rememberRecordAction(onDenied = { micDenied = true }) { vm.recordMore(id) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

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
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(onClick = { scope.launch { shareFolder() } }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Chia sẻ cả folder")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Tuỳ chọn folder") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Đổi tên") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Chia sẻ file ghi âm") },
                                leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) },
                                onClick = {
                                    menu = false
                                    d?.let { Sharing.shareAudio(context, MyRecapApp.from(context).store, it.session) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Xoá folder", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; vm.requestDelete(id) },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        val s = d.session
        val status = folderStatus(s, settings)
        val selectableClips = s.segments.filter { selectable(it, d.clipText[it.index]) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item {
                FolderHeader(s, onRename = { renaming = true })
            }
            if (status.kind in setOf(StatusKind.WARNING, StatusKind.ERROR, StatusKind.WORKING, StatusKind.IDLE) && s.segments.isNotEmpty()) {
                item {
                    val transcribe: () -> Unit = { vm.transcribeAll(id) }
                    val openSettings: () -> Unit = { vm.openSettings() }
                    val (label, action) = when {
                        status.kind == StatusKind.WARNING -> Pair<String?, (() -> Unit)?>("Cài đặt", openSettings)
                        s.segments.any { it.stt == TaskStatus.ERROR } -> Pair("Thử lại", transcribe)
                        status.kind == StatusKind.IDLE && s.sttBusy -> Pair("Chuyển ngay", transcribe)
                        else -> Pair(null, null)
                    }
                    Banner(status, label, action, Modifier.padding(top = 14.dp))
                }
            }
            if (micDenied) {
                item { Banner(StatusInfo(StatusKind.WARNING, "Cần quyền micro để ghi âm"), "Mở cài đặt", { openAppSettings(context) }, Modifier.padding(top = 10.dp)) }
            }
            item {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { summarizing = true },
                        enabled = selectableClips.isNotEmpty() && !s.isRecording,
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Icon(Icons.Rounded.Summarize, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tóm tắt")
                    }
                    if (!s.isRecording) {
                        OutlinedButton(onClick = recordMore, modifier = Modifier.weight(1f).height(50.dp)) {
                            Icon(Icons.Rounded.Mic, null, Modifier.size(20.dp), tint = Brand.Record)
                            Spacer(Modifier.width(8.dp))
                            Text("Ghi tiếp")
                        }
                    }
                }
            }
            item {
                SegmentedTabs(
                    listOf("Đoạn hội thoại · ${s.segments.size}", "Tóm tắt · ${s.summaries.size}"),
                    tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(top = 20.dp, bottom = 14.dp),
                )
            }
            if (tab == 0) {
                if (s.segments.isEmpty()) {
                    item { EmptyState(Icons.Rounded.ViewAgenda, "Chưa có đoạn nào", "Các lượt hội thoại sẽ xuất hiện ở đây khi ghi âm.") }
                }
                val ordered = s.segments.sortedBy { it.index }
                items(ordered, key = { "clip-${it.index}" }) { seg ->
                    ClipRow(
                        seg = seg,
                        text = d.clipText[seg.index],
                        bookmarks = s.bookmarksMs.count { it >= seg.startMs && it <= seg.endMs },
                        isLast = seg == ordered.last(),
                        onOpen = { vm.openClip(id, seg.index) },
                        onRetranscribe = { vm.retranscribe(id, seg.index) },
                        onDelete = { vm.deleteClip(id, seg.index) },
                    )
                }
            } else {
                val jobs = s.summaries.sortedByDescending { it.createdAt }
                if (jobs.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Rounded.Summarize,
                            "Chưa có tóm tắt",
                            "Bấm Tóm tắt, chọn mẫu và các đoạn cần thiết. AI chỉ đọc transcript, không gửi lại âm thanh.",
                        )
                    }
                }
                items(jobs, key = { "sum-${it.id}" }) { job ->
                    SummaryRow(
                        job = job,
                        text = d.summaryText[job.id],
                        onOpen = { vm.openSummary(id, job.id) },
                        onShare = { t -> Sharing.shareText(context, s.title, s.title + "\n\n" + ShareText.plain(t)) },
                        onCopy = { t -> Sharing.copy(context, s.title, ShareText.plain(t)) },
                        onRetry = { vm.retrySummary(id, job.id) },
                        onDelete = { vm.deleteSummary(id, job.id) },
                    )
                }
            }
        }

        if (summarizing) {
            SummarizeSheet(
                session = s,
                clips = selectableClips,
                onDismiss = { summarizing = false },
                onConfirm = { indexes, mode ->
                    summarizing = false
                    vm.summarize(id, indexes, mode)
                    tab = 1
                },
            )
        }
    }

    if (renaming) {
        detail?.let { RenameDialog(it.session.title, onRename = { t -> vm.rename(id, t) }, onDismiss = { renaming = false }) }
    }
}

@Composable
private fun FolderHeader(s: Session, onRename: () -> Unit) {
    val style = modeStyle(s.mode)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(style.icon, style.container, style.content, size = 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClickLabel = "Đổi tên", onClick = onRename),
            )
            Text(
                "${s.mode.label}  ·  ${formatDate(s.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetaChip(Icons.Rounded.ViewAgenda, "${s.segments.size} đoạn")
        MetaChip(Icons.Rounded.Schedule, TimeFormat.clock(s.audioMs))
        if (s.bookmarksMs.isNotEmpty()) MetaChip(Icons.Rounded.Bookmark, "${s.bookmarksMs.size}", Brand.Bookmark)
    }
}

@Composable
fun MetaChip(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(c.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) c.surfaceContainerLowest else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) c.onSurface else c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipRow(
    seg: Segment,
    text: String?,
    bookmarks: Int,
    isLast: Boolean,
    onOpen: () -> Unit,
    onRetranscribe: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Timeline: clock time and a rail connecting the turns of the conversation.
        Column(Modifier.width(52.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(clipTime(seg), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(6.dp))
            if (!isLast) Box(Modifier.width(2.dp).weight(1f).clip(RoundedCornerShape(1.dp)).background(c.outlineVariant))
        }
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(18.dp),
            color = c.surfaceContainerLow,
            modifier = Modifier.weight(1f).padding(bottom = 10.dp),
        ) {
            Column(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 14.dp, end = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Đoạn ${seg.number}", style = MaterialTheme.typography.labelMedium, color = c.primary)
                    Text("  ·  ${TimeFormat.clock(seg.durationMs)}", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    if (bookmarks > 0) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Bookmark, "Có đánh dấu", Modifier.size(14.dp), tint = Brand.Bookmark)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Tuỳ chọn đoạn", tint = c.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Chuyển văn bản lại") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = { menu = false; onRetranscribe() },
                            )
                            DropdownMenuItem(
                                text = { Text("Xoá đoạn", color = c.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                }
                seg.title?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
                    Spacer(Modifier.height(4.dp))
                }
                val body = Modifier.padding(end = 12.dp)
                when (seg.stt) {
                    TaskStatus.DONE -> Text(
                        when (text?.trim()) {
                            Prompts.NO_SPEECH -> "Không có lời nói"
                            Prompts.INTERVIEWER_ONLY -> "Chỉ có lời người phỏng vấn"
                            else -> text.orEmpty().lineSequence().joinToString(" ")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = body,
                    )
                    TaskStatus.PENDING, TaskStatus.RUNNING -> Column(body) {
                        Text(
                            if (seg.stt == TaskStatus.RUNNING) "Đang chuyển thành văn bản" else "Chờ chuyển thành văn bản",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.onSurfaceVariant,
                        )
                        if (seg.stt == TaskStatus.RUNNING) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                        }
                    }
                    TaskStatus.ERROR -> Text(seg.error ?: "Không chuyển được văn bản", style = MaterialTheme.typography.bodyMedium, color = c.error, modifier = body)
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Xoá đoạn ${seg.number}?",
            "Âm thanh và transcript của đoạn này sẽ bị xoá.",
            "Xoá",
            destructive = true,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryRow(
    job: SummaryJob,
    text: String?,
    onOpen: () -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf(false) }
    val style = modeStyle(job.mode)
    Surface(
        onClick = onOpen,
        enabled = job.status == TaskStatus.DONE,
        shape = RoundedCornerShape(20.dp),
        color = c.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(style.icon, style.container, style.content, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tóm tắt ${job.mode.label.lowercase()}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${job.clipIndexes.size} đoạn  ·  ${formatDate(job.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when (job.status) {
                TaskStatus.PENDING, TaskStatus.RUNNING -> {
                    Text(job.progress ?: "Đang chuẩn bị…", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)))
                }
                TaskStatus.ERROR -> {
                    Text(job.error ?: "Tóm tắt chưa thành công", style = MaterialTheme.typography.bodyMedium, color = c.error)
                    Row(Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = onRetry) { Text("Thử lại") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Xoá") }
                    }
                }
                TaskStatus.DONE -> {
                    val body = text.orEmpty()
                    Text(
                        ShareText.plain(body).lineSequence().filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Xem chi tiết") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onCopy(body) }) { Icon(Icons.Rounded.ContentCopy, "Sao chép tóm tắt", tint = c.onSurfaceVariant) }
                        IconButton(onClick = { onShare(body) }) { Icon(Icons.Rounded.Share, "Chia sẻ tóm tắt", tint = c.onSurfaceVariant) }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Xoá tóm tắt", tint = c.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Xoá bản tóm tắt này?", null, "Xoá", destructive = true, onConfirm = onDelete, onDismiss = { confirmDelete = false })
    }
}

private fun templateHint(mode: SessionMode) = when (mode) {
    SessionMode.INTERVIEW -> "Đánh giá ứng viên + chi tiết từng câu hỏi"
    SessionMode.MEETING -> "Quyết định, việc cần làm, vấn đề mở"
    SessionMode.CUSTOM -> "Theo yêu cầu bạn viết trong Cài đặt"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummarizeSheet(
    session: Session,
    clips: List<Segment>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>, SessionMode) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableStateOf(session.mode) }
    var chosen by remember { mutableStateOf(clips.map { it.index }.toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.surfaceContainerLow) {
        // The confirm button stays pinned at the bottom; everything above scrolls, so it is always
        // reachable on small screens and with many clips.
        Column(Modifier.navigationBarsPadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Text("Tạo tóm tắt", style = MaterialTheme.typography.titleLarge)
                Text(
                    "AI chỉ đọc transcript đã có, không gửi lại âm thanh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text("Mẫu", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SessionMode.entries.forEach { m ->
                        val style = modeStyle(m)
                        val on = m == mode
                        Surface(
                            onClick = { mode = m },
                            shape = RoundedCornerShape(16.dp),
                            color = if (on) c.primaryContainer.copy(alpha = 0.55f) else c.surfaceContainer,
                            border = if (on) BorderStroke(1.5.dp, c.primary) else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconTile(style.icon, style.container, style.content, size = 34.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(m.label, style = MaterialTheme.typography.titleSmall)
                                    Text(templateHint(m), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Đoạn hội thoại", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    val all = chosen.size == clips.size
                    TextButton(onClick = { chosen = if (all) emptySet() else clips.map { it.index }.toSet() }) {
                        Text(if (all) "Bỏ chọn tất cả" else "Chọn tất cả")
                    }
                }
                clips.forEach { seg ->
                    val on = seg.index in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Checkbox) { chosen = if (on) chosen - seg.index else chosen + seg.index }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(seg.title ?: "Đoạn ${seg.number}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Đoạn ${seg.number}  ·  ${clipTime(seg)}  ·  ${TimeFormat.clock(seg.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Surface(color = c.surfaceContainerLow) {
                Button(
                    onClick = { onConfirm(chosen.toList(), mode) },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp),
                ) { Text("Tóm tắt ${chosen.size} đoạn") }
            }
        }
    }
}
