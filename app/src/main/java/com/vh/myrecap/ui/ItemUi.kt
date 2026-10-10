package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemExtraction
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One icon and colour pair per item type, used everywhere items appear. */
@Composable
fun typeStyle(type: ItemType): ModeStyle {
    val c = MaterialTheme.colorScheme
    return when (type) {
        ItemType.TASK -> ModeStyle(Icons.Rounded.TaskAlt, c.primaryContainer, c.onPrimaryContainer)
        ItemType.EVENT -> ModeStyle(Icons.Rounded.Event, c.secondaryContainer, c.onSecondaryContainer)
        ItemType.EXPENSE -> ModeStyle(Icons.Rounded.Payments, Color(0x2EF5A524), Color(0xFF8A5A00))
        ItemType.NOTE -> ModeStyle(Icons.Rounded.Lightbulb, c.tertiaryContainer, c.onTertiaryContainer)
    }
}

private val zone: ZoneId get() = ZoneId.systemDefault()
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DM = DateTimeFormatter.ofPattern("dd/MM")

fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/** "Hôm nay", "Mai", "Hôm qua", "Thứ Năm 15/10" — how people say dates. */
fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when (date) {
    today -> "Hôm nay"
    today.plusDays(1) -> "Mai"
    today.minusDays(1) -> "Hôm qua"
    else -> ItemExtraction.weekday(date) + " " + date.format(DM) + if (date.year != today.year) "/${date.year}" else ""
}

/** "Mai · 15:00" or "Thứ Năm 15/10 · cả ngày". */
fun whenLabel(item: Item): String? {
    val at = item.whenAt ?: return null
    val z = Instant.ofEpochMilli(at).atZone(zone)
    val day = dayLabel(z.toLocalDate())
    return if (item.allDay) day else "$day · ${z.format(HM)}"
}

fun isOverdue(item: Item, now: Long = System.currentTimeMillis()): Boolean {
    val at = item.whenAt ?: return false
    if (item.status != ItemStatus.OPEN || item.type == ItemType.NOTE || item.type == ItemType.EXPENSE) return false
    return if (item.allDay) localDate(at).isBefore(LocalDate.now(zone)) else at < now
}

/** Secondary line of an item row. */
fun itemSubtitle(item: Item): String = listOfNotNull(
    if (item.type == ItemType.EXPENSE) item.category else whenLabel(item),
    if (item.type == ItemType.EXPENSE) whenLabel(item)?.substringBefore(" · ") else null,
    item.place,
    item.person.takeIf { item.type == ItemType.EVENT },
    item.details.takeIf { it.isNotBlank() && item.type == ItemType.NOTE }?.lineSequence()?.firstOrNull(),
).joinToString("  ·  ")

/**
 * A list row for an item. Tasks get a check circle that completes them in one tap; everything
 * else opens the item.
 */
@Composable
fun ItemRow(item: Item, onOpen: () -> Unit, onToggleDone: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val style = typeStyle(item.type)
    val haptic = LocalHapticFeedback.current
    val done = item.status == ItemStatus.DONE
    val overdue = isOverdue(item)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.type == ItemType.TASK && onToggleDone != null) {
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleDone(!done)
                }) {
                    Icon(
                        if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (done) "Bỏ đánh dấu xong" else "Đánh dấu xong",
                        tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(26.dp),
                    )
                }
            } else {
                Box(Modifier.padding(8.dp)) { IconTile(style.icon, style.container, style.content, size = 34.dp) }
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val sub = itemSubtitle(item)
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item.amount?.let {
                Spacer(Modifier.width(10.dp))
                Text(Money.format(it), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Snackbar host that offers "Hoàn tác" after an item is deleted or a proposal discarded. */
@Composable
fun rememberItemUndo(vm: AppViewModel): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    val deleted = vm.lastDeletedItem
    LaunchedEffect(deleted?.id) {
        val item = deleted ?: return@LaunchedEffect
        val label = if (item.status == ItemStatus.DRAFT) "Đã bỏ “${item.title}”" else "Đã xoá “${item.title}”"
        val result = host.showSnackbar(label, actionLabel = "Hoàn tác", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) vm.undoDeleteItem(item) else vm.clearDeletedItem(item)
    }
    return host
}
