package com.vh.myrecap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vh.myrecap.core.ItemTag
import com.vh.myrecap.core.TagFilter

/** Colour of a tag and the ink for its glyph; brighter in the dark theme, same palette as the web. */
internal data class TagPaint(val fill: Color, val ink: Color)

@Composable
internal fun tagPaint(tag: ItemTag): TagPaint {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        val fill = when (tag) {
            ItemTag.RED -> Color(0xFFEF5350)
            ItemTag.YELLOW -> Color(0xFFFFCB2E)
            ItemTag.ORANGE -> Color(0xFFFF9340)
            ItemTag.BLUE -> Color(0xFF5B9BFF)
            ItemTag.PURPLE -> Color(0xFFB388FF)
            ItemTag.GREEN -> Color(0xFF4CD08A)
        }
        TagPaint(fill, Color(0xFF12060A))
    } else {
        when (tag) {
            ItemTag.RED -> TagPaint(Color(0xFFC62828), Color.White)
            ItemTag.YELLOW -> TagPaint(Color(0xFFF2B800), Color(0xFF2B2100))
            ItemTag.ORANGE -> TagPaint(Color(0xFFE8710A), Color(0xFF2B2100))
            ItemTag.BLUE -> TagPaint(Color(0xFF1D62D6), Color.White)
            ItemTag.PURPLE -> TagPaint(Color(0xFF7B3FC4), Color.White)
            ItemTag.GREEN -> TagPaint(Color(0xFF1B7F4B), Color.White)
        }
    }
}

/** The diagonal corner on a list row: a triangle in the top-right with the glyph, never colour alone. */
@Composable
fun TagCorner(tag: ItemTag, modifier: Modifier = Modifier, size: Int = 34) {
    val paint = tagPaint(tag)
    Box(modifier.size(size.dp).semantics { contentDescription = "Thẻ ${tag.label}" }) {
        Canvas(Modifier.size(size.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(this@Canvas.size.width, 0f)
                lineTo(this@Canvas.size.width, this@Canvas.size.height)
                close()
            }
            drawPath(path, paint.fill)
        }
        Text(
            tag.glyph,
            color = paint.ink,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.38f).sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = (size * 0.09f).dp, end = (size * 0.15f).dp),
        )
    }
}

/** A round swatch with the glyph, used where a tag is picked. */
@Composable
fun TagDot(tag: ItemTag, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val paint = tagPaint(tag)
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(paint.fill)
            .then(if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = "Thẻ ${tag.label}" + if (selected) ", đang chọn" else "" },
        contentAlignment = Alignment.Center,
    ) { Text(tag.glyph, color = paint.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** The quick-pick menu shown on a long press: the tags in a row and a way to remove it. */
@Composable
fun TagQuickMenu(expanded: Boolean, current: ItemTag?, onDismiss: () -> Unit, onPick: (ItemTag?) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemTag.entries.forEach { t -> TagDot(t, selected = t == current, onClick = { onPick(if (t == current) null else t) }) }
        }
        if (current != null) DropdownMenuItem(text = { Text("Bỏ thẻ") }, onClick = { onPick(null) })
    }
}

/** Form control: the tag's corner at the top-right of the form; tapping lists the tags. */
@Composable
fun TagFormButton(current: ItemTag?, onPick: (ItemTag?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val line = MaterialTheme.colorScheme.outline
    Box(modifier) {
        Box(
            Modifier
                .size(46.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable { open = true }
                .semantics { role = Role.Button; contentDescription = if (current != null) "Thẻ ${current.label}, đổi thẻ" else "Gắn thẻ" },
            contentAlignment = Alignment.TopEnd,
        ) {
            if (current != null) {
                TagCorner(current, size = 46)
            } else {
                Canvas(Modifier.size(46.dp)) {
                    val path = Path().apply {
                        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); close()
                    }
                    drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ))
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ItemTag.entries.forEach { t ->
                DropdownMenuItem(
                    leadingIcon = { TagDot(t, selected = false, onClick = { open = false; onPick(t) }) },
                    text = { Text(t.label + if (t == current) "  ✓" else "") },
                    onClick = { open = false; onPick(t) },
                )
            }
            DropdownMenuItem(text = { Text("Không thẻ") }, onClick = { open = false; onPick(null) })
        }
    }
}

/** "Có thẻ · ! ★ ▲ ? ♥" chips above the lists; only the tags in use are offered. */
@Composable
fun TagFilterRow(items: List<com.vh.myrecap.core.Item>, filter: TagFilter, onChange: (TagFilter) -> Unit, modifier: Modifier = Modifier) {
    val available = TagFilter.available(items)
    if (available.isEmpty() && !filter.active) return
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = filter.anyTag && filter.tag == null,
            onClick = { onChange(if (filter.anyTag && filter.tag == null) TagFilter() else TagFilter(anyTag = true)) },
            label = { Text("Có thẻ") },
        )
        available.forEach { t ->
            val paint = tagPaint(t)
            FilterChip(
                selected = filter.tag == t,
                onClick = { onChange(if (filter.tag == t) TagFilter() else TagFilter(tag = t)) },
                label = { Text("${t.glyph} ${t.label}") },
                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(paint.fill)) },
            )
        }
    }
}
