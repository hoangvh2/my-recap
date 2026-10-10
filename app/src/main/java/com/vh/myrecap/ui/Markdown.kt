package com.vh.myrecap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vh.myrecap.core.Prompts

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val indent: Int, val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data class Paragraph(val text: String) : Block
    data object Gap : Block
}

private fun parse(markdown: String): List<Block> {
    val out = mutableListOf<Block>()
    val table = mutableListOf<List<String>>()
    fun flushTable() {
        if (table.isNotEmpty()) out += Block.Table(table.toList())
        table.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        val t = line.trimStart()
        if (t.startsWith("|")) {
            val cells = t.trim('|').split('|').map { it.trim() }
            if (cells.all { it.isEmpty() || it.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }) continue
            table += cells
            continue
        }
        flushTable()
        when {
            t.isEmpty() -> if (out.lastOrNull() != Block.Gap) out += Block.Gap
            t.startsWith("#") -> out += Block.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim())
            t.startsWith("- ") || t.startsWith("* ") -> out += Block.Bullet((line.length - t.length) / 2, t.drop(2))
            else -> out += Block.Paragraph(t)
        }
    }
    flushTable()
    return out
}

/**
 * Renders the Markdown subset the summaries use: headings, nested bullets, bold and tables,
 * with typographic spacing tuned for reading on a phone.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier) {
        parse(markdown).forEachIndexed { i, block ->
            when (block) {
                is Block.Heading -> {
                    if (i > 0) Spacer(Modifier.height(if (block.level <= 2) 22.dp else 14.dp))
                    Text(
                        inline(block.text),
                        style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        color = if (block.level <= 2) c.onSurface else c.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                is Block.Bullet -> Row(Modifier.padding(start = (block.indent * 18).dp, top = 3.dp, bottom = 3.dp)) {
                    Box(Modifier.padding(top = 10.dp, end = 12.dp).size(5.dp).clip(CircleShape).background(c.primary.copy(alpha = 0.7f)))
                    Text(inline(block.text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                is Block.Paragraph -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
                is Block.Table -> TableBlock(block.rows)
                Block.Gap -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    val c = MaterialTheme.colorScheme
    val columns = rows.maxOf { it.size }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = c.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column {
            rows.forEachIndexed { r, row ->
                if (r > 0) HorizontalDivider(color = c.outlineVariant)
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (col in 0 until columns) {
                        Text(
                            inline(row.getOrElse(col) { "" }),
                            style = if (r == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            color = if (r == 0) c.onSurfaceVariant else c.onSurface,
                            modifier = Modifier.weight(if (col == 0) 1.6f else 1f),
                        )
                    }
                }
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}

private data class Turn(val speaker: String?, val text: String)

/** Splits "Speaker: text" lines into turns; consecutive lines of one speaker stay together. */
private fun turns(transcript: String): List<Turn> {
    val out = mutableListOf<Turn>()
    for (raw in transcript.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val colon = line.indexOf(':')
        val label = if (colon in 1..40) line.substring(0, colon).trim() else null
        // "Người phỏng vấn: …" — a short name before the colon, not a sentence that contains one.
        val looksLikeLabel = label != null && label.split(' ').size <= 5
        if (looksLikeLabel) {
            out += Turn(label, line.substring(colon + 1).trim())
        } else if (out.isNotEmpty()) {
            val last = out.removeAt(out.lastIndex)
            out += last.copy(text = last.text + "\n" + line)
        } else {
            out += Turn(null, line)
        }
    }
    return out
}

/** Transcript as a readable conversation: speaker names on their own line, colour-coded. */
@Composable
fun SpeakerTranscript(transcript: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val text = transcript.trim()
    if (text == Prompts.NO_SPEECH || text == Prompts.INTERVIEWER_ONLY) {
        Text(
            if (text == Prompts.NO_SPEECH) "Đoạn này không có lời nói." else "Đoạn này chỉ có lời người phỏng vấn (đã lược bỏ).",
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val palette = listOf(c.primary, c.secondary, c.tertiary, Color(0xFFC2410C))
    val speakers = mutableMapOf<String, Color>()
    fun colorFor(name: String): Color {
        val key = name.lowercase()
        return speakers.getOrPut(key) {
            when {
                "phỏng vấn" in key || "interviewer" in key -> c.primary
                "ứng viên" in key || "candidate" in key -> c.secondary
                else -> palette[(speakers.size + 2) % palette.size]
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (turn in turns(text)) {
            Column {
                if (turn.speaker != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = colorFor(turn.speaker)
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(8.dp))
                        Text(turn.speaker, style = MaterialTheme.typography.labelLarge, color = color)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(turn.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
