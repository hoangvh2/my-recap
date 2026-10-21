package com.vh.myrecap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.ChecklistTemplate
import com.vh.myrecap.core.ChecklistTemplates
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Recurrence
import kotlinx.coroutines.launch

/**
 * The checklist templates in one place: add, edit and delete (with confirmation and Undo). A template with a
 * time or a repeat is created at once when used; one without opens the editor to choose the day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistTemplatesScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val list = settings.checklistTemplates
    // null: closed; "": a new template; otherwise the id being edited.
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun reallyDelete(id: String) {
        val index = list.indexOfFirst { it.id == id }
        val removed = list.getOrNull(index) ?: return
        vm.removeChecklistTemplate(id)
        scope.launch {
            val r = snackbar.showSnackbar("Đã xoá mẫu “${removed.name}”", actionLabel = "Hoàn tác", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restoreChecklistTemplate(index, removed)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Mẫu checklist", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Chạm một mẫu để sửa. Mẫu có giờ hoặc lặp được tạo ngay khi dùng; mẫu còn lại mở trình soạn để chọn ngày.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { editing = "" }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("Thêm mẫu", Modifier.padding(start = 6.dp))
                    }
                }
            }
            items(list, key = { it.id }) { t ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable { editing = t.id },
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOf("${t.items.size} mục", ChecklistTemplates.scheduleLabel(t), if (t.uses > 0) "Dùng ${t.uses} lần" else "Chưa dùng").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { confirmDelete = t.id }) { Icon(Icons.Rounded.Close, contentDescription = "Xoá mẫu ${t.name}") }
                    }
                }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Text("Chưa có mẫu nào.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            item(key = "defaults") {
                OutlinedButton(onClick = vm::restoreDefaultChecklistTemplates, modifier = Modifier.fillMaxWidth()) { Text("Khôi phục mẫu có sẵn") }
            }
        }
    }

    confirmDelete?.let { id ->
        ConfirmDialog(
            title = "Xoá mẫu này?",
            text = list.firstOrNull { it.id == id }?.name,
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { confirmDelete = null; reallyDelete(id) },
            onDismiss = { confirmDelete = null },
        )
    }
    editing?.let { id ->
        EditChecklistTemplateDialog(
            initial = list.firstOrNull { it.id == id },
            save = { name, lines, type, recurrence, time -> vm.saveChecklistTemplate(id.ifEmpty { null }, name, lines, type, recurrence, time) },
            onDone = { editing = null },
            onDelete = { editing = null; confirmDelete = id },
        )
    }
}

@Composable
private fun EditChecklistTemplateDialog(
    initial: ChecklistTemplate?,
    save: (String, String, ItemType, Recurrence?, String?) -> String?,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var lines by rememberSaveable { mutableStateOf(initial?.items?.joinToString("\n") ?: "") }
    var type by rememberSaveable { mutableStateOf(initial?.type ?: ItemType.TASK) }
    var recurrence by rememberSaveable { mutableStateOf(initial?.recurrence) }
    var time by rememberSaveable { mutableStateOf(initial?.time ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (initial == null) "Mẫu checklist mới" else "Sửa mẫu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Tên mẫu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    lines, { lines = it; error = null },
                    label = { Text("Các mục (mỗi dòng một mục)") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == ItemType.TASK, onClick = { type = ItemType.TASK }, label = { Text("Việc") })
                    FilterChip(selected = type == ItemType.EVENT, onClick = { type = ItemType.EVENT }, label = { Text("Lịch hẹn") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = recurrence == null, onClick = { recurrence = null }, label = { Text("Không lặp") })
                    Recurrence.entries.forEach { r ->
                        FilterChip(selected = recurrence == r, onClick = { recurrence = r }, label = { Text(r.label) })
                    }
                }
                OutlinedTextField(
                    time, { time = it; error = null },
                    label = { Text("Giờ (HH:mm, để trống nếu không có)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = save(name, lines, type, recurrence, time.trim().ifEmpty { null })
                if (e == null) onDone() else error = e
            }) { Text("Lưu") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onDelete) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDone) { Text("Huỷ") }
            }
        },
    )
}
