package com.vh.myrecap.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.json.JSONObject

/**
 * Vietnamese public holidays. Port of shared/holidays.ts: the days come from the labour code (Điều 112), a holiday on a
 * weekend is made up on the next working day (Điều 111), and what the government announces each year (adjacent day of
 * Quốc khánh, swapped working days, longer Tết) is a per-year override from shared/holidays-vn.json.
 */
enum class HolidayId(val key: String, val label: String) {
    TET_DUONG_LICH("tet-duong-lich", "Tết Dương lịch"),
    TET_NGUYEN_DAN("tet-nguyen-dan", "Tết Nguyên đán"),
    GIO_TO("gio-to", "Giỗ Tổ Hùng Vương"),
    LE_30_4_1_5("30-4-1-5", "Lễ 30/4 và 1/5"),
    QUOC_KHANH("quoc-khanh", "Quốc khánh 2/9"),
}

/** The schedule announced for one holiday of one year. */
data class HolidayOverride(
    val id: HolidayId,
    val year: Int,
    val from: LocalDate,
    val to: LocalDate,
    val workdays: List<LocalDate> = emptyList(),
    val source: String? = null,
)

data class Holiday(
    val id: HolidayId,
    val year: Int,
    /** First and last day of the whole break, weekends included. */
    val from: LocalDate,
    val to: LocalDate,
    /** The days the law names. */
    val legal: List<LocalDate>,
    /** The date the holiday is about (mùng 1 Tết, 10/3 Âm, 2/9...). */
    val main: LocalDate,
    val workdays: List<LocalDate>,
    /** True when an official schedule was announced for the year, false when computed from the law. */
    val announced: Boolean,
    val source: String?,
) {
    val name: String get() = id.label
    val days: Int get() = ChronoUnit.DAYS.between(from, to).toInt() + 1
    val key: String get() = "${id.key}:$year"
    /** "10/3 Âm" or "mùng 1 Âm" for the lunar holidays. */
    val lunarText: String?
        get() = when (id) {
            HolidayId.GIO_TO -> "10/3 Âm"
            HolidayId.TET_NGUYEN_DAN -> "mùng 1 Âm"
            else -> null
        }
}

enum class DayKind { LEGAL, EXTRA, WEEKEND }

data class HolidayAhead(val holiday: Holiday, val daysUntil: Int)

object Holidays {
    private fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun legalDays(id: HolidayId, y: Int): Pair<List<LocalDate>, LocalDate>? = when (id) {
        HolidayId.TET_DUONG_LICH -> LocalDate.of(y, 1, 1).let { listOf(it) to it }
        HolidayId.TET_NGUYEN_DAN -> Lunar.toSolar(y, 1, 1)?.let { tet -> (-1L..3L).map { tet.plusDays(it) } to tet }
        HolidayId.GIO_TO -> Lunar.toSolar(y, 3, 10)?.let { listOf(it) to it }
        HolidayId.LE_30_4_1_5 -> listOf(LocalDate.of(y, 4, 30), LocalDate.of(y, 5, 1)) to LocalDate.of(y, 4, 30)
        // Two days: 2/9 and one next to it. Which one is announced each year, so the rule guesses the day after.
        HolidayId.QUOC_KHANH -> listOf(LocalDate.of(y, 9, 2), LocalDate.of(y, 9, 3)) to LocalDate.of(y, 9, 2)
    }

    /** Legal days, made up on the next working day when on a weekend, joined to touching weekends. */
    private fun ruleBreak(days: List<LocalDate>): Pair<LocalDate, LocalDate> {
        val off = days.toMutableSet()
        for (d in days) {
            if (!weekend(d)) continue
            var n = d.plusDays(1)
            while (weekend(n) || n in off) n = n.plusDays(1)
            off += n
        }
        var from = off.min()
        var to = off.max()
        while (weekend(from.minusDays(1))) from = from.minusDays(1)
        while (weekend(to.plusDays(1))) to = to.plusDays(1)
        return from to to
    }

    fun ofYear(year: Int, overrides: List<HolidayOverride> = emptyList()): List<Holiday> =
        HolidayId.entries.mapNotNull { id ->
            val (legal, main) = legalDays(id, year) ?: return@mapNotNull null
            val o = overrides.firstOrNull { it.id == id && it.year == year }
            val (from, to) = if (o != null) o.from to o.to else ruleBreak(legal)
            Holiday(id, year, from, to, legal, main, o?.workdays ?: emptyList(), o != null, o?.source)
        }.sortedBy { it.from }

    private fun around(date: LocalDate, overrides: List<HolidayOverride>): List<Holiday> =
        (date.year - 1..date.year + 1).flatMap { ofYear(it, overrides) }.sortedBy { it.from }

    /** Which holiday a date belongs to and whether the day is named by the law or an extra day off. */
    fun on(date: LocalDate, overrides: List<HolidayOverride> = emptyList()): Pair<Holiday, DayKind>? {
        val h = around(date, overrides).firstOrNull { date >= it.from && date <= it.to } ?: return null
        return h to if (date in h.legal) DayKind.LEGAL else if (weekend(date)) DayKind.WEEKEND else DayKind.EXTRA
    }

    /** The first break that has not ended yet. */
    fun next(today: LocalDate, overrides: List<HolidayOverride> = emptyList()): HolidayAhead? =
        around(today, overrides).firstOrNull { it.to >= today }?.let { HolidayAhead(it, ChronoUnit.DAYS.between(today, it.from).toInt()) }

    /** Days of notice before a break: Tết needs the longest because people travel. */
    fun leadFor(id: HolidayId, lead: Int): Int = if (id == HolidayId.TET_NGUYEN_DAN) maxOf(14, lead) else lead

    /** The break to put in a banner today: one that is on, or starts within the notice. [dismissed] holds [Holiday.key]s. */
    fun banner(today: LocalDate, lead: Int, dismissed: Set<String>, overrides: List<HolidayOverride> = emptyList()): HolidayAhead? {
        for (h in around(today, overrides).filter { it.to >= today }) {
            val until = ChronoUnit.DAYS.between(today, h.from).toInt()
            if (until > leadFor(h.id, lead)) return null
            if (h.key !in dismissed) return HolidayAhead(h, until)
        }
        return null
    }

    /** Reads holidays-vn.json, dropping anything malformed so a bad file never breaks the app. */
    fun parseOverrides(json: String?): List<HolidayOverride> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = try {
            JSONObject(json).optJSONArray("periods")
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val out = mutableListOf<HolidayOverride>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = HolidayId.entries.firstOrNull { it.key == o.optString("id") } ?: continue
            val year = o.optInt("year", 0).takeIf { it in Lunar.MIN_YEAR..Lunar.MAX_YEAR } ?: continue
            val from = date(o.optString("from")) ?: continue
            val to = date(o.optString("to")) ?: continue
            if (from > to || ChronoUnit.DAYS.between(from, to) > 16) continue
            val workdays = o.optJSONArray("workdays")?.let { a -> (0 until minOf(a.length(), 8)).mapNotNull { date(a.optString(it)) } } ?: emptyList()
            out += HolidayOverride(id, year, from, to, workdays, o.optString("source").takeIf { it.isNotEmpty() }?.take(200))
        }
        return out
    }

    private fun date(s: String): LocalDate? = try {
        if (s.length == 10) LocalDate.parse(s) else null
    } catch (_: Exception) {
        null
    }

    /** The bundled file (core/src/main/resources/holidays-vn.json, copied from shared/ by the build). */
    fun bundled(): List<HolidayOverride> =
        Holidays::class.java.getResourceAsStream("/holidays-vn.json")?.use { parseOverrides(it.readBytes().toString(Charsets.UTF_8)) } ?: emptyList()
}
