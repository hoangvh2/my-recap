package com.vh.myrecap.core

import java.text.Normalizer

/** One step of a study session: a checklist line that still has to be done, and how long it may take. */
data class StudyPhase(val entryId: String, val text: String, val minutes: Int?)

/**
 * "10 phút: AnkiDroid ôn thẻ" → ten minutes. The number is read from the start of a checklist line, so a checklist
 * turns into a study plan without any new field (and without touching sync, backup or the web app).
 */
object StudyPlan {
    const val MAX_MINUTES = 240

    // Number, then the unit ("phút", "phut", "p", "min", or an apostrophe), and the unit must end there ("10 phim" is not a time).
    private val LEAD = Regex("^\\s*(\\d{1,3})\\s*(?:phút|phut|min|p|')(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** Minutes at the start of [text], or null when the line has no time. Out-of-range numbers (0, > [MAX_MINUTES]) count as no time. */
    fun minutesOf(text: String): Int? {
        val m = LEAD.find(Normalizer.normalize(text, Normalizer.Form.NFC)) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_MINUTES }
    }

    /** The lines still to do, in order. A line without a time has [StudyPhase.minutes] = null and waits for "Xong". */
    fun phases(item: Item): List<StudyPhase> =
        Checklist.progress(item).remaining.map { StudyPhase(it.id, it.text, minutesOf(it.text)) }

    /** A session needs an open task or appointment with at least one timed line still to do. */
    fun canStart(item: Item): Boolean =
        item.type.canChecklist() && item.status == ItemStatus.OPEN && phases(item).any { it.minutes != null }

    /** [item] with the lines [entryIds] ticked (already ticked or unknown ids change nothing). */
    fun tick(item: Item, entryIds: Collection<String>): Item {
        if (entryIds.isEmpty() || item.checklist.none { it.id in entryIds && !it.done }) return item
        return item.copy(checklist = item.checklist.map { if (it.id in entryIds) it.copy(done = true) else it })
    }

    fun totalMinutes(phases: List<StudyPhase>): Int = phases.sumOf { it.minutes ?: 0 }
}
