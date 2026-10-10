package com.vh.myrecap.ui

import android.app.Activity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.recorder.RecorderUi
import com.vh.myrecap.ui.theme.BeVietnamPro
import com.vh.myrecap.ui.theme.Brand

private const val WAVE_BARS = 46

/**
 * Full-screen recording controls on a dark background, readable at a glance and usable on the lock
 * screen. Shows only the current recording — never the folder list — because it can appear over the
 * keyguard.
 */
@Composable
fun RecordingScreen(
    ui: RecorderUi,
    onTogglePause: () -> Unit,
    onBookmark: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val memo = ui.mode == SessionMode.MEMO
    var confirmStop by remember { mutableStateOf(false) }
    LightSystemBars()

    // Rolling history of mic levels (5 per second) drawn as a waveform: real input, not decoration.
    val levels = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } } }
    LaunchedEffect(ui.elapsedMs, ui.paused) {
        levels.add(if (ui.paused) 0f else ui.level)
        while (levels.size > WAVE_BARS) levels.removeAt(0)
    }

    val listening = ui.speaking || !ui.autoSplit
    val stateLabel = when {
        ui.stopping -> "Đang lưu"
        ui.paused -> "Tạm dừng"
        memo -> if (ui.speaking) "Đang nghe" else "Đang ghi"
        !ui.autoSplit -> "Đang ghi"
        ui.speaking -> "Đang nghe"
        else -> "Chờ giọng nói"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.NavyDeep, Brand.Navy, Color(0xFF101C52)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ui.folderTitle.ifBlank { ui.mode.label },
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            StateChip(stateLabel, active = !ui.paused && !ui.stopping && listening)

            Spacer(Modifier.weight(0.8f))
            Text(
                TimeFormat.clock(ui.elapsedMs),
                fontFamily = BeVietnamPro,
                fontWeight = FontWeight.Light,
                fontSize = 68.sp,
                color = Color.White,
                style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            )
            Spacer(Modifier.height(20.dp))
            Waveform(levels, dimmed = ui.paused, modifier = Modifier.fillMaxWidth().height(84.dp))
            Spacer(Modifier.height(28.dp))
            if (memo) {
                Text(
                    "Nói tự nhiên: việc cần làm, lịch hẹn, khoản chi, điều cần nhớ.\nXong thì bấm Xong — thư ký sẽ tách và hỏi bạn xác nhận.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("${ui.clips}", "đoạn đã lưu")
                    Stat("${ui.bookmarks}", "đánh dấu")
                    Stat(TimeFormat.clock(ui.skippedMs), "im lặng đã bỏ")
                }
            }
            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (memo) {
                    RoundControl(
                        icon = Icons.Rounded.Close,
                        label = "Huỷ",
                        description = "Huỷ ghi nhanh",
                        size = 64.dp,
                        container = Color.White.copy(alpha = 0.12f),
                        tint = Color.White,
                        enabled = !ui.stopping,
                        onClick = onDiscard,
                    )
                } else RoundControl(
                    icon = if (ui.bookmarks > 0) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    label = "Đánh dấu",
                    size = 64.dp,
                    container = Color.White.copy(alpha = 0.12f),
                    tint = Brand.Bookmark,
                    enabled = !ui.stopping && !ui.paused,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBookmark()
                    },
                )
                RoundControl(
                    icon = if (memo) Icons.Rounded.Check else Icons.Rounded.Stop,
                    label = if (memo) "Xong" else "Dừng",
                    description = if (memo) "Xong, phân tích ghi chú" else "Dừng ghi âm",
                    size = 84.dp,
                    container = Brand.Record,
                    tint = Color.White,
                    enabled = !ui.stopping,
                    // A quick capture ends in one tap; a long recording asks first.
                    onClick = { if (memo) onStop() else confirmStop = true },
                )
                RoundControl(
                    icon = if (ui.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    label = if (ui.paused) "Tiếp tục" else "Tạm dừng",
                    size = 64.dp,
                    container = Color.White.copy(alpha = 0.12f),
                    tint = Color.White,
                    enabled = !ui.stopping,
                    onClick = onTogglePause,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Có thể tắt màn hình. Bấm nút nguồn để quay lại đây, hoặc dùng nút trong thông báo.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = "Kết thúc buổi ghi?",
            text = "Các đoạn đã lưu vào folder và sẽ tự chuyển thành văn bản. Bạn có thể ghi tiếp vào folder này sau.",
            confirmLabel = "Kết thúc",
            destructive = true,
            onConfirm = onStop,
            onDismiss = { confirmStop = false },
        )
    }
}

/** Light status/navigation bar icons while this dark screen is shown. */
@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
            previousNav?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}

@Composable
private fun StateChip(label: String, active: Boolean) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (active) alpha else 1f)
                .clip(CircleShape)
                .background(if (active) Brand.Record else Color.White.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun Waveform(levels: List<Float>, dimmed: Boolean, modifier: Modifier) {
    val active = Brush.verticalGradient(listOf(Brand.Cyan, Brand.Indigo, Brand.Violet))
    Canvas(modifier) {
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (WAVE_BARS - 1)) / WAVE_BARS).coerceAtLeast(2f)
        val minHeight = 4.dp.toPx()
        levels.forEachIndexed { i, level ->
            // sqrt lifts quiet speech so the bars move visibly in a meeting room.
            val h = (minHeight + (size.height - minHeight) * kotlin.math.sqrt(level.coerceIn(0f, 1f))).coerceAtMost(size.height)
            val x = i * (barWidth + gap)
            val recent = i.toFloat() / WAVE_BARS
            drawRoundRect(
                brush = active,
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
                alpha = if (dimmed) 0.25f else 0.35f + 0.65f * recent,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = Color.White,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    label: String,
    size: Dp,
    container: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    description: String = label,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.42f))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
    }
}
