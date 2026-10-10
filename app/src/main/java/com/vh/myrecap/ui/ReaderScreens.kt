package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.ShareText
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.ui.theme.Brand

/** One conversational turn, readable full screen, with previous/next to read through the interview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipScreen(vm: AppViewModel, id: String, index: Int) {
    val context = LocalContext.current
    val detailFlow = remember(id) { vm.detail(id) }
    val detail by detailFlow.collectAsState(initial = null)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAudio by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val d = detail ?: return
    val ordered = d.session.segments.sortedBy { it.index }
    val pos = ordered.indexOfFirst { it.index == index }
    val seg = ordered.getOrNull(pos) ?: return
    val text = d.clipText[seg.index].orEmpty()
    val shareable = "${d.session.title} — Đoạn ${seg.number}" + (seg.title?.let { ": $it" } ?: "") + "\n\n" + text.trim()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Đoạn ${seg.number} / ${ordered.size}", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    if (seg.stt == TaskStatus.DONE) {
                        IconButton(onClick = { Sharing.copy(context, d.session.title, text) }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Sao chép")
                        }
                        IconButton(onClick = { Sharing.shareText(context, d.session.title, shareable) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Chia sẻ đoạn")
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Tuỳ chọn") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (seg.stt == TaskStatus.DONE) "Sửa transcript" else "Tự nhập transcript") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                enabled = seg.stt == TaskStatus.DONE || seg.stt == TaskStatus.ERROR,
                                onClick = { menu = false; editing = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Chuyển văn bản lại") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                // Needs the audio; once deleted, the transcript is final.
                                enabled = seg.hasAudio,
                                onClick = { menu = false; vm.retranscribe(id, seg.index) },
                            )
                            DropdownMenuItem(
                                text = { Text("Xoá đoạn", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
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
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val prev = ordered.getOrNull(pos - 1)
                    val next = ordered.getOrNull(pos + 1)
                    OutlinedButton(onClick = { prev?.let { vm.replaceTop(Screen.Clip(id, it.index)) } }, enabled = prev != null, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null)
                        Text("Trước", maxLines = 1)
                    }
                    OutlinedButton(onClick = { next?.let { vm.replaceTop(Screen.Clip(id, it.index)) } }, enabled = next != null, modifier = Modifier.weight(1f)) {
                        Text("Sau", maxLines = 1)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(seg.title ?: "Đoạn ${seg.number}", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(Icons.Rounded.Schedule, clipTime(seg))
                MetaChip(Icons.Rounded.Timer, TimeFormat.clock(seg.durationMs))
                val marks = d.session.bookmarksMs.count { it >= seg.startMs && it <= seg.endMs }
                if (marks > 0) MetaChip(Icons.Rounded.Bookmark, "$marks", Brand.Bookmark)
            }
            if (seg.hasAudio) {
                val parts = remember(seg.index, seg.fileName) { vm.audioParts(d.session.copy(segments = listOf(seg))) }
                AudioPlayerCard(
                    parts = parts,
                    sizeLabel = formatBytes(parts.sumOf { it.first.length() }),
                    // Audio still waiting for speech-to-text cannot go yet.
                    onDelete = if (seg.stt == TaskStatus.DONE) ({ confirmAudio = true }) else null,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            when (seg.stt) {
                TaskStatus.DONE -> SelectionContainer { SpeakerTranscript(text) }
                TaskStatus.ERROR -> Banner(
                    StatusInfo(StatusKind.ERROR, seg.error ?: "Không chuyển được văn bản"),
                    "Thử lại",
                    { vm.retranscribe(id, seg.index) },
                )
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Đang chuyển thành văn bản…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editing) {
        TextEditorDialog(
            title = "Đoạn ${seg.number}",
            initial = if (seg.stt == TaskStatus.DONE) text else "",
            saveLabel = "Lưu",
            hint = "Giữ nhãn người nói ở đầu dòng (vd: “Ứng viên: …”) để transcript hiển thị theo lượt.",
            onSave = { vm.editClipText(id, seg.index, it) },
            onDismiss = { editing = false },
        )
    }
    if (confirmAudio) {
        ConfirmDialog(
            "Xoá file ghi âm của đoạn ${seg.number}?",
            "Transcript vẫn được giữ. Sau khi xoá sẽ không nghe lại hay chuyển văn bản lại được.",
            "Xoá ghi âm",
            destructive = true,
            onConfirm = { vm.deleteAudio(id, listOf(seg.index)) },
            onDismiss = { confirmAudio = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Xoá đoạn ${seg.number}?",
            "Âm thanh và transcript của đoạn này sẽ bị xoá.",
            "Xoá",
            destructive = true,
            onConfirm = {
                vm.deleteClip(id, seg.index)
                vm.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A finished summary, typeset for reading, with copy/share/delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: AppViewModel, id: String, jobId: String) {
    val context = LocalContext.current
    val detailFlow = remember(id) { vm.detail(id) }
    val detail by detailFlow.collectAsState(initial = null)
    var confirmDelete by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    val d = detail ?: return
    val job = d.session.summaries.firstOrNull { it.id == jobId } ?: return
    val text = d.summaryText[jobId].orEmpty()
    val style = modeStyle(job.mode)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Tóm tắt", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(onClick = { Sharing.copy(context, d.session.title, ShareText.plain(text)) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Sao chép")
                    }
                    IconButton(onClick = { Sharing.shareText(context, d.session.title, d.session.title + "\n\n" + ShareText.plain(text)) }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Chia sẻ tóm tắt")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, contentDescription = "Xoá tóm tắt") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(style.icon, style.container, style.content, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(d.session.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${job.mode.label}  ·  ${job.clipIndexes.size} đoạn  ·  ${formatDate(job.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SelectionContainer { MarkdownText(text) }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Xoá bản tóm tắt này?",
            null,
            "Xoá",
            destructive = true,
            onConfirm = {
                vm.deleteSummary(id, jobId)
                vm.back()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
