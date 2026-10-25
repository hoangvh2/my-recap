package com.vh.myrecap.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.vh.myrecap.core.noteDone
import com.vh.myrecap.core.noteState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.NoteKind
import com.vh.myrecap.core.NoteFilter
import com.vh.myrecap.core.NoteKinds
import com.vh.myrecap.core.NoteLinks
import com.vh.myrecap.core.noteKind

/** The kind chips at the top of a note's form ("Thường" for none). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteKindChips(edit: Item, onChange: (Item) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Loại ghi chú", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = edit.noteKind == null, onClick = { onChange(edit.copy(kind = null)) }, label = { Text("Thường") })
            NoteKind.entries.forEach { k ->
                FilterChip(
                    selected = edit.noteKind == k,
                    onClick = { onChange(edit.copy(kind = k.key)) },
                    label = { Text("${k.glyph} ${k.label}") },
                )
            }
        }
    }
}

/**
 * The link and the few values the kind keeps, below the address so a place note shows its address first. Values of a
 * kind the note had before stay in the form while editing; saving keeps only the current kind's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteKindFields(edit: Item, onChange: (Item) -> Unit) {
    val kind = edit.noteKind ?: return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = edit.url.orEmpty(),
            onValueChange = { onChange(edit.copy(url = it.take(NoteKinds.MAX_URL).ifEmpty { null })) },
            label = { Text("Link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        kind.fields.filter { !it.flag }.forEach { f ->
            OutlinedTextField(
                value = edit.attrs[f.key].orEmpty(),
                onValueChange = { v -> onChange(edit.copy(attrs = edit.attrs + (f.key to v.take(NoteKinds.MAX_ATTR_VALUE)))) },
                label = { Text(f.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kind.fields.filter { it.flag }.forEach { f ->
                val on = edit.attrs[f.key] == "1"
                FilterChip(
                    selected = on,
                    onClick = { onChange(edit.copy(attrs = edit.attrs + (f.key to if (on) "" else "1"))) },
                    label = { Text(f.label) },
                    leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null,
                )
            }
        }
    }
}

/** The "Tóm tắt bằng AI" button under a note's body. It only runs on a tap and only sends this note's text. */
@Composable
fun NoteAiButton(item: Item, busy: Boolean, onClick: () -> Unit) {
    val ready = item.details.trim().length >= MIN_AI_TEXT
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onClick, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("✨ Tóm tắt bằng AI")
        }
        Text(
            if (ready) "Gửi nội dung ghi chú này cho AI của bạn khi bạn bấm. Kết quả chỉ là đề xuất, bạn chọn dùng hay bỏ."
            else "Dán nội dung bài (từ $MIN_AI_TEXT ký tự) vào ô Nội dung để AI tóm tắt. AI không tự mở link.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val MIN_AI_TEXT = 40

/** Chips above the notes list: one per kind in use, plus "Chưa xong" for the ones not yet read, visited or watched. */
@Composable
fun NoteFilterRow(notes: List<Item>, filter: NoteFilter, onChange: (NoteFilter) -> Unit, modifier: Modifier = Modifier) {
    val kinds = NoteFilter.available(notes)
    if (kinds.isEmpty() && !filter.active) return
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { k ->
            FilterChip(
                selected = filter.kind == k,
                onClick = { onChange(filter.copy(kind = if (filter.kind == k) null else k)) },
                label = { Text("${k.glyph} ${k.label}") },
            )
        }
        FilterChip(
            selected = filter.pending,
            onClick = { onChange(filter.copy(pending = !filter.pending)) },
            label = { Text("Chưa xong") },
        )
    }
}

/** Opens the link of a note in whatever app answers; says so when none does. */
fun openNoteLink(context: Context, item: Item) {
    val url = NoteLinks.openUrl(item) ?: return
    startOrToast(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/**
 * Shows the note's place on a map: the stored Google Maps link when it is one (the exact spot), otherwise a map
 * app's answer to the address, otherwise Google Maps in the browser.
 */
fun openNoteMap(context: Context, item: Item) {
    val link = item.url?.trim()
    if (NoteLinks.isMapsLink(link)) {
        startOrToast(context, Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        return
    }
    val query = NoteLinks.mapsQuery(item) ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NoteLinks.geoUri(query))))
    } catch (_: ActivityNotFoundException) {
        startOrToast(context, Intent(Intent.ACTION_VIEW, Uri.parse(NoteLinks.mapsSearchUrl(query))))
    }
}

private fun startOrToast(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Không có ứng dụng để mở link này", Toast.LENGTH_SHORT).show()
    }
}

/** Under a note's title: its kind and where it stands as two small pills ("Địa điểm", "Chưa đến"). */
@Composable
fun NoteKindTags(item: Item, kind: NoteKind) {
    val style = noteKindStyle(kind)
    val c = MaterialTheme.colorScheme
    val done = item.noteDone
    Row(Modifier.padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TagPill(kind.label, style.container, style.content)
        item.noteState()?.let { state ->
            if (done) TagPill(state, c.primaryContainer, c.onPrimaryContainer) else TagPill(state, c.surfaceContainerHighest, c.onSurfaceVariant)
        }
    }
}

@Composable
private fun TagPill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
