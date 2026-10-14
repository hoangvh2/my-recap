package com.vh.myrecap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.vh.myrecap.core.ItemTag
import com.vh.myrecap.core.canHaveTag
import com.vh.myrecap.core.itemTag
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
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
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
    item.recurrence?.label?.lowercase()?.let { "lặp $it" },
    if (item.type == ItemType.EXPENSE) whenLabel(item)?.substringBefore(" · ") else null,
    item.place,
    item.person.takeIf { item.type == ItemType.EVENT },
    item.details.takeIf { it.isNotBlank() && item.type == ItemType.NOTE }?.lineSequence()?.firstOrNull(),
).joinToString("  ·  ")

/**
 * A list row for an item. Swipe right marks a task or appointment done (reopens it when done), swipe left
 * asks, then deletes (with Undo); there is deliberately no check circle to hit by mistake while scrolling. A long press
 * offers the colour tags; the tag shows as a diagonal corner at the top-right.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ItemRow(
    item: Item,
    onOpen: () -> Unit,
    onToggleDone: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onTag: ((ItemTag?) -> Unit)? = null,
) {
    val share = LocalShareInfo.current
    // Someone else's item: their name on it, an indigo edge, and no actions (only the owner changes it).
    val owner = share.owners[item.id]
    val audience = share.audiences[item.id]
    val style = typeStyle(item.type)
    val haptic = LocalHapticFeedback.current
    val done = item.status == ItemStatus.DONE
    val overdue = isOverdue(item)
    val tag = item.itemTag.takeIf { item.type.canHaveTag() }
    val actionable = item.type == ItemType.TASK || item.type == ItemType.EVENT
    val canDone = actionable && onToggleDone != null && owner == null
    val canDelete = onDelete != null && owner == null
    val canTag = onTag != null && owner == null && item.type.canHaveTag()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val doneNow by rememberUpdatedState(done)
    val toggleNow by rememberUpdatedState(onToggleDone)
    val deleteNow by rememberUpdatedState(onDelete)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> toggleNow?.let { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it(!doneNow) }
                SwipeToDismissBoxValue.EndToStart -> if (deleteNow != null) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); confirmDelete = true }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // the row snaps back; a delete waits for the dialog, then the list drops it
        },
    )
    val rowShape = RoundedCornerShape(18.dp)
    val row: @Composable () -> Unit = {
        Surface(
            shape = rowShape,
            color = if (owner != null) OtherTint.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surfaceContainerLow,
            border = if (owner != null) BorderStroke(1.dp, OtherTint.copy(alpha = 0.35f)) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = if (canTag) ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) else null,
                    onLongClickLabel = if (canTag) "Gắn thẻ" else null,
                )
                .semantics(mergeDescendants = true) {
                    val actions = buildList {
                        if (canDone) add(CustomAccessibilityAction(if (done) "Mở lại" else "Đánh dấu xong") { onToggleDone?.invoke(!done); true })
                        if (canDelete) add(CustomAccessibilityAction("Xoá") { confirmDelete = true; true })
                        if (canTag) add(CustomAccessibilityAction("Gắn thẻ") { menu = true; true })
                    }
                    if (actions.isNotEmpty()) customActions = actions
                    if (tag != null) stateDescription = "Thẻ ${tag.label}"
                },
        ) {
            Box {
                Row(
                    Modifier.padding(start = 6.dp, end = if (tag != null) 26.dp else 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(8.dp)) {
                        if (done && actionable) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = "Đã xong", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                        } else {
                            IconTile(style.icon, style.container, style.content, size = 34.dp)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            if (item.important) "❗ ${item.title}" else item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = when {
                                done -> MaterialTheme.colorScheme.onSurfaceVariant
                                item.important -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (owner != null || audience != null) {
                            Row(Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                                if (owner != null) OwnerChip(owner) else AudienceChip(audience!!)
                            }
                        }
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
                if (tag != null) TagCorner(tag, Modifier.align(Alignment.TopEnd))
                if (canTag) {
                    Box(Modifier.align(Alignment.TopEnd)) {
                        TagQuickMenu(menu, tag, onDismiss = { menu = false }, onPick = { menu = false; onTag?.invoke(it) })
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Xoá mục này?",
            text = item.title,
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { onDelete?.invoke() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (canDone || canDelete) {
        SwipeToDismissBox(
            state = swipe,
            modifier = modifier,
            enableDismissFromStartToEnd = canDone,
            enableDismissFromEndToStart = canDelete,
            backgroundContent = { SwipeBackground(swipe.dismissDirection, done, rowShape) },
            content = { row() },
        )
    } else {
        Box(modifier) { row() }
    }
}

/** What shows behind a swiped row: a green "Xong" (or "Mở lại") on the left, a red "Xoá" on the right. Icon and word, not colour alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, done: Boolean, shape: RoundedCornerShape) {
    val (color, label, icon, align) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeLook(
            Color(0xFF2E7D32), if (done) "Mở lại" else "Xong", if (done) Icons.Rounded.Replay else Icons.Rounded.Check, Alignment.CenterStart,
        )
        SwipeToDismissBoxValue.EndToStart -> SwipeLook(Color(0xFFC62828), "Xoá", Icons.Rounded.DeleteOutline, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(Modifier.fillMaxSize().clip(shape).background(color).padding(horizontal = 20.dp), contentAlignment = align) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (direction == SwipeToDismissBoxValue.StartToEnd) {
                Icon(icon, null, tint = Color.White)
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
            } else {
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
                Icon(icon, null, tint = Color.White)
            }
        }
    }
}

private data class SwipeLook(val color: Color, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val align: Alignment)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Snackbar host that offers "Hoàn tác" after an item is deleted, a proposal discarded or a quick add saved. */
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
    val added = vm.lastAddedItem
    LaunchedEffect(added?.id) {
        val item = added ?: return@LaunchedEffect
        val amount = item.amount
        val label = if (amount != null) "Đã ghi chi tiêu “${item.title}” · ${Money.format(amount)}" else "Đã thêm “${item.title}”"
        val result = host.showSnackbar(label, actionLabel = "Hoàn tác", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) vm.undoAddItem(item) else vm.clearAddedItem(item)
    }
    return host
}
