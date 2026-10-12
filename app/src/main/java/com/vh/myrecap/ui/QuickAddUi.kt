package com.vh.myrecap.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.QuickAdd
import com.vh.myrecap.core.QuickTemplates
import java.time.ZoneId

/**
 * Typed quick-add: parses "gọi anh Nam mai 3h chiều" on the phone (no AI call, works offline) and shows
 * what it understood as chips. An amount ("cà phê 45k") makes it an expense with a guessed category.
 * Tapping a chip drops that part, which stays in the title instead.
 *
 * Under the box, saved lines (Cài đặt → Mẫu nhập nhanh): one without a blank is added in one tap (with
 * Undo); one with a blank ("ăn trưa …k") fills the box with the cursor on the blank.
 */
@Composable
fun QuickAddBar(vm: AppViewModel, modifier: Modifier = Modifier, initial: String? = null, autoFocus: Boolean = false) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(initial?.let { QuickTemplates.fill(it) }?.let { (t, c) -> TextFieldValue(t, TextRange(c)) } ?: TextFieldValue(""))
    }
    val text = field.text
    var off by remember { mutableStateOf(emptySet<QuickAdd.Part>()) }
    // Past expenses teach the category: a title filed under "Công việc" once stays there.
    val history by vm.items.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val result = remember(text, off, history) { QuickAdd.parse(text, System.currentTimeMillis(), ZoneId.systemDefault(), off, history) }
    fun submit() {
        if (text.isBlank()) return
        vm.quickAdd(result)
        field = TextFieldValue("")
        off = emptySet()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            placeholder = { Text("Gõ nhanh: gọi anh Nam mai 3h, cà phê 45k") },
            trailingIcon = {
                IconButton(onClick = ::submit, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Thêm")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        if (text.isBlank()) {
            val templates = QuickTemplates.ordered(settings.quickTemplates)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                templates.forEach { t ->
                    SuggestionChip(
                        onClick = {
                            if (QuickTemplates.hasBlank(t.text)) {
                                val (filled, cursor) = QuickTemplates.fill(t.text)
                                field = TextFieldValue(filled, TextRange(cursor))
                                off = emptySet()
                                focus.requestFocus()
                            } else {
                                vm.addFromTemplate(t)
                            }
                        },
                        label = { Text(QuickTemplates.normalize(t.text)) },
                    )
                }
                TextButton(onClick = vm::openSettings) { Text(if (templates.isEmpty()) "Thêm mẫu" else "Sửa mẫu", style = MaterialTheme.typography.labelLarge) }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.type == ItemType.EVENT) AssistChip(onClick = {}, label = { Text("Lịch hẹn") })
                if (result.type == ItemType.EXPENSE) AssistChip(onClick = {}, label = { Text("Chi tiêu · ${result.category}") })
                result.spans.forEach { span ->
                    AssistChip(onClick = { off = off + span.part }, label = { Text("${span.shown}  ✕") })
                }
                if (off.isNotEmpty()) TextButton(onClick = { off = emptySet() }) { Text("Hiểu lại", style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = { vm.saveTemplate(text) }) { Text("Lưu làm mẫu", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
