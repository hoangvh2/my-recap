package com.vh.myrecap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders the small Markdown subset the summary prompt asks for (headings, bullets, bold, tables)
 * so summaries read well on a phone. Anything else is shown as plain text.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Text("", style = MaterialTheme.typography.bodySmall)
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.drop(level).trim()
                    Text(
                        inline(text),
                        style = if (level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") } -> {
                    val indent = line.length - line.trimStart().length
                    Row(Modifier.padding(start = (indent * 6).dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyLarge)
                        Text(inline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                line.trimStart().startsWith("|") -> {
                    if (line.replace("|", "").replace("-", "").replace(":", "").isNotBlank()) {
                        Text(line.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> Text(inline(line), style = MaterialTheme.typography.bodyLarge)
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
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(start + 2, end)) }
        rest = rest.substring(end + 2)
    }
}
