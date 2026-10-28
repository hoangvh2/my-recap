package com.vh.myrecap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vh.myrecap.core.DayKind
import com.vh.myrecap.core.HolidayOverride
import com.vh.myrecap.core.Holidays
import com.vh.myrecap.core.StudyLane
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.Lunar
import com.vh.myrecap.core.LunarPrefs
import com.vh.myrecap.core.LunarText
import com.vh.myrecap.settings.AppSettings
import java.time.LocalDate
import java.time.YearMonth

private val HOLIDAY_FILL = Color(0x26F5A524)
private val HOLIDAY_EDGE = Color(0xFFE09400)

/** "Sắp nghỉ" for a state holiday and, when chosen, the day-before note to prepare offerings on mùng 1 and rằm. */
@Composable
fun HolidayBanners(vm: AppViewModel, s: AppSettings) {
    val today = LocalDate.now()
    val data = remember(s.holidayJson) { Holidays.newest(s.holidayJson) }
    val lunar = s.lunar
    val ahead = if (lunar.banner && lunar.holidays) Holidays.banner(today, lunar.lead, lunar.dismissed, data.overrides) else null
    val tomorrow = if (lunar.monthly) Lunar.toLunar(today.plusDays(1)) else null
    val mark = LunarText.monthlyMark(tomorrow)
    if (ahead == null && mark == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ahead != null) {
            val h = ahead.holiday
            Surface(color = HOLIDAY_FILL, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Event, null, Modifier.size(20.dp), tint = HOLIDAY_EDGE)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text((if (ahead.daysUntil < 0) "Đang nghỉ" else "Sắp nghỉ") + " · ${h.name}", style = MaterialTheme.typography.titleSmall)
                        val workdays = if (h.workdays.isEmpty()) "" else " Làm bù: ${h.workdays.joinToString(", ") { LunarText.longDate(it) }}."
                        Text(LunarText.holidayLine(h, ahead.daysUntil) + workdays, style = MaterialTheme.typography.bodyMedium)
                        Text(LunarText.holidayBasis(h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.updateSettings { it.copy(lunar = it.lunar.copy(dismissed = it.lunar.dismissed + h.key)) } }) { Text("Ẩn") }
                }
            }
        }
        if (mark != null && tomorrow != null) {
            Surface(color = HOLIDAY_FILL, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(20.dp), tint = HOLIDAY_EDGE)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Mai là $mark ${LunarText.full(tomorrow).removePrefix("$mark ")}", style = MaterialTheme.typography.titleSmall)
                        Text("Chuẩn bị đồ cúng.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val DOW = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

/** Month grid: solar number, lunar day under it, holidays marked with text, the chosen day's items underneath. */
@Composable
fun MonthCalendar(items: List<Item>, vm: AppViewModel, s: AppSettings) {
    val today = LocalDate.now()
    val data = remember(s.holidayJson) { Holidays.newest(s.holidayJson) }
    val prefs = s.lunar
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    val open = items.filter { it.status == ItemStatus.OPEN && it.whenAt != null && (it.type == ItemType.TASK || it.type == ItemType.EVENT) }
    val byDay = open.groupBy { localDate(it.whenAt!!) }
    val first = month.atDay(1)
    val cells: List<LocalDate?> = List(first.dayOfWeek.value - 1) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Tháng trước") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tháng ${month.monthValue}/${month.year}", style = MaterialTheme.typography.titleMedium)
                if (prefs.show) {
                    val a = Lunar.toLunar(first)
                    val b = Lunar.toLunar(month.atEndOfMonth())
                    if (a != null && b != null) Text("Âm: ${a.short} đến ${b.short}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Tháng sau") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DOW.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    if (date == null) Spacer(Modifier.weight(1f)) else DayCell(date, today, selected, byDay[date]?.size ?: 0, prefs, data.overrides, Modifier.weight(1f)) { selected = date }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            dayLabel(selected, today) + " · ${LunarText.dm(selected)}" + LunarText.dayExtras(selected, prefs, data.overrides),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = StudyLane.studyLast(byDay[selected].orEmpty().sortedBy { it.whenAt })
        if (list.isEmpty()) {
            Text("Không có việc hay lịch hẹn ngày này.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { item ->
                    ItemRow(item, onOpen = { vm.openItem(item.id) }, onToggleDone = { vm.setDone(item.id, it) }, onDelete = { vm.deleteItem(item.id) }, onTag = { vm.setTag(item.id, it) }, onChecklist = { vm.checklist(item.id, it) })
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, today: LocalDate, selected: LocalDate, count: Int, prefs: LunarPrefs, overrides: List<HolidayOverride>, modifier: Modifier, onClick: () -> Unit) {
    val l = if (prefs.show || prefs.monthly) Lunar.toLunar(date) else null
    val holiday = if (prefs.holidays) Holidays.on(date, overrides) else null
    val mark = if (prefs.monthly) LunarText.monthlyMark(l) else if (l?.day == 1) "Mùng 1" else null
    val sub = when {
        l == null -> ""
        l.day == 1 -> l.short
        mark == "Rằm" -> "Rằm"
        prefs.show -> l.day.toString()
        else -> ""
    }
    val legal = holiday?.second == DayKind.LEGAL
    val label = when {
        legal -> "Lễ"
        holiday?.second == DayKind.EXTRA -> "Nghỉ"
        else -> null
    }
    val c = MaterialTheme.colorScheme
    val border = when {
        date == selected -> BorderStroke(2.dp, c.primary)
        legal -> BorderStroke(2.dp, HOLIDAY_EDGE)
        else -> BorderStroke(1.dp, c.outlineVariant)
    }
    val description = listOfNotNull(
        LunarText.longDate(date),
        l?.let { LunarText.full(it) },
        holiday?.let { "nghỉ lễ ${it.first.name}" },
        if (count > 0) "$count mục" else null,
    ).joinToString(", ")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (holiday != null) HOLIDAY_FILL else c.surfaceContainerLow,
        border = border,
        modifier = modifier.height(56.dp).semantics { contentDescription = description },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                date.dayOfMonth.toString(),
                fontWeight = if (date == today) FontWeight.ExtraBold else FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                color = if (date == today) c.primary else c.onSurface,
            )
            if (sub.isNotEmpty()) Text(sub, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = if (mark != null) FontWeight.Bold else FontWeight.Normal, color = if (mark != null) c.onSurface else c.onSurfaceVariant)
            if (label != null) Text(label, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold, color = HOLIDAY_EDGE)
            else if (count > 0) Text("●$count", fontSize = 9.sp, lineHeight = 10.sp, color = c.primary)
        }
    }
}

/** Chooses between the list and the month grid of the calendar. */
@Composable
fun CalendarViewSwitch(month: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !month, onClick = { onChange(false) }, label = { Text("Danh sách") })
        FilterChip(selected = month, onClick = { onChange(true) }, label = { Text("Tháng") })
    }
}

/** Under the date in the editor: the lunar date it falls on, and a button to pick the day on the lunar calendar. */
@Composable
fun LunarWhenRow(date: LocalDate?, showLunar: Boolean, onPickLunar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val l = date?.let { Lunar.toLunar(it) }
        if (showLunar && l != null) {
            Text("= ${LunarText.full(l)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onPickLunar) { Text("Chọn theo lịch Âm") }
    }
}

/** Day, month, year (and leap month) on the lunar calendar, with the solar date it makes. */
@Composable
fun LunarDateDialog(initial: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val start = remember { initial?.let { Lunar.toLunar(it) } ?: Lunar.toLunar(LocalDate.now())!! }
    var day by remember { mutableStateOf(start.day.toString()) }
    var month by remember { mutableStateOf(start.month.toString()) }
    var year by remember { mutableStateOf(start.year.toString()) }
    var leap by remember { mutableStateOf(start.leap) }
    val d = day.toIntOrNull()
    val m = month.toIntOrNull()
    val y = year.toIntOrNull()
    val hasLeap = y != null && m != null && Lunar.month(y, m, true) != null
    val useLeap = leap && hasLeap
    val info = if (y != null && m != null) Lunar.month(y, m, useLeap) else null
    val solar = if (d != null && m != null && y != null) Lunar.toSolar(y, m, d, useLeap) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chọn ngày Âm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Ngày") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(month, { month = it.filter(Char::isDigit).take(2) }, label = { Text("Tháng") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Năm Âm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1.4f))
                }
                if (hasLeap) {
                    Row(Modifier.toggleable(value = leap, onValueChange = { leap = it }, role = Role.Checkbox), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = leap, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tháng nhuận")
                    }
                }
                val problem = when {
                    solar != null -> null
                    info != null && d != null && d > info.days -> "Tháng này chỉ có ${info.days} ngày."
                    else -> "Chưa đúng ngày Âm."
                }
                Text(
                    if (solar != null) "= ${LunarText.longDate(solar)} dương" else problem.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (solar != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(enabled = solar != null, onClick = { solar?.let(onPick) }) { Text("Chọn") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
}
