package com.vh.myrecap.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a reminder notification says: one scannable line when collapsed, one line per thing when expanded. */
data class ReminderText(
    /** Collapsed body: when it is and how much is left, nothing else. */
    val collapsed: String,
    /** Expanded body, one line each: [header] first, then the checklist lines still to do. */
    val header: String,
    val entries: List<String>,
    /** Last, small line: the start of the long description, cut short. */
    val summary: String?,
) {
    /** Whole expanded text for a style that has no lines of its own. */
    fun asParagraph(): String = (listOf(header) + entries + listOfNotNull(summary)).joinToString("\n")
}

object ReminderTexts {
    const val MAX_ENTRIES = 4
    const val ENTRY_CHARS = 60
    const val SUMMARY_CHARS = 70

    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM")
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun of(item: Item, now: Long, zone: ZoneId): ReminderText {
        val whenText = whenLabel(item, now, zone)
        val remaining = Checklist.progress(item).remaining
        val minutes = StudyPlan.totalMinutes(StudyPlan.phases(item))
        val left = if (remaining.isEmpty()) null else "còn ${remaining.size} việc" + if (minutes > 0) " · ~$minutes phút" else ""
        val place = item.place?.takeIf { it.isNotBlank() }

        val collapsed = listOfNotNull(whenText, left ?: place).joinToString(" · ")
        val header = listOfNotNull(whenText, item.recurrence?.label, place).joinToString(" · ")

        val shown = if (remaining.size > MAX_ENTRIES) MAX_ENTRIES - 1 else remaining.size
        val lines = remaining.take(shown).map { "• " + cut(it.text, ENTRY_CHARS) }.toMutableList()
        if (remaining.size > shown) lines += "+${remaining.size - shown} việc khác"

        val summary = item.details.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { cut(it, SUMMARY_CHARS) }
        return ReminderText(collapsed.ifEmpty { item.title }, header.ifEmpty { item.title }, lines, summary)
    }

    /** "07:30 hôm nay", "07:30 13/11", or the full date when it is another year; all-day items have no clock. */
    fun whenLabel(item: Item, now: Long, zone: ZoneId): String? {
        val at = item.whenAt ?: return null
        val z = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = z.toLocalDate()
        val day = when {
            date == today -> "hôm nay"
            date == today.plusDays(1) -> "ngày mai"
            date.year == today.year -> z.format(DAY_MONTH)
            else -> z.format(DAY_MONTH_YEAR)
        }
        return if (item.allDay) day.replaceFirstChar { it.uppercase() } else "${z.format(HOUR_MINUTE)} $day"
    }

    fun cut(text: String, max: Int): String {
        val t = text.trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}
