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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.QuickAdd
import java.time.ZoneId

/**
 * Typed quick-add: parses "gọi anh Nam mai 3h chiều" on the phone (no AI call, works offline) and shows
 * what it understood as chips. Tapping a chip drops that part, which stays in the title instead.
 */
@Composable
fun QuickAddBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var off by remember { mutableStateOf(emptySet<QuickAdd.Part>()) }
    val result = remember(text, off) { QuickAdd.parse(text, System.currentTimeMillis(), ZoneId.systemDefault(), off) }
    fun submit() {
        if (text.isBlank()) return
        vm.quickAdd(result)
        text = ""
        off = emptySet()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Gõ nhanh: gọi anh Nam mai 3h chiều") },
            trailingIcon = {
                IconButton(onClick = ::submit, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Thêm")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        if (text.isNotBlank() && (result.spans.isNotEmpty() || off.isNotEmpty())) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.type == ItemType.EVENT) AssistChip(onClick = {}, label = { Text("Lịch hẹn") })
                result.spans.forEach { span ->
                    AssistChip(onClick = { off = off + span.part }, label = { Text("${span.shown}  ✕") })
                }
                if (off.isNotEmpty()) TextButton(onClick = { off = emptySet() }) { Text("Hiểu lại", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}
