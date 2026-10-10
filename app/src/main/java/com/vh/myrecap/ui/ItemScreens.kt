package com.vh.myrecap.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.EXPENSE_CATEGORIES
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemText
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Money
import com.vh.myrecap.core.Recurrence
import com.vh.myrecap.data.ItemStore
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.reminder.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val zone: ZoneId get() = ZoneId.systemDefault()

/** A type change keeps what fits the new type: expenses need a date and a category, others no amount. */
private fun Item.withType(t: ItemType): Item = when (t) {
    ItemType.EXPENSE -> copy(
        type = t,
        recurrence = null,
        whenAt = whenAt ?: LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli(),
        allDay = if (whenAt == null) true else allDay,
        category = category ?: "Khác",
    )
    ItemType.NOTE -> copy(type = t, amount = null, category = null, recurrence = null)
    else -> copy(type = t, amount = null, category = null)
}

// ---------------------------------------------------------------------------------------------
// Review: one quick capture, what the AI proposed, and what was saved from it.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: AppViewModel, id: String) {
    val context = LocalContext.current
    val flow = remember(id) { vm.memoDetail(id) }
    val detail by flow.collectAsState(initial = null)
    val settings by vm.settings.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAudio by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val undo = rememberItemUndo(vm)
    val d = detail
    val drafts = d?.items?.filter { it.status == ItemStatus.DRAFT }.orEmpty()
    val saved = d?.items?.filter { it.status != ItemStatus.DRAFT }.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(undo) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ghi nhanh", style = MaterialTheme.typography.titleLarge)
                        d?.let {
                            Text(
                                formatDate(it.session.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Tuỳ chọn ghi chú") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Phân tích lại") },
                                leadingIcon = { Icon(Icons.Rounded.Autorenew, null) },
                                enabled = d != null && d.text.isNotBlank() && !d.session.extractBusy,
                                onClick = { menu = false; vm.reanalyze(id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Xoá ghi chú này", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (drafts.size > 1) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = { vm.confirm(drafts.map { it.id }) },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                    ) { Text("Lưu tất cả ${drafts.size} mục") }
                }
            }
        },
    ) { padding ->
        if (d == null) return@Scaffold
        val s = d.session
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val failed = s.extract == TaskStatus.ERROR || s.segments.any { it.stt == TaskStatus.ERROR }
            val missingKey = !settings.sttConfig().isComplete || !settings.summaryConfig().isComplete
            when {
                (s.sttBusy || s.extractBusy) && missingKey -> item {
                    Banner(StatusInfo(StatusKind.WARNING, "Cần API key để phân tích ghi chú"), "Cài đặt", vm::openSettings)
                }
                failed -> item {
                    Banner(StatusInfo(StatusKind.ERROR, s.error ?: "Chưa phân tích được"), "Thử lại", { vm.reanalyze(id) })
                }
                s.sttBusy -> item { Banner(StatusInfo(StatusKind.WORKING, "Đang chuyển giọng nói thành chữ…")) }
                s.extractBusy -> item { Banner(StatusInfo(StatusKind.WORKING, "Đang phân loại việc, lịch, chi tiêu…")) }
                s.error != null -> item { Banner(StatusInfo(StatusKind.IDLE, s.error)) }
            }

            if (d.text.isNotBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Bạn đã nói", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                IconButton(onClick = { editing = true }, enabled = !s.extractBusy && !s.sttBusy) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Sửa lời ghi", Modifier.size(20.dp))
                                }
                                IconButton(onClick = { Sharing.copy(context, "Ghi nhanh", d.text) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Sao chép", Modifier.size(20.dp))
                                }
                            }
                            Text(d.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
                        }
                    }
                }
            }
            if (d.text.isBlank() && failed && !s.sttBusy) {
                item {
                    OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Tự nhập nội dung")
                    }
                }
            }
            if (s.audioClips.isNotEmpty()) {
                item {
                    AudioPlayerCard(vm.audioParts(s), formatBytes(d.audioBytes), onDelete = { confirmAudio = true })
                }
            } else if (d.text.isNotBlank()) {
                item {
                    Text(
                        "File ghi âm đã được xoá sau khi chuyển thành chữ để tiết kiệm dung lượng.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            if (drafts.isNotEmpty()) {
                item { SectionLabel("Đề xuất · bấm Lưu để giữ") }
                items(drafts, key = { "draft-${it.id}" }) { draft ->
                    DraftCard(
                        item = draft,
                        onType = { t -> vm.saveItem(draft.withType(t)) },
                        onEdit = { vm.openItem(draft.id) },
                        onDiscard = { vm.deleteItem(draft.id) },
                        onSave = { vm.confirm(listOf(draft.id)) },
                    )
                }
            } else if (s.extract == TaskStatus.DONE && saved.isEmpty() && d.text.isNotBlank()) {
                item {
                    Text(
                        "Không còn đề xuất nào. Dùng “Phân tích lại” nếu cần.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            if (saved.isNotEmpty()) {
                item { SectionLabel("Đã lưu từ ghi chú này") }
                items(saved, key = { "saved-${it.id}" }) { item ->
                    ItemRow(item, onOpen = { vm.openItem(item.id) }, onToggleDone = { vm.setDone(item.id, it) })
                }
            }
        }
    }

    if (editing && d != null) {
        TextEditorDialog(
            title = "Sửa lời ghi",
            initial = d.text,
            saveLabel = "Lưu & phân tích",
            hint = "Sau khi lưu, thư ký phân tích lại; các đề xuất chưa lưu được thay mới, mục đã lưu giữ nguyên.",
            onSave = { vm.editMemoText(id, it) },
            onDismiss = { editing = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Xoá ghi chú này?",
            text = buildString {
                append("Xoá lời nói và file ghi âm")
                append(if (drafts.isNotEmpty()) ", cùng ${drafts.size} đề xuất chưa lưu." else ".")
                if (saved.isNotEmpty()) append(" ${saved.size} mục đã lưu vẫn được giữ.")
            },
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { vm.deleteMemo(id) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmAudio) {
        ConfirmDialog(
            title = "Xoá file ghi âm?",
            text = "Giải phóng ${formatBytes(d?.audioBytes ?: 0)}. Lời nói đã chuyển thành chữ vẫn được giữ.",
            confirmLabel = "Xoá ghi âm",
            destructive = true,
            onConfirm = { vm.deleteAudio(id) },
            onDismiss = { confirmAudio = false },
        )
    }
}

@Composable
private fun DraftCard(item: Item, onType: (ItemType) -> Unit, onEdit: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            TypeSelector(item.type, onType)
            Spacer(Modifier.height(12.dp))
            Surface(onClick = onEdit, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        val sub = itemSubtitle(item)
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.type != ItemType.NOTE && item.details.isNotBlank()) {
                            Text(item.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.type == ItemType.EVENT && item.whenAt == null) {
                            Text("Chưa rõ thời gian — bấm để chọn", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item.amount?.let { Text(Money.format(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item.quote?.let {
                Text(
                    "“$it”",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Bỏ") }
                Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Lưu") }
            }
        }
    }
}

/** Four-way type switch: icon over a short label, so all four fit on a 320dp screen. */
@Composable
private fun TypeSelector(selected: ItemType, onSelect: (ItemType) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ItemType.entries.forEach { t ->
            val style = typeStyle(t)
            val on = t == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) style.container else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(t) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(style.icon, null, Modifier.size(20.dp), tint = if (on) style.content else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(
                    t.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) style.content else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Item editor: create, confirm, edit, complete, add to calendar, delete.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemEditScreen(vm: AppViewModel, id: String?, newType: ItemType) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val stored = id?.let { i -> items.firstOrNull { it.id == i } }
    val template = remember(id) {
        Item(
            id = ItemStore.newId(),
            type = newType,
            status = ItemStatus.OPEN,
            title = "",
            createdAt = System.currentTimeMillis(),
        ).let { if (newType == ItemType.EXPENSE) it.withType(ItemType.EXPENSE) else it }
    }
    val original = stored ?: if (id == null) template else null
    val ready by vm.itemsReady.collectAsStateWithLifecycle()
    if (original == null) {
        // Not loaded yet (opened from a reminder at cold start): wait. Loaded but missing: deleted elsewhere.
        if (ready) LaunchedEffect(Unit) { vm.back() }
        return
    }
    var edit by remember(original.id) { mutableStateOf(original) }
    var amountText by remember(original.id) { mutableStateOf(original.amount?.toString().orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    val parsedAmount = Money.parseVnd(amountText)
    val current = edit.copy(amount = if (edit.type == ItemType.EXPENSE) parsedAmount else null, title = edit.title.trim())
    val isNew = id == null
    val isDraft = original.status == ItemStatus.DRAFT
    val dirty = current != original.copy(title = original.title.trim())
    val valid = current.title.isNotBlank() && (current.type != ItemType.EXPENSE || parsedAmount != null)

    fun save(status: ItemStatus = if (isDraft) ItemStatus.OPEN else current.status) {
        // Completing goes through the store so a repeating task schedules its next instance.
        if (status != current.status && !isDraft) vm.saveAndSetDone(current, status == ItemStatus.DONE) else vm.saveItem(current.copy(status = status))
        vm.back()
    }
    if (dirty) BackHandler { confirmLeave = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "${current.type.label} mới" else if (isDraft) "Xác nhận đề xuất" else current.type.label) },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmLeave = true else vm.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { Sharing.shareText(context, current.title, ItemText.line(current, zone)) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Chia sẻ")
                        }
                        // No confirmation: the previous screen offers Undo.
                        IconButton(onClick = {
                            vm.deleteItem(original.id)
                            vm.back()
                        }) { Icon(Icons.Rounded.Delete, contentDescription = "Xoá mục") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!isNew && !isDraft && current.type == ItemType.TASK) {
                        val done = original.status == ItemStatus.DONE
                        OutlinedButton(
                            onClick = { save(if (done) ItemStatus.OPEN else ItemStatus.DONE) },
                            enabled = valid,
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) { Text(if (done) "Mở lại" else "Xong") }
                    }
                    Button(
                        onClick = { save() },
                        enabled = valid && (dirty || isNew || isDraft),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text(if (isDraft) "Xác nhận & lưu" else if (isNew) "Lưu" else "Lưu thay đổi") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TypeSelector(edit.type, { edit = edit.withType(it) })
            OutlinedTextField(
                value = edit.title,
                onValueChange = { edit = edit.copy(title = it) },
                label = { Text("Tiêu đề") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            if (edit.type == ItemType.EXPENSE) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Số tiền") },
                    placeholder = { Text("vd: 85000, 85k, 1tr2") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    supportingText = {
                        Text(parsedAmount?.let { "= ${Money.format(it)}" } ?: if (amountText.isBlank()) "Bắt buộc" else "Chưa đọc được số tiền")
                    },
                    isError = amountText.isNotBlank() && parsedAmount == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    EXPENSE_CATEGORIES.forEach { c ->
                        FilterChip(selected = edit.category == c, onClick = { edit = edit.copy(category = c) }, label = { Text(c) })
                    }
                }
            }

            if (edit.type != ItemType.NOTE || edit.whenAt != null) {
                WhenRow(
                    item = edit,
                    onPickDate = { pickDate = true },
                    onPickTime = { pickTime = true },
                    onClear = { edit = edit.copy(whenAt = null, allDay = false) },
                )
                ReminderHint(current)
                if ((edit.type == ItemType.TASK || edit.type == ItemType.EVENT) && edit.whenAt != null) {
                    RepeatRow(edit.recurrence) { edit = edit.copy(recurrence = it) }
                }
            }
            if (edit.type == ItemType.EVENT || edit.place != null) {
                OutlinedTextField(
                    value = edit.place.orEmpty(),
                    onValueChange = { edit = edit.copy(place = it.ifEmpty { null }) },
                    label = { Text("Địa điểm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (edit.type == ItemType.EVENT || edit.person != null) {
                OutlinedTextField(
                    value = edit.person.orEmpty(),
                    onValueChange = { edit = edit.copy(person = it.ifEmpty { null }) },
                    label = { Text("Với ai") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = edit.details,
                onValueChange = { edit = edit.copy(details = it) },
                label = { Text(if (edit.type == ItemType.NOTE) "Nội dung" else "Ghi chú thêm") },
                minLines = if (edit.type == ItemType.NOTE) 5 else 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            if ((edit.type == ItemType.EVENT || edit.type == ItemType.TASK) && edit.whenAt != null) {
                OutlinedButton(onClick = { addToCalendar(context, current) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Thêm vào ứng dụng Lịch")
                }
            }
            original.sourceId?.let { source -> SourceCard(vm, source, original.quote) }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickDate) {
        val initial = (edit.whenAt?.let { localDate(it) } ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = edit.whenAt?.takeIf { !edit.allDay }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
                        edit = edit.copy(
                            whenAt = (if (time != null) date.atTime(time) else date.atStartOfDay()).atZone(zone).toInstant().toEpochMilli(),
                            allDay = time == null,
                        )
                    }
                }) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Huỷ") } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val now = edit.whenAt?.takeIf { !edit.allDay }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Chọn giờ") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    val date = edit.whenAt?.let { localDate(it) } ?: LocalDate.now(zone)
                    edit = edit.copy(
                        whenAt = date.atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli(),
                        allDay = false,
                    )
                }) { Text("Chọn") }
            },
            dismissButton = {
                if (edit.whenAt != null && !edit.allDay) {
                    TextButton(onClick = {
                        pickTime = false
                        edit = edit.copy(whenAt = localDate(edit.whenAt!!).atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true)
                    }) { Text("Bỏ giờ") }
                } else {
                    TextButton(onClick = { pickTime = false }) { Text("Huỷ") }
                }
            },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = "Bỏ thay đổi?",
            text = "Những gì bạn vừa sửa sẽ không được lưu.",
            confirmLabel = "Bỏ",
            destructive = true,
            onConfirm = vm::back,
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun WhenRow(item: Item, onPickDate: () -> Unit, onPickTime: () -> Unit, onClear: () -> Unit) {
    val label = when (item.type) {
        ItemType.TASK -> "Hạn"
        ItemType.EVENT -> "Thời gian"
        ItemType.EXPENSE -> "Ngày chi"
        ItemType.NOTE -> "Ngày"
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickDate, modifier = Modifier.weight(1.3f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Rounded.Event, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.whenAt?.let { dayLabel(localDate(it)) } ?: "Chọn ngày", maxLines = 1)
            }
            if (item.type != ItemType.EXPENSE) {
                OutlinedButton(onClick = onPickTime, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.AccessTime, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.whenAt?.takeIf { !item.allDay }?.let { clock(it) } ?: "Giờ",
                        maxLines = 1,
                    )
                }
            }
            if (item.whenAt != null && item.type != ItemType.EXPENSE) {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Bỏ thời gian") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatRow(selected: Recurrence?, onSelect: (Recurrence?) -> Unit) {
    Column {
        Text("Lặp lại", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Không") })
            Recurrence.entries.forEach { r ->
                val icon: (@Composable () -> Unit)? = if (selected != r) null else {
                    { Icon(Icons.Rounded.Repeat, null, Modifier.size(16.dp)) }
                }
                FilterChip(selected = selected == r, onClick = { onSelect(r) }, label = { Text(r.label) }, leadingIcon = icon)
            }
        }
    }
}

@Composable
private fun ReminderHint(item: Item) {
    val context = LocalContext.current
    val at = Reminders.triggerAt(item.copy(status = ItemStatus.OPEN)) ?: return
    if (at <= System.currentTimeMillis()) return
    val notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            if (notificationsOn) "Sẽ nhắc lúc ${clock(at)} ${dayLabel(localDate(at)).lowercase()}" else "Thông báo đang tắt nên sẽ không có nhắc nhở",
            style = MaterialTheme.typography.bodySmall,
            color = if (notificationsOn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SourceCard(vm: AppViewModel, sourceId: String, quote: String?) {
    val memos by vm.memos.collectAsStateWithLifecycle()
    val memo = memos.firstOrNull { it.id == sourceId }
    Surface(
        onClick = { if (memo != null) vm.openSession(sourceId) },
        enabled = memo != null,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val style = modeStyle(com.vh.myrecap.core.SessionMode.MEMO)
            IconTile(style.icon, style.container, style.content, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (memo != null) "Từ ghi nhanh · ${formatDate(memo.createdAt)}" else "Từ ghi nhanh (đã xoá)",
                    style = MaterialTheme.typography.labelLarge,
                )
                quote?.let {
                    Text("“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (memo != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Opens the phone's calendar app with the event filled in; the user saves it there. */
private fun addToCalendar(context: Context, item: Item) {
    val at = item.whenAt ?: return
    val begin = if (item.allDay) localDate(at).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else at
    val end = if (item.allDay) begin + 24 * 3600_000L else begin + 3600_000L
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, item.title)
        .putExtra(CalendarContract.Events.DESCRIPTION, item.details)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, item.place.orEmpty())
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, item.allDay)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Không tìm thấy ứng dụng Lịch trên máy", Toast.LENGTH_SHORT).show()
    }
}
