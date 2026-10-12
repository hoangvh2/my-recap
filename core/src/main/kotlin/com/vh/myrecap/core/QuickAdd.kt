package com.vh.myrecap.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Typed quick-add without the AI: "gọi anh Nam mai 3h chiều", "họp nhóm thứ 5 tuần sau 9h", "uống thuốc
 * mỗi ngày 8h tối gấp". Understands only the common Vietnamese phrasings below and leaves everything else
 * in the title; a phrase it is unsure of is never guessed, so the worst case is a plain undated task.
 * Each recognised phrase is reported so the screen can show it and let the user switch it off.
 */
object QuickAdd {
    enum class Part(val label: String) { DAY("Ngày"), TIME("Giờ"), REPEAT("Lặp lại"), IMPORTANT("Quan trọng"), AMOUNT("Số tiền") }

    /** A recognised phrase: which part it set, the words as typed and a readable form such as "Mai". */
    data class Span(val part: Part, val text: String, val shown: String, val start: Int, val end: Int)

    class Result(
        val title: String,
        val type: ItemType,
        val whenAt: Long?,
        val allDay: Boolean,
        val recurrence: Recurrence?,
        val important: Boolean,
        val spans: List<Span>,
        /** Whole VND; set only for expenses ("cà phê 45k"). */
        val amount: Long? = null,
        val category: String? = null,
    ) {
        fun toItem(id: String, now: Long) = Item(
            id = id, type = type, status = ItemStatus.OPEN, title = title, whenAt = whenAt, allDay = allDay,
            createdAt = now, recurrence = recurrence, important = important, amount = amount, category = category,
        )
    }

    /** Words that make a dated entry an appointment rather than a task (same idea as the web server rule). */
    private val EVENT_WORDS = listOf("hop", "hen", "gap mat", "phong van", "kham", "demo", "sinh nhat", "chuyen bay", "tiec")

    private val DAYS = mapOf("hai" to 1, "2" to 1, "ba" to 2, "3" to 2, "tu" to 3, "4" to 3, "nam" to 4, "5" to 4, "sau" to 5, "6" to 5, "bay" to 6, "7" to 6)

    /** [history]: earlier items, so an expense gets the category the user gave the same title before. */
    fun parse(text: String, now: Long, zone: ZoneId, off: Set<Part> = emptySet(), history: List<Item> = emptyList()): Result {
        val typed = text.trim().replace(Regex("\\s+"), " ")
        val folded = TextSearch.fold(typed)
        // Positions are read on the folded text and applied to the typed text; only safe when they line up.
        if (typed.isEmpty() || folded.length != typed.length) return plain(typed)

        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val spans = mutableListOf<Span>()
        val taken = BooleanArray(typed.length)
        fun free(r: IntRange) = r.none { taken[it] }
        fun claim(part: Part, r: IntRange, shown: String) {
            r.forEach { taken[it] = true }
            spans += Span(part, typed.substring(r.first, r.last + 1), shown, r.first, r.last + 1)
        }

        // --- amount: makes it an expense -----------------------------------------------------------------
        var amount: Long? = null
        if (Part.AMOUNT !in off) {
            val found = AMOUNTS.flatMap { (kind, re) -> re.findAll(folded).map { kind to it } }.sortedBy { it.second.range.first }
            for ((kind, m) in found) {
                if (!free(m.range)) continue
                val value = parseAmount(kind, m.groupValues) ?: continue
                amount = value
                claim(Part.AMOUNT, m.range, Money.format(value))
                break
            }
        }

        // --- repeat -------------------------------------------------------------------------------------
        var recurrence: Recurrence? = null
        var repeatWeekday: DayOfWeek? = null
        if (Part.REPEAT !in off && amount == null) {
            REPEAT.find(folded)?.takeIf { free(it.range) }?.let { m ->
                recurrence = when {
                    m.groupValues[1] == "ngay" -> Recurrence.DAILY
                    m.groupValues[1] == "tuan" -> Recurrence.WEEKLY
                    else -> Recurrence.MONTHLY
                }
                claim(Part.REPEAT, m.range, recurrence!!.label)
            }
            // "mỗi thứ 2" repeats weekly on that day.
            if (recurrence == null) EVERY_WEEKDAY.find(folded)?.takeIf { free(it.range) && isWeekday(typed, it, 1) }?.let { m ->
                repeatWeekday = DayOfWeek.of(DAYS.getValue(m.groupValues[1]))
                recurrence = Recurrence.WEEKLY
                claim(Part.REPEAT, m.range, "Mỗi " + weekdayName(repeatWeekday!!))
            }
        }

        // --- day ----------------------------------------------------------------------------------------
        var date: LocalDate? = null
        if (Part.DAY !in off) {
            date = repeatWeekday?.let { today.with(TemporalAdjusters.next(it)) }
            if (date == null) {
                // Spending is logged after the fact, so "hôm qua" (yesterday) counts only for an expense.
                val past = if (amount != null) YESTERDAY.findAll(folded).map { Candidate(it.range, today.minusDays(1)) }.toList() else emptyList()
                for (candidate in (past + dayCandidates(folded, typed, today)).sortedBy { it.range.first }) {
                    if (free(candidate.range)) {
                        date = candidate.date
                        claim(Part.DAY, candidate.range, dayLabel(candidate.date, today))
                        break
                    }
                }
            }
        }

        // --- time ---------------------------------------------------------------------------------------
        var time: LocalTime? = null
        if (Part.TIME !in off) {
            for (m in TIME.findAll(folded)) {
                val range = widen(folded, m.range)
                if (!free(range)) continue
                val parsed = parseTime(m, period(folded, m.range)) ?: continue
                time = parsed
                claim(Part.TIME, range, "%02d:%02d".format(parsed.hour, parsed.minute))
                break
            }
        }

        // --- importance ---------------------------------------------------------------------------------
        var important = false
        if (Part.IMPORTANT !in off && amount == null) {
            // "gấp" (urgent) folds to the same letters as "gặp" (meet): require the real accent.
            IMPORTANT.find(folded)?.takeIf { free(it.range) && (it.value != "gap" || typed.substring(it.range.first, it.range.last + 1).lowercase() == "gấp") }?.let {
                important = true
                claim(Part.IMPORTANT, it.range, "Quan trọng")
            }
        }

        val title = cleanTitle(typed, taken)
        if (title.isEmpty()) return plain(typed)

        if (amount != null) {
            // Spending happened on the day said, or today; it never repeats or reminds.
            val day = date ?: today
            val at = if (time != null) day.atTime(time).atZone(zone) else day.atStartOfDay(zone)
            return Result(
                title, ItemType.EXPENSE, at.toInstant().toEpochMilli(), time == null, null, false,
                spans.sortedBy { it.start }, amount, Categories.guess(title, history),
            )
        }

        // A time with no day means today, or tomorrow once that time has passed.
        var whenAt: Long? = null
        var allDay = false
        when {
            date != null && time != null -> whenAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
            date != null -> { whenAt = date.atStartOfDay(zone).toInstant().toEpochMilli(); allDay = true }
            time != null -> {
                var at = today.atTime(time).atZone(zone)
                if (at.toInstant().toEpochMilli() < now) at = at.plusDays(1)
                whenAt = at.toInstant().toEpochMilli()
            }
        }
        // A repeat needs a date to repeat from: today if only "mỗi ngày" was said.
        if (recurrence != null && whenAt == null) { whenAt = today.atStartOfDay(zone).toInstant().toEpochMilli(); allDay = true }

        val foldedTitle = TextSearch.fold(title)
        val event = whenAt != null && EVENT_WORDS.any { Regex("\\b$it\\b").containsMatchIn(foldedTitle) }
        return Result(title, if (event) ItemType.EVENT else ItemType.TASK, whenAt, allDay, recurrence, important, spans.sortedBy { it.start })
    }

    private fun plain(text: String) = Result(text, ItemType.TASK, null, false, null, false, emptyList())

    // ---------------------------------------------------------------------------------------- patterns

    private val REPEAT = Regex("\\b(?:moi|hang) (ngay|tuan|thang)\\b")
    private val EVERY_WEEKDAY = Regex("\\bmoi thu (hai|ba|tu|nam|sau|bay|[2-7])\\b")
    private val WEEKDAY = Regex("\\bthu (hai|ba|tu|nam|sau|bay|[2-7])(?: (tuan sau|tuan toi|tuan nay))?\\b")
    private val SUNDAY = Regex("\\b(?:chu nhat|cn)(?: (tuan sau|tuan toi|tuan nay))?\\b")
    private val SLASH_DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?\\b")
    private val LONG_DATE = Regex("\\bngay (\\d{1,2}) thang (\\d{1,2})\\b")
    private val DAY_ONLY = Regex("\\bngay (\\d{1,2})\\b")
    private val YESTERDAY = Regex("\\bhom qua\\b")
    private val RELATIVE = Regex("\\b(hom nay|ngay mai|ngay mot|mai)\\b")
    // 9h, 9h30, 9 giờ, 9 giờ 30, 9 giờ rưỡi, 09:30. "h" must end the word so "2 hộp" is not 2 o'clock.
    private val TIME = Regex("\\b(\\d{1,2})(?: ?h(?:(\\d{2})\\b|(?![a-z]))| ?gio\\b(?: (\\d{2}|ruoi)\\b)?|:(\\d{2}))(?![\\d/])")
    private val PERIOD_AFTER = Regex("^ (sang|trua|chieu|toi)\\b")
    private val PERIOD_BEFORE = Regex("\\b(sang|trua|chieu|toi) $")
    private val IMPORTANT = Regex("\\b(gap|quan trong)\\b")
    // 45k, 45 nghìn, 1tr2, 1.5 triệu, 2 củ, 120.000đ, 50000 vnd. A bare number is never money ("gọi 3 người").
    private val MILLIONS = Regex("\\b(\\d+)(?:[.,](\\d+))? ?(?:tr|trieu|cu)(?: ?(\\d{1,3}))?(?![\\d.,a-z])")
    private val THOUSANDS = Regex("\\b(\\d+)(?:[.,](\\d+))? ?(?:k|nghin|ngan)(?![\\d.,a-z])")
    private val GROUPED = Regex("\\b(\\d{1,3}(?:\\.\\d{3})+)(?: ?(?:d|vnd|dong))?(?![\\d.,a-z])")
    private val WITH_UNIT = Regex("\\b(\\d{3,})(?: ?(?:d|vnd|dong))(?![\\d.,a-z])")
    private enum class Unit { MILLIONS, THOUSANDS, GROUPED, WITH_UNIT }
    private val AMOUNTS = listOf(Unit.MILLIONS to MILLIONS, Unit.THOUSANDS to THOUSANDS, Unit.GROUPED to GROUPED, Unit.WITH_UNIT to WITH_UNIT)

    private fun parseAmount(unit: Unit, g: List<String>): Long? {
        fun fraction(digits: String, scale: Long) = digits.takeIf { it.isNotEmpty() }?.let { ("0.$it".toDouble() * scale).toLong() } ?: 0L
        val amount = when (unit) {
            Unit.MILLIONS -> {
                // "1tr2" = 1.2 triệu, "1tr25" = 1.25 triệu, "1 triệu 200" = 1.200.000.
                val tail = g[3].takeIf { it.isNotEmpty() }?.let { t -> t.toLong() * when (t.length) { 1 -> 100_000L; 2 -> 10_000L; else -> 1_000L } } ?: 0L
                g[1].toLong() * 1_000_000 + fraction(g[2], 1_000_000) + tail
            }
            Unit.THOUSANDS -> g[1].toLong() * 1_000 + fraction(g[2], 1_000)
            Unit.GROUPED -> g[1].replace(".", "").toLong()
            Unit.WITH_UNIT -> g[1].toLong()
        }
        return amount.takeIf { it in 1_000..10_000_000_000 }
    }

    private class Candidate(val range: IntRange, val date: LocalDate)

    private fun isWeekday(typed: String, m: MatchResult, group: Int): Boolean {
        // "thứ tư" (Wednesday) folds to the same letters as "thứ tự" (order): require the real accent.
        if (m.groupValues[group] != "tu") return true
        val g = m.groups[group]!!.range
        return typed.substring(g.first, g.last + 1).lowercase() == "tư"
    }

    private fun dayCandidates(folded: String, typed: String, today: LocalDate): List<Candidate> {
        val out = mutableListOf<Candidate>()
        RELATIVE.findAll(folded).forEach { m ->
            out += Candidate(m.range, today.plusDays(if (m.value == "hom nay") 0 else if (m.value == "ngay mot") 2 else 1))
        }
        WEEKDAY.findAll(folded).filter { isWeekday(typed, it, 1) }.forEach { m ->
            val dow = DayOfWeek.of(DAYS.getValue(m.groupValues[1]))
            out += Candidate(m.range, weekdayDate(today, dow, m.groupValues[2]))
        }
        SUNDAY.findAll(folded).forEach { m -> out += Candidate(m.range, weekdayDate(today, DayOfWeek.SUNDAY, m.groupValues[1])) }
        LONG_DATE.findAll(folded).forEach { m -> monthDay(today, m.groupValues[1].toInt(), m.groupValues[2].toInt(), null)?.let { out += Candidate(m.range, it) } }
        SLASH_DATE.findAll(folded).forEach { m ->
            monthDay(today, m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull())?.let { out += Candidate(m.range, it) }
        }
        DAY_ONLY.findAll(folded).forEach { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) {
                var month = java.time.YearMonth.from(today)
                if (d < today.dayOfMonth) month = month.plusMonths(1)
                if (d <= month.lengthOfMonth()) out += Candidate(m.range, month.atDay(d))
            }
        }
        return out.sortedBy { it.range.first }
    }

    /** The coming [dow]; "tuần sau" means that day of next week, "tuần này" the day this week even if past. */
    private fun weekdayDate(today: LocalDate, dow: DayOfWeek, qualifier: String): LocalDate = when (qualifier) {
        "tuan sau", "tuan toi" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
        "tuan nay" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(dow))
        else -> today.with(TemporalAdjusters.next(dow))
    }

    private fun monthDay(today: LocalDate, day: Int, month: Int, year: Int?): LocalDate? = try {
        if (year != null) LocalDate.of(year, month, day)
        else LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it }
    } catch (_: java.time.DateTimeException) {
        null
    }

    /** Extends a time match over an adjacent "sáng/trưa/chiều/tối" so the word is not left in the title. */
    private fun widen(folded: String, r: IntRange): IntRange {
        PERIOD_AFTER.find(folded.substring(r.last + 1))?.let { return r.first..(r.last + it.range.last + 1) }
        PERIOD_BEFORE.find(folded.substring(0, r.first))?.let { return it.range.first..r.last }
        return r
    }

    private fun period(folded: String, r: IntRange): String? =
        PERIOD_AFTER.find(folded.substring(r.last + 1))?.groupValues?.get(1) ?: PERIOD_BEFORE.find(folded.substring(0, r.first))?.groupValues?.get(1)

    private fun parseTime(m: MatchResult, period: String?): LocalTime? {
        var hour = m.groupValues[1].toInt()
        val minute = when {
            m.groupValues[3] == "ruoi" -> 30
            m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
            m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
            m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
            else -> 0
        }
        if (hour > 23 || minute > 59) return null
        when (period) {
            "chieu", "toi" -> if (hour in 1..11) hour += 12
            "trua" -> if (hour in 1..5) hour += 12
        }
        return LocalTime.of(hour, minute)
    }

    private fun cleanTitle(typed: String, taken: BooleanArray): String {
        val kept = buildString { typed.forEachIndexed { i, c -> append(if (taken[i]) ' ' else c) } }
        val words = kept.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val connectors = setOf("lúc", "vào", "ngày", "hạn", "trước", "luc", "vao", "truoc", "-", ",")
        while (words.isNotEmpty() && words.first().lowercase() in connectors) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase() in connectors) words.removeAt(words.lastIndex)
        return words.joinToString(" ").trim(' ', ',', ';', '-')
    }

    private fun weekdayName(d: DayOfWeek) = if (d == DayOfWeek.SUNDAY) "Chủ nhật" else "Thứ ${d.value + 1}"

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Hôm nay"
        today.plusDays(1) -> "Mai"
        today.minusDays(1) -> "Hôm qua"
        else -> weekdayName(date.dayOfWeek) + " " + date.dayOfMonth + "/" + date.monthValue
    }
}
