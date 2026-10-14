package com.vh.myrecap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.core.QuickTemplates
import com.vh.myrecap.core.TextSearch
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Saved quick-add lines on their own screen: one box both searches the list and saves a new line, lines
 * are grouped by kind with how often each was used, and a tap edits one. Lines worth saving, taken from
 * what the user keeps entering, are offered at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val list = settings.quickTemplates
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()

    val words = TextSearch.tokens(query)
    val shown = list.withIndex().filter { (_, t) ->
        val folded = TextSearch.fold(QuickTemplates.normalize(t.text).replace(QuickTemplates.BLANK, " "))
        words.all { it in folded }
    }
    val featured = remember(list) { QuickTemplates.featured(list, now, zone).map { it.text }.toSet() }
    val byType = shown.groupBy { QuickTemplates.typeOf(it.value.text, now, zone) }
    val suggestions = remember(items, list) { QuickTemplates.suggest(items, list, now, zone) }
    val canSave = query.isNotBlank() && QuickTemplates.add(list, query).size > list.size

    fun save() {
        if (!canSave) return
        vm.addTemplate(query)
        query = ""
    }

    fun delete(index: Int) {
        confirmDelete = index
    }

    fun reallyDelete(index: Int) {
        val removed = list.getOrNull(index) ?: return
        vm.removeTemplate(index)
        scope.launch {
            val r = snackbar.showSnackbar("Đã xoá “${removed.text}”", actionLabel = "Hoàn tác", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restoreTemplate(index, removed)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Mẫu câu nhập nhanh", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "box") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        placeholder = { Text("Tìm hoặc gõ mẫu mới") },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Xoá chữ") }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                    if (canSave) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Mẫu mới · ${QuickTemplates.typeOf(query, now, zone).label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(onClick = ::save) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Text("Lưu mẫu", Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    Text(
                        "Dùng … cho chỗ cần điền mỗi lần, ví dụ \"ăn trưa …k\", \"gọi … mai 9h\". Mẫu không có … được thêm ngay khi bấm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (type in QuickTemplates.FEATURED_TYPES + (byType.keys - QuickTemplates.FEATURED_TYPES.toSet())) {
                val group = byType[type] ?: continue
                item(key = "head-$type") { SectionLabel(type.label, trailing = "${group.size}") }
                items(group, key = { "t-${it.index}-${it.value.text}" }) { (index, t) ->
                    TemplateRow(t, isFeatured = t.text in featured, onEdit = { editing = index }, onDelete = { delete(index) })
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (list.isEmpty()) "Chưa có mẫu nào. Gõ một câu ở ô trên rồi bấm Lưu mẫu." else "Không có mẫu nào chứa \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            if (suggestions.isNotEmpty() && query.isBlank()) {
                item(key = "suggest-head") { SectionLabel("Gợi ý từ những gì bạn hay nhập") }
                items(suggestions, key = { "s-$it" }) { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line, Modifier.weight(1f).padding(start = 4.dp))
                        TextButton(onClick = { vm.addTemplate(line) }) { Text("Thêm") }
                    }
                }
            }
        }
    }

    confirmDelete?.let { pending ->
        ConfirmDialog(
            title = "Xoá mẫu này?",
            text = list.getOrNull(pending)?.text,
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { reallyDelete(pending) },
            onDismiss = { confirmDelete = null },
        )
    }
    val index = editing
    val current = index?.let { list.getOrNull(it) }
    if (index != null && current != null) {
        EditTemplateDialog(
            initial = current.text,
            onSave = { text -> if (vm.editTemplate(index, text)) editing = null },
            onDelete = { editing = null; delete(index) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun TemplateRow(t: QuickTemplate, isFeatured: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.text, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isFeatured) {
                        Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        listOfNotNull(
                            "Hiện sẵn ở ô gõ".takeIf { isFeatured },
                            if (t.uses > 0) "Dùng ${t.uses} lần" else "Chưa dùng",
                            "Điền chỗ trống".takeIf { QuickTemplates.hasBlank(t.text) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Close, contentDescription = "Xoá mẫu") }
        }
    }
}

@Composable
private fun EditTemplateDialog(initial: String, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sửa mẫu") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Dùng … cho chỗ cần điền") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Lưu") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Xoá", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Huỷ") }
            }
        },
    )
}
