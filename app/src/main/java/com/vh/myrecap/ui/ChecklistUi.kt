package com.vh.myrecap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.Checklist
import com.vh.myrecap.core.ChecklistEntry
import com.vh.myrecap.core.ChecklistTemplate
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.StudyPlan
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.study.StudyEngine
import com.vh.myrecap.study.StudyService
import kotlinx.coroutines.launch

/** "2/5" or "Đủ 5/5": words and a tick, never colour alone. Tap opens the card under the row. */
@Composable
fun ChecklistChip(item: Item, expanded: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val p = Checklist.progress(item)
    val scheme = MaterialTheme.colorScheme
    val tone = if (p.complete) scheme.primary else if (expanded) scheme.primary else scheme.onSurfaceVariant
    val label = Checklist.chipLabel(item)
    Surface(
        shape = RoundedCornerShape(50),
        color = if (p.complete) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, tone),
        modifier = modifier
            .heightIn(min = 36.dp)
            .semantics { contentDescription = "Checklist $label" + if (onClick != null) (if (expanded) ". Thu gọn" else ". Mở để tick") else "" }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
    }
}

/** The open card under a row: what is still missing first, what is already in the bag below, one button for "all there". */
@Composable
fun ChecklistPanel(item: Item, onOp: (ChecklistOp) -> Unit, modifier: Modifier = Modifier) {
    val p = Checklist.progress(item)
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider()
        p.remaining.forEach { ChecklistLine(it) { onOp(ChecklistOp.Toggle(it.id)) } }
        if (p.ticked.isNotEmpty()) {
            Text(
                "Đã có (${p.ticked.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
            )
            p.ticked.forEach { ChecklistLine(it) { onOp(ChecklistOp.Toggle(it.id)) } }
        }
        StudyRow(item)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.remaining.isNotEmpty()) {
                Button(onClick = { onOp(ChecklistOp.All) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Đủ rồi")
                }
            }
            if (p.ticked.isNotEmpty()) TextButton(onClick = { onOp(ChecklistOp.Reset) }) { Text("Đặt lại") }
        }
    }
}

@Composable
private fun ChecklistLine(e: ChecklistEntry, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = e.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = e.done, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            e.text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (e.done) TextDecoration.LineThrough else null,
            color = if (e.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The checklist section of the editor: type a line and press Enter, paste a list, or attach a template. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChecklistEditor(
    entries: List<ChecklistEntry>,
    templates: List<ChecklistTemplate>,
    title: String,
    onChange: (List<ChecklistEntry>) -> Unit,
    onNeedTitle: (String) -> Unit,
    onSaveAsTemplate: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var confirmEntry by remember { mutableStateOf<ChecklistEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val full = entries.size >= Checklist.MAX_ENTRIES

    fun add() {
        val lines = Checklist.parseLines(draft)
        if (lines.isEmpty()) return
        onChange(Checklist.append(entries, lines))
        draft = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (entries.isEmpty()) "Checklist" else "Checklist · ${entries.size} mục",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entries.forEachIndexed { index, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = e.text,
                    onValueChange = { v -> onChange(entries.map { if (it.id == e.id) it.copy(text = v.take(Checklist.MAX_TEXT)) else it }) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Mục ${index + 1}" },
                )
                IconButton(onClick = { confirmEntry = e }) { Icon(Icons.Rounded.Close, contentDescription = "Xoá mục ${e.text}") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = !full,
                singleLine = true,
                placeholder = { Text(if (full) "Đã đủ số mục tối đa" else "Thêm mục, Enter để thêm tiếp…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { add() }),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Thêm mục checklist" },
            )
            IconButton(onClick = ::add, enabled = draft.isNotBlank() && !full) { Icon(Icons.Rounded.Add, contentDescription = "Thêm mục") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (templates.isNotEmpty()) {
                Box {
                    AssistChip(onClick = { menu = true }, label = { Text("Gắn mẫu") })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        templates.forEach { t ->
                            DropdownMenuItem(
                                text = { Text("${t.name} · ${t.items.size}") },
                                onClick = {
                                    menu = false
                                    onChange(Checklist.append(entries, t.items))
                                    if (title.isBlank()) onNeedTitle(t.name)
                                },
                            )
                        }
                    }
                }
            }
            if (entries.isNotEmpty()) {
                AssistChip(onClick = onSaveAsTemplate, label = { Text("Lưu thành mẫu") })
                AssistChip(onClick = { confirmClear = true }, label = { Text("Xoá checklist", color = MaterialTheme.colorScheme.error) })
            }
        }
    }

    confirmEntry?.let { e ->
        ConfirmDialog(
            title = "Xoá mục này?",
            text = e.text,
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { onChange(entries.filter { it.id != e.id }) },
            onDismiss = { confirmEntry = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Xoá checklist này?",
            text = "${entries.size} mục. Việc vẫn còn, chỉ mất danh sách.",
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { onChange(emptyList()) },
            onDismiss = { confirmClear = false },
        )
    }
}


/**
 * "Bắt đầu học" for a checklist whose lines start with minutes, or the running session with its way out. Ending asks first;
 * the ticks already made stay.
 */
@Composable
private fun StudyRow(item: Item) {
    val context = LocalContext.current
    val session by StudyEngine.active.collectAsState()
    val running = session?.takeIf { it.itemId == item.id && !it.finished }
    var confirmEnd by remember { mutableStateOf(false) }
    if (running != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Đang học · bước ${running.index + 1}/${running.phases.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmEnd = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Kết thúc") }
        }
    } else if (session == null && StudyPlan.canStart(item)) {
        val minutes = StudyPlan.totalMinutes(StudyPlan.phases(item))
        OutlinedButton(
            onClick = { StudyService.start(context, item.id) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Bắt đầu học · $minutes phút")
        }
    }
    if (confirmEnd && running != null) {
        ConfirmDialog(
            title = "Kết thúc phiên học?",
            text = "“${item.title}”. Các dòng đã tick giữ nguyên, bộ đếm giờ sẽ dừng.",
            confirmLabel = "Kết thúc",
            destructive = true,
            onConfirm = { MyRecapApp.from(context).appScope.launch { StudyEngine.stop(context) } },
            onDismiss = { confirmEnd = false },
        )
    }
}
