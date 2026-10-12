package com.vh.myrecap.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
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
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.QuickAdd
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.core.QuickTemplates
import java.time.ZoneId

/**
 * Typed quick-add: parses "gọi anh Nam mai 3h chiều" on the phone (no AI call, works offline) and shows
 * what it understood as chips. An amount ("cà phê 45k") makes it an expense with a guessed category.
 * Tapping a chip drops that part, which stays in the title instead.
 *
 * Saved lines (màn hình Mẫu): the focused empty box offers the most used one of each kind; typing offers
 * every line containing the words typed. One without a blank is added in one tap (with Undo); one with a
 * blank ("ăn trưa …k") fills the box with the cursor on the blank.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val focusManager = LocalFocusManager.current
    // Saved lines show while the box is focused, so the agenda below keeps its place on small screens.
    var focused by remember { mutableStateOf(false) }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    // Hiding the keyboard (back) means done typing: let the box go so its lines fold away.
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeShown = true
        else if (imeShown) {
            imeShown = false
            if (focused && !autoFocus) focusManager.clearFocus()
        }
    }
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
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
            singleLine = true,
            leadingIcon = {
                IconButton(onClick = vm::openTemplates) { Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Mẫu câu") }
            },
            placeholder = { Text("Gõ nhanh: gọi anh Nam mai 3h, cà phê 45k", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                IconButton(onClick = ::submit, enabled = text.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Thêm")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        // A saved line: one without a blank is added at once, one with a blank fills the box.
        fun use(t: QuickTemplate) {
            if (QuickTemplates.hasBlank(t.text)) {
                val (filled, cursor) = QuickTemplates.fill(t.text)
                field = TextFieldValue(filled, TextRange(cursor))
                off = emptySet()
                vm.templateUsed(t)
                focus.requestFocus()
            } else {
                vm.addFromTemplate(t)
                field = TextFieldValue("")
                off = emptySet()
                focusManager.clearFocus()
            }
        }
        if (text.isBlank() && focused) {
            // Only the most used line of each kind; the rest come up as the user types.
            val featured = remember(settings.quickTemplates) {
                QuickTemplates.featured(settings.quickTemplates, System.currentTimeMillis(), ZoneId.systemDefault())
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                featured.forEach { t -> SuggestionChip(onClick = { use(t) }, label = { Text(QuickTemplates.normalize(t.text)) }) }
                if (featured.isEmpty()) TextButton(onClick = vm::openTemplates) { Text("Thêm mẫu", style = MaterialTheme.typography.labelLarge) }
            }
        } else if (text.isNotBlank()) {
            val matches = remember(text, settings.quickTemplates) { QuickTemplates.matching(settings.quickTemplates, text) }
            if (matches.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { t ->
                        SuggestionChip(
                            onClick = { use(t) },
                            label = { Text(QuickTemplates.normalize(t.text)) },
                            icon = { Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (result.type == ItemType.EVENT) AssistChip(onClick = {}, label = { Text("Lịch hẹn") })
                if (result.type == ItemType.EXPENSE) AssistChip(onClick = {}, label = { Text("Chi tiêu · ${result.category}") })
                result.spans.forEach { span ->
                    AssistChip(onClick = { off = off + span.part }, label = { Text("${span.shown}  ✕") })
                }
                if (off.isNotEmpty()) TextButton(onClick = { off = emptySet() }) { Text("Hiểu lại", style = MaterialTheme.typography.labelLarge) }
                if (matches.isEmpty()) TextButton(onClick = { vm.saveTemplate(text) }) { Text("Lưu làm mẫu", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/**
 * A tap that nothing under it handled (empty space, a label, the list's padding) drops the keyboard
 * focus, so the quick-add box closes like a tap outside a text field does elsewhere. Taps on buttons,
 * rows and the box itself are theirs; a scroll is not a tap.
 */
fun Modifier.clearFocusOnTapOutside(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (waitForUpOrCancellation() != null) focusManager.clearFocus()
        }
    }
}
