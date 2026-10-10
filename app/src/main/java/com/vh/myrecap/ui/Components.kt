package com.vh.myrecap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.settings.AppSettings
import com.vh.myrecap.work.ProcessWorker

/** Icon and accent colours per recording type, used consistently in lists and headers. */
data class ModeStyle(val icon: ImageVector, val container: Color, val content: Color)

@Composable
fun modeStyle(mode: SessionMode): ModeStyle {
    val c = MaterialTheme.colorScheme
    return when (mode) {
        SessionMode.INTERVIEW -> ModeStyle(Icons.Rounded.RecordVoiceOver, c.primaryContainer, c.onPrimaryContainer)
        SessionMode.MEETING -> ModeStyle(Icons.Rounded.Groups, c.secondaryContainer, c.onSecondaryContainer)
        SessionMode.CUSTOM -> ModeStyle(Icons.Rounded.EditNote, c.tertiaryContainer, c.onTertiaryContainer)
        SessionMode.MEMO -> ModeStyle(Icons.Rounded.Mic, c.secondaryContainer, c.onSecondaryContainer)
    }
}

@Composable
fun IconTile(icon: ImageVector, container: Color, content: Color, size: Dp = 44.dp, contentDescription: String? = null) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

enum class StatusKind { RECORDING, WORKING, WARNING, ERROR, DONE, IDLE }

data class StatusInfo(val kind: StatusKind, val text: String)

/** Processing state of a folder in plain words, for list rows and the folder header. */
fun folderStatus(s: Session, settings: AppSettings): StatusInfo {
    val total = s.segments.size
    val error = s.error
    val sttRunning = s.segments.any { it.stt == TaskStatus.RUNNING }
    return when {
        s.isRecording -> StatusInfo(StatusKind.RECORDING, "Đang ghi")
        error != null && error.startsWith(ProcessWorker.RETRY_PREFIX) ->
            StatusInfo(StatusKind.WORKING, "Mạng chập chờn, đang thử lại")
        s.segments.any { it.stt == TaskStatus.ERROR } -> StatusInfo(StatusKind.ERROR, error ?: "Có đoạn chưa chuyển được văn bản")
        total == 0 -> StatusInfo(StatusKind.IDLE, "Chưa có đoạn hội thoại")
        s.sttBusy && !settings.sttConfig().isComplete ->
            StatusInfo(StatusKind.WARNING, "Cần nhập API key để chuyển giọng nói thành văn bản")
        s.sttBusy && !sttRunning && !settings.autoProcess && s.transcribedCount == 0 ->
            StatusInfo(StatusKind.IDLE, "Chưa chuyển văn bản")
        s.sttBusy -> StatusInfo(StatusKind.WORKING, "Đang chuyển văn bản ${s.transcribedCount}/$total")
        s.summaryBusy -> StatusInfo(StatusKind.WORKING, "Đang tóm tắt")
        s.summaries.any { it.status == TaskStatus.ERROR } -> StatusInfo(StatusKind.ERROR, "Tóm tắt chưa thành công")
        else -> {
            val done = s.summaries.count { it.status == TaskStatus.DONE }
            StatusInfo(StatusKind.DONE, if (done > 0) "$done bản tóm tắt" else "Sẵn sàng tóm tắt")
        }
    }
}

/** Compact status label with an icon or spinner; colour carries meaning, text carries detail. */
@Composable
fun StatusPill(info: StatusInfo, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val (container, content) = when (info.kind) {
        StatusKind.RECORDING -> c.errorContainer to c.onErrorContainer
        StatusKind.WORKING -> c.primaryContainer to c.onPrimaryContainer
        StatusKind.WARNING -> Color(0x33F5A524) to c.onSurface
        StatusKind.ERROR -> c.errorContainer to c.onErrorContainer
        StatusKind.DONE -> c.surfaceContainerHigh to c.onSurfaceVariant
        StatusKind.IDLE -> c.surfaceContainerHigh to c.onSurfaceVariant
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (info.kind) {
            StatusKind.RECORDING -> Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            StatusKind.WORKING -> CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.6.dp, color = content)
            StatusKind.WARNING -> Icon(Icons.Rounded.WarningAmber, null, Modifier.size(14.dp), tint = Color(0xFFE09400))
            StatusKind.ERROR -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(14.dp), tint = content)
            StatusKind.DONE -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = content)
            StatusKind.IDLE -> Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = content)
        }
        Spacer(Modifier.width(6.dp))
        Text(info.text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Inline notice with an optional action; used for errors and things that need the user. */
@Composable
fun Banner(
    info: StatusInfo,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val (container, content, icon) = when (info.kind) {
        StatusKind.ERROR -> Triple(c.errorContainer, c.onErrorContainer, Icons.Rounded.ErrorOutline)
        StatusKind.WARNING -> Triple(Color(0x26F5A524), c.onSurface, Icons.Rounded.WarningAmber)
        else -> Triple(c.primaryContainer, c.onPrimaryContainer, Icons.Rounded.Schedule)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (info.kind == StatusKind.WORKING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
            } else {
                Icon(icon, null, Modifier.size(20.dp), tint = if (info.kind == StatusKind.WARNING) Color(0xFFE09400) else content)
            }
            Spacer(Modifier.width(12.dp))
            Text(info.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconTile(icon, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, size = 56.dp)
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (text != null) {
            { Text(text, style = MaterialTheme.typography.bodyMedium) }
        } else {
            null
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}

@Composable
fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Đổi tên folder") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onRename(value.text)
                    onDismiss()
                },
                enabled = value.text.isNotBlank(),
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
