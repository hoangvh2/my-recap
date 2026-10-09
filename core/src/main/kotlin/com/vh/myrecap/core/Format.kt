package com.vh.myrecap.core

import java.util.Locale

object TimeFormat {
    /** 65_000 -> "01:05", 3_725_000 -> "1:02:05". */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }
}

object TranscriptAssembler {
    /**
     * Joins clip transcripts in timeline order. Each block starts with its number, time range,
     * title and the bookmarks inside it, so readers (and the summary model) can locate moments.
     */
    fun assemble(clips: List<ClipText>, bookmarksMs: List<Long>): String {
        val sorted = clips.sortedBy { it.startMs }
        return sorted.mapIndexed { i, clip ->
            val isLast = i == sorted.lastIndex
            val marks = bookmarksMs.filter { it >= clip.startMs && (it < clip.endMs || (isLast && it <= clip.endMs)) }
            val header = buildString {
                append("[Đoạn ").append(clip.number).append(" · ")
                append(TimeFormat.clock(clip.startMs)).append("–").append(TimeFormat.clock(clip.endMs)).append("]")
                if (!clip.title.isNullOrBlank()) append(" ").append(clip.title)
                if (marks.isNotEmpty()) append("  ⭐ ").append(marks.joinToString(", ") { TimeFormat.clock(it) })
            }
            val body = clip.text?.trim().orEmpty().ifEmpty { "(chưa có transcript)" }
            "$header\n$body"
        }.joinToString("\n\n")
    }
}

object ShareText {
    /**
     * Whole folder as plain text for one-tap sharing:
     * folder name, summary (if any), a `----` divider, then each clip's transcript in order.
     * Clips without speech are left out.
     */
    fun folder(title: String, summary: String?, clips: List<ClipText>): String = buildString {
        appendLine(title.trim())
        if (!summary.isNullOrBlank()) {
            appendLine()
            appendLine(plain(summary))
        }
        val spoken = clips.sortedBy { it.startMs }.filter { c ->
            val t = c.text?.trim()
            !t.isNullOrEmpty() && t != Prompts.NO_SPEECH && t != Prompts.INTERVIEWER_ONLY
        }
        if (spoken.isNotEmpty()) {
            appendLine()
            appendLine("----")
            spoken.forEachIndexed { i, c ->
                if (i > 0) appendLine()
                appendLine("Đoạn ${c.number}" + if (!c.title.isNullOrBlank()) " — ${c.title.trim()}" else "")
                appendLine(c.text!!.trim())
            }
        }
    }.trim()

    /**
     * Markdown → readable plain text for chat apps (Zalo, Messenger) that show symbols literally:
     * headings become UPPERCASE lines, bullets become "•", bold markers and table rules are removed.
     */
    fun plain(markdown: String): String = markdown.trim().lines().mapNotNull { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            trimmed.startsWith("#") -> trimmed.trimStart('#').trim().replace("**", "").uppercase()
            trimmed.startsWith("|") && trimmed.replace(Regex("[|:\\-\\s]"), "").isEmpty() -> null
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                " ".repeat(indent) + "• " + trimmed.drop(2).replace("**", "")
            else -> line.replace("**", "")
        }
    }.joinToString("\n")
}
