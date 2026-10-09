package com.vh.myrecap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.recorder.RecorderUi

/**
 * Full-screen recording controls sized for glance-and-tap use during a meeting. Also shown over
 * the lock screen, so it deliberately contains nothing but the current recording.
 */
@Composable
fun RecordingScreen(
    ui: RecorderUi,
    onTogglePause: () -> Unit,
    onBookmark: () -> Unit,
    onStop: () -> Unit,
) {
    var confirmStop by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ui.folderTitle.ifBlank { ui.mode.label },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                ui.paused -> MaterialTheme.colorScheme.outline
                                ui.speaking || !ui.autoSplit -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                        ),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    when {
                        ui.stopping -> "Đang lưu…"
                        ui.paused -> "Tạm dừng"
                        !ui.autoSplit -> "Đang ghi"
                        ui.speaking -> "Đang nghe hội thoại"
                        else -> "Chờ giọng nói…"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                TimeFormat.clock(ui.elapsedMs),
                fontSize = 72.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LinearProgressIndicator(
                progress = { if (ui.paused) 0f else ui.level },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics { contentDescription = "Mức âm thanh micro" },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "${ui.clips} đoạn đã lưu" +
                    if (ui.skippedMs >= 1_000) " · bỏ ${TimeFormat.clock(ui.skippedMs)} im lặng" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onTogglePause,
                    enabled = !ui.stopping,
                    modifier = Modifier
                        .weight(1f)
                        .height(112.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    BigLabel(if (ui.paused) "▶" else "⏸", if (ui.paused) "Tiếp tục" else "Tạm dừng")
                }
                FilledTonalButton(
                    onClick = onBookmark,
                    enabled = !ui.stopping && !ui.paused,
                    modifier = Modifier
                        .weight(1f)
                        .height(112.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    BigLabel("⭐", "Đánh dấu (${ui.bookmarks})")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { confirmStop = true },
                enabled = !ui.stopping,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("■  Dừng & lưu", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Có thể tắt màn hình — vẫn ghi âm. App tự tách mỗi lượt hỏi-đáp thành 1 đoạn khi có khoảng lặng " +
                    "và bỏ phần im lặng trước khi gửi đi. Bấm nút nguồn để mở lại màn hình này trên màn hình khoá. " +
                    "Rung 1 lần = đã đánh dấu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Dừng ghi âm?") },
            text = { Text("Các đoạn được lưu vào folder và tự chuyển thành văn bản. Muốn tóm tắt: mở folder, chọn đoạn, bấm Tóm tắt.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmStop = false
                        onStop()
                    },
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Dừng & lưu", fontSize = 18.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }, modifier = Modifier.height(56.dp)) {
                    Text("Tiếp tục ghi", fontSize = 18.sp)
                }
            },
        )
    }
}

@Composable
private fun BigLabel(icon: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 34.sp)
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}
