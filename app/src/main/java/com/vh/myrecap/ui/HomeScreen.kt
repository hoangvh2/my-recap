package com.vh.myrecap.ui

import android.Manifest
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import com.vh.myrecap.core.ItemStatus
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.Session
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.settings.AppSettings
import com.vh.myrecap.ui.theme.Brand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Two home tabs: the secretary (quick captures and items) and recordings (folders). */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val memos by vm.memos.collectAsStateWithLifecycle()
    val tab = settings.homeTab.coerceIn(0, 1)
    val waiting = items.count { it.status == ItemStatus.DRAFT } + memos.count { it.extractBusy }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { vm.setHomeTab(0) },
                    icon = {
                        BadgedBox(badge = { if (waiting > 0) Badge { Text("$waiting") } }) {
                            Icon(if (tab == 0) Icons.Rounded.Inbox else Icons.Outlined.Inbox, null)
                        }
                    },
                    label = { Text("Thư ký") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { vm.setHomeTab(1) },
                    icon = { Icon(if (tab == 1) Icons.Rounded.Folder else Icons.Outlined.Folder, null) },
                    label = { Text("Ghi âm") },
                )
            }
        },
    ) { outer ->
        Box(Modifier.padding(bottom = outer.calculateBottomPadding())) {
            if (tab == 0) AgendaTab(vm) else FoldersTab(vm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersTab(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val resumeTick by vm.resumeTick.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(settings.defaultMode) }
    var micDenied by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val record = rememberRecordAction(onDenied = { micDenied = true }) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.startRecording(mode)
    }

    // Delete with Undo instead of a confirmation dialog: one tap, recoverable for a few seconds.
    val deleted = vm.lastDeleted
    LaunchedEffect(deleted?.id) {
        val target = deleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar("Đã xoá “${target.title}”", actionLabel = "Hoàn tác", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete(target.id) else vm.commitDelete(target.id)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ghi âm", style = MaterialTheme.typography.titleLarge)
                        Text(
                            todayLabel(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { RecordHero(mode, onMode = { mode = it }, onRecord = record) }
            recorder.error?.let { err ->
                item { Banner(StatusInfo(StatusKind.ERROR, err), "Đóng", RecorderState::clearError) }
            }
            if (micDenied) {
                item {
                    Banner(StatusInfo(StatusKind.WARNING, "Cần quyền micro để ghi âm"), "Mở cài đặt", { openAppSettings(context) })
                }
            }
            item(key = "setup-$resumeTick") { SetupCard(settings, vm) }

            if (sessions.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.FolderOpen,
                        "Chưa có buổi ghi nào",
                        "Mỗi buổi phỏng vấn hay cuộc họp là một folder: các đoạn hội thoại, transcript và tóm tắt nằm gọn trong đó.",
                    )
                }
            }
            for ((label, group) in groupByDay(sessions)) {
                item(key = "h-$label") {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
                    )
                }
                items(group, key = { it.id }) { s ->
                    FolderRow(
                        session = s,
                        status = folderStatus(s, settings),
                        onOpen = { vm.openSession(s.id) },
                        onRename = { renaming = s },
                        onShare = {
                            scope.launch {
                                val text = vm.folderShareText(s.id)
                                if (text == null) {
                                    Toast.makeText(context, "Chưa có nội dung để chia sẻ", Toast.LENGTH_SHORT).show()
                                } else {
                                    Sharing.shareText(context, s.title, text)
                                }
                            }
                        },
                        onDelete = { vm.requestDelete(s.id) },
                    )
                }
            }
        }
    }

    renaming?.let { s -> RenameDialog(s.title, onRename = { vm.rename(s.id, it) }, onDismiss = { renaming = null }) }
}

private fun modeHint(mode: SessionMode) = when (mode) {
    SessionMode.INTERVIEW -> "Tự tách từng lượt hỏi–đáp. Có thể tắt màn hình."
    SessionMode.MEETING -> "Ghi biên bản, quyết định và việc cần làm."
    SessionMode.CUSTOM -> "Ghi tự do, tóm tắt theo yêu cầu của bạn."
    SessionMode.MEMO -> "Nói nhanh việc, lịch hẹn, khoản chi."
}

@Composable
private fun RecordHero(mode: SessionMode, onMode: (SessionMode) -> Unit, onRecord: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Brand.Navy, Brand.NavyRaised, Color(0xFF26389C))))
            .padding(20.dp),
    ) {
        Column {
            ModeSwitch(mode, onMode)
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordButton(onRecord)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bắt đầu ghi", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(modeHint(mode), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: SessionMode, onMode: (SessionMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(4.dp),
    ) {
        SessionMode.entries.filter { it.isFolderMode }.forEach { m ->
            val selected = m == mode
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onMode(m) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Brand.Navy else Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun RecordButton(onClick: () -> Unit, description: String = "Bắt đầu ghi âm") {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    Box(
        Modifier
            .size(84.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(66.dp).clip(CircleShape).background(Brand.Record), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(
    session: Session,
    status: StatusInfo,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val style = modeStyle(session.mode)
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            IconTile(style.icon, style.container, style.content)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(
                        clock(session.createdAt),
                        "${session.segments.size} đoạn",
                        TimeFormat.clock(session.audioMs),
                        session.bookmarksMs.size.takeIf { it > 0 }?.let { "$it đánh dấu" },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                StatusPill(status)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Tuỳ chọn", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Đổi tên") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Chia sẻ") },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Xoá", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

private class SetupStep(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val detail: String,
    val done: Boolean,
    val action: String,
    val onAction: () -> Unit,
)

@Composable
fun SetupCard(settings: AppSettings, vm: AppViewModel) {
    val context = LocalContext.current
    val steps = listOf(
        SetupStep(
            Icons.Rounded.Mic, "Quyền micro", "Bắt buộc để ghi âm.",
            hasPermission(context, Manifest.permission.RECORD_AUDIO), "Cấp quyền",
        ) { openAppSettings(context) },
        SetupStep(
            Icons.Rounded.Notifications, "Thông báo", "Hiện nút điều khiển trên màn hình khoá.",
            NotificationManagerCompat.from(context).areNotificationsEnabled(), "Bật",
        ) { openNotificationSettings(context) },
        SetupStep(
            Icons.Rounded.BatteryChargingFull, "Không giới hạn pin", "Để Android không dừng ghi âm khi tắt màn hình.",
            isIgnoringBatteryOptimizations(context), "Cho phép",
        ) { requestIgnoreBatteryOptimizations(context) },
        SetupStep(
            Icons.Rounded.PhoneAndroid, "Chạy nền trên ${vendorName()}", vendorGuide(),
            settings.vendorGuideDone, "Đã làm",
        ) { vm.updateSettings { it.copy(vendorGuideDone = true) } },
        SetupStep(
            Icons.Rounded.Key, "API key", "Gemini (miễn phí tại aistudio.google.com) hoặc Groq.",
            settings.sttConfig().isComplete, "Nhập key",
        ) { vm.openSettings() },
    )
    val remaining = steps.count { !it.done }
    if (remaining == 0) return
    // Collapsed by default so the folder list stays in view; the header still shows what is left.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Icons.Rounded.Tune, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hoàn tất thiết lập", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Còn $remaining/${steps.size} bước để ghi âm ổn định",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 14.dp, end = 10.dp, bottom = 10.dp)) {
                    steps.forEach { step -> SetupRow(step) }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(step: SetupStep) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (step.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = if (step.done) "Đã xong" else "Chưa xong",
            tint = if (step.done) c.primary else c.outline,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (step.done) c.onSurfaceVariant else c.onSurface,
            )
            if (!step.done) {
                Text(step.detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        if (!step.done) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = step.onAction, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(step.action) }
        }
    }
}

private val dayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
private val clockFormat = SimpleDateFormat("HH:mm", Locale.ROOT)

fun clock(ms: Long): String = clockFormat.format(Date(ms))

fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date(ms))

private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Folders grouped under "Hôm nay", "Hôm qua" or the date, newest first. */
fun groupByDay(sessions: List<Session>): List<Pair<String, List<Session>>> {
    val today = startOfDay(System.currentTimeMillis())
    val yesterday = today - 24 * 3600_000L
    return sessions.groupBy { startOfDay(it.createdAt) }.toSortedMap(compareByDescending { it }).map { (day, list) ->
        val label = when (day) {
            today -> "Hôm nay"
            yesterday -> "Hôm qua"
            else -> dayFormat.format(Date(day))
        }
        label to list
    }
}

fun todayLabel(): String {
    val cal = Calendar.getInstance()
    val weekday = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "Thứ Hai"
        Calendar.TUESDAY -> "Thứ Ba"
        Calendar.WEDNESDAY -> "Thứ Tư"
        Calendar.THURSDAY -> "Thứ Năm"
        Calendar.FRIDAY -> "Thứ Sáu"
        Calendar.SATURDAY -> "Thứ Bảy"
        else -> "Chủ nhật"
    }
    return "$weekday, ${cal.get(Calendar.DAY_OF_MONTH)} tháng ${cal.get(Calendar.MONTH) + 1}"
}
