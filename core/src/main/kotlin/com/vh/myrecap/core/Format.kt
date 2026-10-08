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
    enum class Content { SUMMARY, SUMMARY_AND_TRANSCRIPT, TRANSCRIPT }

    fun build(
        content: Content,
        title: String,
        dateLabel: String,
        durationMs: Long,
        summary: String?,
        transcript: String?,
    ): String = buildString {
        appendLine(title)
        appendLine("$dateLabel · ${TimeFormat.clock(durationMs)}")
        val wantSummary = content != Content.TRANSCRIPT
        val wantTranscript = content != Content.SUMMARY
        if (wantSummary && !summary.isNullOrBlank()) {
            appendLine()
            appendLine(summary.trim())
        }
        if (wantTranscript && !transcript.isNullOrBlank()) {
            appendLine()
            appendLine("---")
            appendLine("TRANSCRIPT")
            appendLine()
            appendLine(transcript.trim())
        }
    }.trim()
}
