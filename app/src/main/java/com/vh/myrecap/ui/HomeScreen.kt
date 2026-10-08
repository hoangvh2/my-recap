package com.vh.myrecap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.settings.AppSettings
import com.vh.myrecap.work.ProcessWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val resumeTick by vm.resumeTick.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(settings.defaultMode) }
    var micDenied by remember { mutableStateOf(false) }
    val record = rememberRecordAction(onDenied = { micDenied = true }) { vm.startRecording(mode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Recap", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Cài đặt") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            recorder.error?.let { err ->
                item { MessageCard("⚠️ $err", isError = true) }
            }
            if (micDenied) {
                item {
                    MessageCard("Cần quyền micro để ghi âm. Mở Cài đặt ứng dụng → Quyền → Micro.", isError = true) {
                        TextButton(onClick = { openAppSettings(context) }) { Text("Mở cài đặt") }
                    }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = SessionMode.entries
                    modes.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = {}, // no checkmark: leaves room for the label on narrow screens
                        ) { Text(m.label, fontSize = 15.sp, maxLines = 2, textAlign = TextAlign.Center) }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(180.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32F2F))
                            .clickable(role = Role.Button, onClickLabel = "Bắt đầu ghi âm", onClick = record),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("●", color = Color.White, fontSize = 48.sp)
                            Text("GHI ÂM", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1 chạm để bắt đầu · tự tách từng đoạn hội thoại",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Below the record button on purpose: recording must always be one tap away.
            item(key = "setup-$resumeTick") { SetupChecklist(context, settings, vm) }
            item {
                Text("Folder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "Chưa có folder nào. Mỗi lần bấm GHI ÂM tạo 1 folder; trong folder bấm \"Ghi tiếp\" để ghi thêm.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(sessions, key = { it.id }) { s -> SessionCard(s, settings) { vm.openSession(s.id) } }
        }
    }
}

@Composable
private fun SessionCard(session: Session, settings: AppSettings, onClick: () -> Unit) {
    val (status, isError) = statusText(session, settings)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                "${formatDate(session.createdAt)} · ${session.segments.size} đoạn · ${TimeFormat.clock(session.audioMs)}" +
                    if (session.bookmarksMs.isNotEmpty()) " · ⭐ ${session.bookmarksMs.size}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                status,
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** One-line processing status for lists and the folder header. Second value = is an error. */
fun statusText(s: Session, settings: AppSettings): Pair<String, Boolean> {
    val total = s.segments.size
    val sttRunning = s.segments.any { it.stt == TaskStatus.RUNNING }
    val error = s.error
    return when {
        s.isRecording -> "● Đang ghi" to false
        error != null && error.startsWith(ProcessWorker.RETRY_PREFIX) -> "⏳ $error" to false
        s.segments.any { it.stt == TaskStatus.ERROR } -> "⚠️ ${error ?: "Có đoạn lỗi khi chuyển văn bản"}" to true
        total == 0 -> "Chưa có đoạn hội thoại nào" to false
        s.sttBusy && !settings.sttConfig().isComplete -> "⚠️ Chưa có API key chuyển giọng nói — vào Cài đặt để nhập" to true
        s.sttBusy && !sttRunning && !settings.autoProcess && s.transcribedCount == 0 ->
            "Chưa chuyển văn bản — mở folder để xử lý" to false
        s.sttBusy -> "⏳ Đang chuyển văn bản ${s.transcribedCount}/$total" to false
        s.summaryBusy -> "⏳ Đang tóm tắt…" to false
        s.summaries.any { it.status == TaskStatus.ERROR } -> "⚠️ Tóm tắt lỗi — mở folder để thử lại" to true
        else -> {
            val done = s.summaries.count { it.status == TaskStatus.DONE }
            "✅ $total đoạn có transcript" + (if (done > 0) " · $done tóm tắt" else "") to false
        }
    }
}

/**
 * Returns a click handler that asks for the microphone (and notification) permission when needed,
 * then runs [start]. Recording must start from the visible UI (Android 14+ rule).
 */
@Composable
fun rememberRecordAction(onDenied: () -> Unit, start: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (micOk) start() else onDenied()
    }
    return {
        val needed = buildList {
            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) start() else launcher.launch(needed.toTypedArray())
    }
}

fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date(ms))

@Composable
fun MessageCard(text: String, isError: Boolean, action: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

private class SetupItem(
    val title: String,
    val detail: String,
    val done: Boolean,
    val actions: List<Pair<String, () -> Unit>>,
)

@Composable
private fun SetupChecklist(context: Context, settings: AppSettings, vm: AppViewModel) {
    val items = buildList {
        add(
            SetupItem(
                "Quyền micro",
                "Bắt buộc để ghi âm.",
                hasPermission(context, Manifest.permission.RECORD_AUDIO),
                listOf("Cấp quyền" to { openAppSettings(context) }),
            ),
        )
        add(
            SetupItem(
                "Thông báo",
                "Để có nút Tạm dừng/Đánh dấu/Dừng trên màn hình khoá.",
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
                listOf("Bật" to { openNotificationSettings(context) }),
            ),
        )
        add(
            SetupItem(
                "Không giới hạn pin",
                "Để Android không dừng ghi âm khi tắt màn hình.",
                isIgnoringBatteryOptimizations(context),
                listOf("Cho phép" to { requestIgnoreBatteryOptimizations(context) }),
            ),
        )
        add(
            SetupItem(
                "Chạy nền trên ${vendorName()}",
                vendorGuide(),
                settings.vendorGuideDone,
                listOf(
                    "Mở cài đặt app" to { openAppSettings(context) },
                    "Đã làm" to { vm.updateSettings { it.copy(vendorGuideDone = true) } },
                ),
            ),
        )
        add(
            SetupItem(
                "API key chuyển giọng nói",
                "Nhập Gemini API key (miễn phí tại aistudio.google.com) hoặc Groq key.",
                settings.sttConfig().isComplete,
                listOf("Mở Cài đặt" to vm::openSettings),
            ),
        )
    }
    val remaining = items.filterNot { it.done }
    if (remaining.isEmpty()) return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Thiết lập 1 lần (${items.size - remaining.size}/${items.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            for (item in remaining) {
                Column {
                    Text("○ ${item.title}", fontWeight = FontWeight.SemiBold)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        item.actions.forEachIndexed { i, (label, action) ->
                            if (i == 0) FilledTonalButton(onClick = action) { Text(label) }
                            else OutlinedButton(onClick = action) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}

private fun isTranssion(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("tecno", "infinix", "itel")

private fun vendorName(): String = if (isTranssion()) "Tecno (HiOS)" else Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

private fun vendorGuide(): String = if (isTranssion()) {
    "1) Phone Master → Quản lý tự khởi chạy (Auto-start) → bật My Recap.\n" +
        "2) Cài đặt → Pin → tắt quản lý tiết kiệm pin cho My Recap.\n" +
        "3) Mở đa nhiệm, kéo xuống trên thẻ My Recap để khoá (🔒)."
} else {
    "Cho phép tự khởi chạy/chạy nền và khoá app trong màn hình đa nhiệm (tên mục tuỳ hãng)."
}

fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openNotificationSettings(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

fun openAppSettings(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun tryStart(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
