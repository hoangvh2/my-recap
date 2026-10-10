package com.vh.myrecap.core

import java.text.Normalizer
import java.util.Locale

/**
 * Accent-insensitive search for Vietnamese: "bao gia" finds "báo giá", "do" finds "đổ".
 * Text is folded once (lowercase, diacritics removed, đ → d) and matched token by token.
 */
object TextSearch {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    fun fold(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
            .replace('đ', 'd')
            .replace(SPACES, " ")
            .trim()

    fun tokens(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /** Every query word appears somewhere in the text (any order). */
    fun matches(foldedText: String, queryTokens: List<String>): Boolean =
        queryTokens.isNotEmpty() && queryTokens.all { foldedText.contains(it) }

    /**
     * A short excerpt of [text] around the first query word, for result lists. Folding keeps one
     * character per character for Vietnamese, so positions in the folded text map back to [text].
     */
    fun snippet(text: String, queryTokens: List<String>, radius: Int = 60): String {
        val flat = text.replace(SPACES, " ").trim()
        val folded = fold(flat)
        val at = queryTokens.map { folded.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        if (folded.length != flat.length) return flat.take(radius * 2)
        var start = (at - radius).coerceAtLeast(0)
        var end = (at + radius).coerceAtMost(flat.length)
        // Never cut a word in half.
        if (start > 0) flat.indexOf(' ', start).takeIf { it in start until at }?.let { start = it + 1 }
        if (end < flat.length) flat.lastIndexOf(' ', end).takeIf { it > at }?.let { end = it }
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }
}

/** Expenses as CSV for a spreadsheet; UTF-8 with BOM so Excel shows Vietnamese correctly. */
object ExpenseCsv {
    const val BOM = "\uFEFF"

    fun build(expenses: List<Item>, zone: java.time.ZoneId): String = buildString {
        append(BOM)
        append("Ngày,Khoản chi,Danh mục,Số tiền (VND),Ghi chú\r\n")
        for (e in expenses.sortedBy { it.whenAt ?: 0 }) {
            val date = e.whenAt?.let {
                java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString()
            }.orEmpty()
            append(listOf(date, e.title, e.category.orEmpty(), (e.amount ?: 0).toString(), e.details).joinToString(",") { cell(it) })
            append("\r\n")
        }
        append(listOf("", "Tổng", "", expenses.sumOf { it.amount ?: 0 }.toString(), "").joinToString(",") { cell(it) })
        append("\r\n")
    }

    /** RFC 4180 quoting; a leading = + - @ is neutralised so a spreadsheet never runs it as a formula. */
    private fun cell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toLongOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}

/** When a confirmed task or appointment reminds the user. */
object ReminderPolicy {
    /** Appointments remind this long before they start. */
    const val EVENT_LEAD_MS = 15 * 60_000L
    /** All-day items remind at this hour of their day. */
    const val ALL_DAY_HOUR = 8

    fun triggerAt(item: Item, zone: java.time.ZoneId): Long? {
        if (item.status != ItemStatus.OPEN) return null
        if (item.type != ItemType.TASK && item.type != ItemType.EVENT) return null
        val at = item.whenAt ?: return null
        return when {
            item.allDay -> java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                .atTime(ALL_DAY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
            item.type == ItemType.EVENT -> at - EVENT_LEAD_MS
            else -> at
        }
    }
}
