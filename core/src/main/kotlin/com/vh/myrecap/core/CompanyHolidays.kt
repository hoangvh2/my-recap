package com.vh.myrecap.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Days off the company gives, typed in by the person. Port of shared/company.ts: an entry is a day or a run of days;
 * a [yearly] entry repeats on the same solar day each year from the year it was entered, any other belongs to that year.
 */
data class CompanyHoliday(val id: String, val name: String, val from: LocalDate, val to: LocalDate, val yearly: Boolean)

/** One occurrence of a company holiday; [key] is what a dismissed banner and a notification are keyed by. */
data class CompanyBreak(val key: String, val name: String, val from: LocalDate, val to: LocalDate) {
    val days: Int get() = ChronoUnit.DAYS.between(from, to).toInt() + 1
}

data class CompanyAhead(val brk: CompanyBreak, val daysUntil: Int)

object CompanyHolidays {
    const val MAX = 60
    const val NAME_MAX = 60
    const val MAX_DAYS = 31

    /** The occurrences that start in [year]. */
    fun ofYear(list: List<CompanyHoliday>, year: Int): List<CompanyBreak> = list.mapNotNull { c ->
        val span = ChronoUnit.DAYS.between(c.from, c.to)
        val from = when {
            c.yearly -> if (year < c.from.year) return@mapNotNull null else sameDay(c.from, year)
            c.from.year == year -> c.from
            else -> return@mapNotNull null
        }
        CompanyBreak("company:${c.id}:$year", c.name, from, from.plusDays(span))
    }.sortedBy { it.from }

    /** The same month and day in another year; 29/2 becomes 28/2 in a year that has none. */
    private fun sameDay(d: LocalDate, year: Int): LocalDate = d.withYear(year).let { if (d.monthValue == 2 && d.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28) else it }

    private fun around(list: List<CompanyHoliday>, date: LocalDate): List<CompanyBreak> =
        (date.year - 1..date.year + 1).flatMap { ofYear(list, it) }.sortedBy { it.from }

    /** The company break a date falls in, if any. */
    fun on(date: LocalDate, list: List<CompanyHoliday>): CompanyBreak? = around(list, date).firstOrNull { date >= it.from && date <= it.to }

    /** The company break to put in a banner today: one that is on, or starts within [lead] days. */
    fun banner(today: LocalDate, lead: Int, dismissed: Set<String>, list: List<CompanyHoliday>): CompanyAhead? {
        for (b in around(list, today).filter { it.to >= today }) {
            val until = ChronoUnit.DAYS.between(today, b.from).toInt()
            if (until > lead) return null
            if (b.key !in dismissed) return CompanyAhead(b, until)
        }
        return null
    }

    fun span(b: CompanyBreak): String = if (b.from == b.to) LunarText.dm(b.from) else "${LunarText.dm(b.from)} đến ${LunarText.dm(b.to)}"

    /** One line for a banner or a notification. */
    fun line(b: CompanyBreak, daysUntil: Int): String {
        val whenText = when {
            daysUntil < 0 -> "Nghỉ đến hết ${LunarText.dm(b.to)}."
            daysUntil == 0 -> "Nghỉ từ hôm nay."
            daysUntil == 1 -> "Nghỉ từ ngày mai."
            else -> "Còn $daysUntil ngày."
        }
        return "$whenText Nghỉ ${span(b)} (${b.days} ngày). Ngày nghỉ của công ty."
    }

    /** Why a draft cannot be saved, or null. */
    fun problem(name: String, from: LocalDate?, to: LocalDate?): String? = when {
        name.isBlank() -> "Nhập tên kỳ nghỉ."
        from == null -> "Chọn ngày bắt đầu."
        to == null -> "Chọn ngày kết thúc."
        to < from -> "Ngày kết thúc phải sau ngày bắt đầu."
        ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS -> "Một kỳ nghỉ dài tối đa $MAX_DAYS ngày."
        else -> null
    }

    fun toJson(list: List<CompanyHoliday>): String = JSONArray().also { arr ->
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("from", it.from.toString()).put("to", it.to.toString()).put("yearly", it.yearly)) }
    }.toString()

    /** Reads a stored list, dropping anything malformed so a bad value never breaks the calendar. */
    fun parse(json: String?): List<CompanyHoliday> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(json) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<CompanyHoliday>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").take(40).takeIf { it.isNotEmpty() && out.none { x -> x.id == it } } ?: continue
            val name = o.optString("name").trim().take(NAME_MAX)
            val from = date(o.optString("from"))
            val to = date(o.optString("to"))
            if (problem(name, from, to) != null) continue
            out += CompanyHoliday(id, name, from!!, to!!, o.optBoolean("yearly", false))
            if (out.size >= MAX) break
        }
        return out
    }

    private fun date(s: String): LocalDate? = try { if (s.length == 10) LocalDate.parse(s) else null } catch (_: Exception) { null }
}
