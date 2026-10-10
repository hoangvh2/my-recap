package com.vh.myrecap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Money
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.ui.theme.Brand
import java.time.LocalDate
import java.time.YearMonth

/** Filter of the secretary list; null shows the overview. */
private val FILTERS: List<ItemType?> = listOf(null, ItemType.TASK, ItemType.EVENT, ItemType.EXPENSE, ItemType.NOTE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaTab(vm: AppViewModel) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val items by vm.items.collectAsStateWithLifecycle()
    val memos by vm.memos.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val resumeTick by vm.resumeTick.collectAsStateWithLifecycle()
    var filterIndex by rememberSaveable { mutableStateOf(0) }
    var micDenied by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val undo = rememberItemUndo(vm)
    val capture = rememberRecordAction(onDenied = { micDenied = true }) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.startMemo()
    }
    LaunchedEffect(vm.quickCapturePending) {
        if (vm.quickCapturePending) {
            vm.consumeQuickCapture()
            capture()
        }
    }

    val filter = FILTERS[filterIndex]
    val drafts = items.filter { it.status == ItemStatus.DRAFT }
    val saved = items.filter { it.status != ItemStatus.DRAFT }
    // Captures still being processed or that need the user; analysed ones live on as item sources.
    val pendingMemos = memos.filter { m ->
        m.sttBusy || m.extractBusy || m.extract == TaskStatus.ERROR || m.segments.any { it.stt == TaskStatus.ERROR } ||
            (m.extract == TaskStatus.DONE && m.error != null && items.none { it.sourceId == m.id })
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(undo) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Thư ký", style = MaterialTheme.typography.titleLarge)
                        Text(todayLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = vm::openSearch) { Icon(Icons.Rounded.Search, contentDescription = "Tìm kiếm") }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Cài đặt") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { CaptureHero(onCapture = capture, onAdd = vm::newItem) }
            recorder.error?.let { err -> item { Banner(StatusInfo(StatusKind.ERROR, err), "Đóng", RecorderState::clearError) } }
            if (micDenied) {
                item { Banner(StatusInfo(StatusKind.WARNING, "Cần quyền micro để ghi âm"), "Mở cài đặt", { openAppSettings(context) }) }
            }
            item(key = "setup-$resumeTick") { SetupCard(settings, vm) }

            if (pendingMemos.isNotEmpty() || drafts.isNotEmpty()) {
                item { SectionLabel("Chờ xử lý") }
            }
            items(pendingMemos, key = { "memo-${it.id}" }) { m ->
                MemoStatusRow(m, hasKey = settings.sttConfig().isComplete && settings.summaryConfig().isComplete, onOpen = { vm.openSession(m.id) })
            }
            val bySource = drafts.groupBy { it.sourceId }
            for ((source, group) in bySource) {
                item(key = "drafts-$source") {
                    DraftsCard(
                        memo = source?.let { id -> memos.firstOrNull { it.id == id } },
                        drafts = group,
                        onReview = { if (source != null) vm.openSession(source) else vm.openItem(group.first().id) },
                        onSaveAll = { vm.confirm(group.map { it.id }) },
                    )
                }
            }

            item {
                FilterRow(
                    selected = filterIndex,
                    counts = FILTERS.map { t -> saved.count { (t == null || it.type == t) && it.status == ItemStatus.OPEN } },
                    onSelect = { filterIndex = it },
                )
            }
            when (filter) {
                null -> overview(saved, vm, onShowExpenses = { filterIndex = FILTERS.indexOf(ItemType.EXPENSE) })
                ItemType.TASK -> taskList(saved.filter { it.type == ItemType.TASK }, vm)
                ItemType.EVENT -> eventList(saved.filter { it.type == ItemType.EVENT }, vm)
                ItemType.EXPENSE -> item { ExpenseView(saved.filter { it.type == ItemType.EXPENSE }, vm) }
                ItemType.NOTE -> noteList(saved.filter { it.type == ItemType.NOTE }, vm)
            }
        }
    }
}

@Composable
private fun CaptureHero(onCapture: () -> Unit, onAdd: (ItemType) -> Unit) {
    var addMenu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Brand.Navy, Brand.NavyRaised, Color(0xFF26389C))))
            .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordButton(onCapture, description = "Ghi nhanh")
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ghi nhanh", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "“Mai 3 giờ chiều họp anh Nam, trưa nay ăn phở 65 nghìn”",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                Box {
                    TextButton(onClick = { addMenu = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Thêm bằng tay", color = Color.White)
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        ItemType.entries.forEach { t ->
                            val style = typeStyle(t)
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                leadingIcon = { Icon(style.icon, null) },
                                onClick = { addMenu = false; onAdd(t) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoStatusRow(memo: Session, hasKey: Boolean, onOpen: () -> Unit) {
    val failed = memo.extract == TaskStatus.ERROR || memo.segments.any { it.stt == TaskStatus.ERROR }
    val (kind, text) = when {
        !hasKey && (memo.sttBusy || memo.extractBusy) -> StatusKind.WARNING to "Cần API key trong Cài đặt để phân tích"
        failed -> StatusKind.ERROR to (memo.error ?: "Chưa phân tích được")
        memo.sttBusy -> StatusKind.WORKING to "Đang chuyển giọng nói thành chữ…"
        memo.extractBusy -> StatusKind.WORKING to "Đang phân loại việc, lịch, chi tiêu…"
        else -> StatusKind.IDLE to (memo.error ?: "Không có gì cần lưu")
    }
    Surface(onClick = onOpen, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(typeStyleMemo().icon, typeStyleMemo().container, typeStyleMemo().content, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ghi nhanh lúc ${clock(memo.createdAt)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                StatusPill(StatusInfo(kind, text))
            }
        }
    }
}

@Composable
private fun typeStyleMemo() = modeStyle(com.vh.myrecap.core.SessionMode.MEMO)

@Composable
private fun DraftsCard(memo: Session?, drafts: List<Item>, onReview: () -> Unit, onSaveAll: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Brand.Bookmark),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${drafts.size} mục chờ xác nhận" + (memo?.let { " · ghi lúc ${clock(it.createdAt)}" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            drafts.take(4).forEach { d ->
                val style = typeStyle(d.type)
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(style.icon, style.container, style.content, size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = itemSubtitle(d)
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    d.amount?.let { Text(Money.format(it), style = MaterialTheme.typography.labelLarge) }
                }
            }
            if (drafts.size > 4) {
                Text("và ${drafts.size - 4} mục khác", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onReview, modifier = Modifier.weight(1f)) { Text("Xem & sửa") }
                Button(onClick = onSaveAll, modifier = Modifier.weight(1f)) { Text("Lưu tất cả") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(selected: Int, counts: List<Int>, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FILTERS.forEachIndexed { i, t ->
            val label = t?.label ?: "Tổng quan"
            val icon: (@Composable () -> Unit)? = if (t == null) null else {
                { Icon(typeStyle(t).icon, null, Modifier.size(18.dp)) }
            }
            FilterChip(
                selected = selected == i,
                onClick = { onSelect(i) },
                label = { Text(if (t != null && t != ItemType.EXPENSE && counts[i] > 0) "$label · ${counts[i]}" else label) },
                leadingIcon = icon,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

private fun dueKey(item: Item): Long = item.whenAt ?: Long.MAX_VALUE

/** Tasks and appointments by when they are due, plus this month's spending and recent notes. */
private fun LazyListScope.overview(saved: List<Item>, vm: AppViewModel, onShowExpenses: () -> Unit) {
    val today = LocalDate.now()
    val open = saved.filter { it.status == ItemStatus.OPEN && (it.type == ItemType.TASK || it.type == ItemType.EVENT) }
    val overdue = open.filter { isOverdue(it) }.sortedBy(::dueKey)
    val rest = open - overdue.toSet()
    val todayItems = rest.filter { it.whenAt != null && localDate(it.whenAt!!) == today }.sortedBy(::dueKey)
    val upcoming = rest.filter { it.whenAt != null && localDate(it.whenAt!!).isAfter(today) }.sortedBy(::dueKey)
    val undated = rest.filter { it.whenAt == null && it.type == ItemType.TASK }.sortedByDescending { it.createdAt }

    if (saved.isEmpty()) {
        item {
            EmptyState(
                Icons.Rounded.Inbox,
                "Chưa có gì",
                "Bấm Ghi nhanh và nói tự nhiên. Thư ký tách thành việc, lịch hẹn, chi tiêu, ghi chú — bạn chỉ cần xác nhận.",
            )
        }
        return
    }
    itemGroup("Quá hạn", overdue, vm)
    itemGroup("Hôm nay", todayItems, vm)
    itemGroup("Sắp tới", upcoming.take(12), vm, trailing = if (upcoming.size > 12) "+${upcoming.size - 12}" else null)
    itemGroup("Chưa có hạn", undated, vm)
    if (open.isEmpty()) {
        item { Text("Không có việc hay lịch hẹn nào đang mở.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp)) }
    }

    val month = YearMonth.now()
    val spent = saved.filter { it.type == ItemType.EXPENSE && it.whenAt != null && YearMonth.from(localDate(it.whenAt!!)) == month }
    if (spent.isNotEmpty()) {
        item { SectionLabel("Chi tiêu tháng ${month.monthValue}") }
        item {
            Surface(onClick = onShowExpenses, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Money.format(spent.sumOf { it.amount ?: 0 }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("${spent.size} khoản · chạm để xem chi tiết", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val notes = saved.filter { it.type == ItemType.NOTE }.sortedByDescending { it.createdAt }
    itemGroup("Ghi chú gần đây", notes.take(3), vm)
}

private fun LazyListScope.itemGroup(label: String, list: List<Item>, vm: AppViewModel, trailing: String? = null) {
    if (list.isEmpty()) return
    item(key = "label-$label") { SectionLabel(label, trailing = trailing) }
    items(list, key = { "$label-${it.id}" }) { item ->
        ItemRow(item, onOpen = { vm.openItem(item.id) }, onToggleDone = { vm.setDone(item.id, it) })
    }
}

private fun LazyListScope.doneGroup(done: List<Item>, vm: AppViewModel, label: String = "Đã xong") {
    if (done.isEmpty()) return
    item(key = "done-toggle-$label") {
        var open by rememberSaveable { mutableStateOf(false) }
        Column {
            TextButton(onClick = { open = !open }) {
                Text("$label · ${done.size}")
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    done.forEach { d -> ItemRow(d, onOpen = { vm.openItem(d.id) }, onToggleDone = { vm.setDone(d.id, it) }) }
                }
            }
        }
    }
}

private fun LazyListScope.taskList(tasks: List<Item>, vm: AppViewModel) {
    val today = LocalDate.now()
    val open = tasks.filter { it.status == ItemStatus.OPEN }
    if (tasks.isEmpty()) {
        item { EmptyState(Icons.Rounded.Inbox, "Chưa có việc nào", "Nói “nhớ gửi báo giá trước thứ Sáu” khi ghi nhanh, hoặc thêm bằng tay.") }
        return
    }
    val overdue = open.filter { isOverdue(it) }.sortedBy(::dueKey)
    val rest = open - overdue.toSet()
    itemGroup("Quá hạn", overdue, vm)
    itemGroup("Hôm nay", rest.filter { it.whenAt != null && localDate(it.whenAt!!) == today }.sortedBy(::dueKey), vm)
    itemGroup("Sắp tới", rest.filter { it.whenAt != null && localDate(it.whenAt!!).isAfter(today) }.sortedBy(::dueKey), vm)
    itemGroup("Chưa có hạn", rest.filter { it.whenAt == null }.sortedByDescending { it.createdAt }, vm)
    doneGroup(tasks.filter { it.status == ItemStatus.DONE }.sortedByDescending { it.doneAt ?: 0 }, vm)
}

private fun LazyListScope.eventList(events: List<Item>, vm: AppViewModel) {
    if (events.isEmpty()) {
        item { EmptyState(Icons.Rounded.Inbox, "Chưa có lịch hẹn", "Nói “thứ Năm tuần sau 9 giờ gặp khách ở văn phòng” khi ghi nhanh.") }
        return
    }
    val now = System.currentTimeMillis()
    val upcoming = events.filter { it.status == ItemStatus.OPEN && (it.whenAt == null || !isOverdue(it, now)) }.sortedBy(::dueKey)
    for ((day, group) in upcoming.groupBy { it.whenAt?.let { w -> dayLabel(localDate(w)) } ?: "Chưa có ngày" }) {
        itemGroup(day, group, vm)
    }
    doneGroup(events.filter { it !in upcoming }.sortedByDescending { it.whenAt ?: 0 }, vm, label = "Đã qua")
}

private fun LazyListScope.noteList(notes: List<Item>, vm: AppViewModel) {
    if (notes.isEmpty()) {
        item { EmptyState(Icons.Rounded.Inbox, "Chưa có ghi chú", "Ý tưởng, thông tin cần nhớ… nói ra khi ghi nhanh.") }
        return
    }
    itemGroup("Mới nhất", notes.sortedByDescending { it.createdAt }, vm)
}

/** Month total, spending by category and the month's expenses by day. */
@Composable
private fun ExpenseView(expenses: List<Item>, vm: AppViewModel) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val inMonth = expenses.filter { it.whenAt != null && YearMonth.from(localDate(it.whenAt!!)) == month }
        .sortedByDescending { it.whenAt }
    val total = inMonth.sumOf { it.amount ?: 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Tháng trước")
                    }
                    Text(
                        "Tháng ${month.monthValue}/${month.year}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Tháng sau")
                    }
                }
                Text(
                    Money.format(total),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "${inMonth.size} khoản",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (inMonth.isNotEmpty()) {
                    val context = LocalContext.current
                    TextButton(
                        onClick = { Sharing.shareExpensesCsv(context, month.toString(), inMonth) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Xuất CSV")
                    }
                }
                val byCategory = inMonth.groupBy { it.category ?: "Khác" }.mapValues { (_, l) -> l.sumOf { it.amount ?: 0 } }
                    .toList().sortedByDescending { it.second }
                if (byCategory.isNotEmpty()) Spacer(Modifier.height(12.dp))
                byCategory.forEach { (cat, sum) ->
                    CategoryBar(cat, sum, if (total > 0) sum.toFloat() / total else 0f)
                }
            }
        }
        if (inMonth.isEmpty()) {
            Text(
                "Chưa có khoản chi nào trong tháng này.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
        for ((day, group) in inMonth.groupBy { localDate(it.whenAt!!) }) {
            SectionLabel(dayLabel(day), trailing = Money.format(group.sumOf { it.amount ?: 0 }))
            group.forEach { e -> ItemRow(e, onOpen = { vm.openItem(e.id) }, onToggleDone = null) }
        }
    }
}

@Composable
private fun CategoryBar(label: String, amount: Long, fraction: Float) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(Money.format(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(Brand.Bookmark))
        }
    }
}
