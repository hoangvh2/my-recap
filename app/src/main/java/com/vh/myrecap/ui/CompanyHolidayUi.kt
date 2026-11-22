package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.CompanyBreak
import com.vh.myrecap.core.CompanyHoliday
import com.vh.myrecap.core.CompanyHolidays
import com.vh.myrecap.core.LunarText
import com.vh.myrecap.settings.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private fun describe(c: CompanyHoliday): String {
    val span = CompanyHolidays.span(CompanyBreak("", c.name, c.from, c.to))
    return span + if (c.yearly) " · lặp hằng năm" else " · năm ${c.from.year}"
}

/** Cài đặt → Ngày nghỉ của công ty: the person's own days off, shown on the calendar and noticed like state holidays. */
@Composable
fun CompanyHolidaysSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val list = s.lunar.company
    var editing by remember { mutableStateOf<CompanyHoliday?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CompanyHoliday?>(null) }
    Hint("Ngày nghỉ riêng của công ty (nghỉ hè, team building, nghỉ cuối năm…). Hiện trên Lịch, ở Hôm nay và nhắc trước như ngày lễ nhà nước. Lưu trên máy này.")
    if (list.isEmpty()) Hint("Chưa nhập ngày nghỉ nào.")
    list.sortedWith(compareBy({ it.from.monthValue }, { it.from.dayOfMonth })).forEach { c ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { editing = c }.padding(vertical = 6.dp).semantics { contentDescription = "Sửa kỳ nghỉ ${c.name}" }) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                Text(describe(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { deleting = c }) { Icon(Icons.Rounded.Delete, contentDescription = "Xoá kỳ nghỉ ${c.name}") }
        }
    }
    FilledTonalButton(
        onClick = { adding = true },
        enabled = list.size < CompanyHolidays.MAX,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Add, null)
        Text("Thêm ngày nghỉ công ty")
    }
    if (adding || editing != null) {
        CompanyHolidayDialog(
            entry = editing,
            onSave = { saved ->
                update { st ->
                    val cur = st.lunar.company
                    val next = if (cur.any { it.id == saved.id }) cur.map { if (it.id == saved.id) saved else it } else cur + saved
                    st.copy(lunar = st.lunar.copy(company = next))
                }
                adding = false
                editing = null
            },
            onDelete = { c -> editing = null; deleting = c },
            onDismiss = { adding = false; editing = null },
        )
    }
    deleting?.let { c ->
        ConfirmDialog(
            title = "Xoá kỳ nghỉ này?",
            text = "${c.name} · ${describe(c)}",
            confirmLabel = "Xoá",
            destructive = true,
            onConfirm = { update { st -> st.copy(lunar = st.lunar.copy(company = st.lunar.company.filter { it.id != c.id })) } },
            onDismiss = { deleting = null },
        )
    }
}

private fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private fun LocalDate.toMs(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanyHolidayDialog(entry: CompanyHoliday?, onSave: (CompanyHoliday) -> Unit, onDelete: (CompanyHoliday) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(entry?.name ?: "") }
    var from by remember { mutableStateOf(entry?.from) }
    var to by remember { mutableStateOf(entry?.to) }
    var yearly by remember { mutableStateOf(entry?.yearly ?: true) }
    var pick by remember { mutableStateOf<String?>(null) }
    val problem = CompanyHolidays.problem(name, from, to ?: from)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Kỳ nghỉ công ty mới" else "Sửa kỳ nghỉ công ty") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(CompanyHolidays.NAME_MAX) }, label = { Text("Tên kỳ nghỉ") }, placeholder = { Text("VD: Nghỉ hè công ty") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pick = "from" }, modifier = Modifier.fillMaxWidth()) { Text("Từ ngày: " + (from?.let { LunarText.longDate(it) } ?: "chọn")) }
                OutlinedButton(onClick = { pick = "to" }, modifier = Modifier.fillMaxWidth()) { Text("Đến ngày: " + ((to ?: from)?.let { LunarText.longDate(it) } ?: "chọn (bỏ trống nếu một ngày)")) }
                Row(Modifier.fillMaxWidth().clickable { yearly = !yearly }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(yearly, { yearly = it })
                    Column {
                        Text("Lặp lại hằng năm")
                        Text("Cùng ngày, cùng tháng mỗi năm. Bỏ chọn nếu chỉ nghỉ trong năm này.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (problem != null && (name.isNotBlank() || from != null)) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (entry != null) TextButton(onClick = { onDelete(entry) }) { Text("Xoá kỳ nghỉ này", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = { onSave(CompanyHoliday(entry?.id ?: UUID.randomUUID().toString().take(8), name.trim(), from!!, to ?: from!!, yearly)) },
            ) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
    )
    pick?.let { which ->
        val initial = (if (which == "from") from else (to ?: from)) ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.toMs())
        DatePickerDialog(
            onDismissRequest = { pick = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.toDate()?.let { d ->
                        if (which == "from") {
                            from = d
                            if (to != null && to!! < d) to = d
                        } else {
                            to = d
                        }
                    }
                    pick = null
                }) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Huỷ") } },
        ) { DatePicker(state = state) }
    }
}
