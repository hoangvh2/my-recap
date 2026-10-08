package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.ShareText
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.TaskStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: AppViewModel, id: String) {
    val context = LocalContext.current
    val detailFlow = remember(id) { vm.detail(id) }
    val detail by detailFlow.collectAsState(initial = null)
    val settings by vm.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var resummarizing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val d = detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.session?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Thêm") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Đổi tên") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Tóm tắt lại / đổi mẫu") }, onClick = { menu = false; resummarizing = true })
                        DropdownMenuItem(
                            text = { Text("Chia sẻ file ghi âm") },
                            onClick = {
                                menu = false
                                d?.let { Sharing.shareAudio(context, MyRecapApp.from(context).store, it.session) }
                            },
                        )
                        DropdownMenuItem(text = { Text("Xoá") }, onClick = { menu = false; deleting = true })
                    }
                },
            )
        },
        bottomBar = {
            if (d != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = { sharing = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                            Text("Chia sẻ", fontSize = 18.sp)
                        }
                        FilledTonalButton(
                            onClick = {
                                val text = if (tab == 0) d.summary.orEmpty() else d.transcript
                                Sharing.copy(context, d.session.title, text)
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text("Sao chép", fontSize = 18.sp) }
                    }
                }
            }
        },
    ) { padding ->
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Không tìm thấy bản ghi") }
            return@Scaffold
        }
        val s = d.session
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${formatDate(s.createdAt)} · ${TimeFormat.clock(s.durationMs)} · ${s.mode.label}" +
                        if (s.bookmarksMs.isNotEmpty()) " · ⭐ ${s.bookmarksMs.size}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (status, isError) = statusText(s, settings)
                val canProcess = !s.isRecording && s.segments.isNotEmpty() &&
                    (isError || (s.transcribedCount == 0 && !settings.autoProcess && s.segments.none { it.stt == TaskStatus.RUNNING }) ||
                        (s.allTranscribed && s.summary == null && settings.summaryEnabled))
                MessageCard(status, isError) {
                    if (canProcess) {
                        Button(onClick = { vm.process(id) }, modifier = Modifier.padding(top = 8.dp).height(48.dp)) {
                            Text(if (isError) "Thử lại" else "Xử lý ngay")
                        }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Tóm tắt", fontSize = 16.sp) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Transcript", fontSize = 16.sp) })
            }
            SelectionContainer(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    if (tab == 0) {
                        val summary = d.summary
                        when {
                            summary != null -> MarkdownText(summary)
                            !settings.summaryEnabled -> Text("AI tóm tắt đang tắt (bật trong Cài đặt).")
                            else -> Text("Chưa có tóm tắt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(d.transcript.ifBlank { "Chưa có transcript." }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    val current = detail ?: return
    if (renaming) {
        var title by remember { mutableStateOf(current.session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Đổi tên") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.rename(id, title); renaming = false }) { Text("Lưu") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Huỷ") } },
        )
    }
    if (sharing) {
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text("Chia sẻ nội dung") },
            text = {
                Column {
                    val options = listOf(
                        ShareText.Content.SUMMARY to "Tóm tắt",
                        ShareText.Content.SUMMARY_AND_TRANSCRIPT to "Tóm tắt + transcript",
                        ShareText.Content.TRANSCRIPT to "Chỉ transcript",
                    )
                    for ((content, label) in options) {
                        TextButton(
                            onClick = {
                                sharing = false
                                val s = current.session
                                Sharing.shareText(
                                    context,
                                    s.title,
                                    ShareText.build(content, s.title, formatDate(s.createdAt), s.durationMs, current.summary, current.transcript),
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(label, fontSize = 18.sp) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sharing = false }) { Text("Huỷ") } },
        )
    }
    if (resummarizing) {
        var mode by remember { mutableStateOf(current.session.mode) }
        AlertDialog(
            onDismissRequest = { resummarizing = false },
            title = { Text("Tóm tắt lại theo mẫu") },
            text = {
                Column {
                    SessionMode.entries.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == m, onClick = { mode = m })
                            Text(m.label)
                        }
                    }
                    if (mode == SessionMode.CUSTOM) {
                        Text(
                            "Mẫu 'Tự do' dùng yêu cầu bạn viết trong Cài đặt → Yêu cầu tóm tắt tự do.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { vm.resummarize(id, mode); resummarizing = false },
                    enabled = settings.summaryEnabled,
                ) { Text("Tóm tắt lại") }
            },
            dismissButton = { TextButton(onClick = { resummarizing = false }) { Text("Huỷ") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Xoá bản ghi?") },
            text = { Text("Xoá cả file ghi âm, transcript và tóm tắt. Không thể hoàn tác.") },
            confirmButton = {
                Button(onClick = { deleting = false; vm.delete(id) }) { Text("Xoá") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Huỷ") } },
        )
    }
}
