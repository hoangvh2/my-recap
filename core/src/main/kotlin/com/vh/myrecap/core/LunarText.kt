package com.vh.myrecap.core

import java.time.DayOfWeek
import java.time.LocalDate

/** Vietnamese wording for lunar dates and holidays; the same text as the web app (web/src/lib/lunarText.ts). */
object LunarText {
    private val WEEKDAY = mapOf(
        DayOfWeek.MONDAY to "Thứ Hai", DayOfWeek.TUESDAY to "Thứ Ba", DayOfWeek.WEDNESDAY to "Thứ Tư", DayOfWeek.THURSDAY to "Thứ Năm",
        DayOfWeek.FRIDAY to "Thứ Sáu", DayOfWeek.SATURDAY to "Thứ Bảy", DayOfWeek.SUNDAY to "Chủ Nhật",
    )

    fun weekday(d: LocalDate): String = WEEKDAY.getValue(d.dayOfWeek)

    /** "Thứ Sáu 16/4/2027". */
    fun longDate(d: LocalDate): String = "${weekday(d)} ${d.dayOfMonth}/${d.monthValue}/${d.year}"

    /** "16/4". */
    fun dm(d: LocalDate): String = "${d.dayOfMonth}/${d.monthValue}"

    /** The lunar date in short form for a chip: "6/3 Âm" (a leap month: "6/3n Âm"). */
    fun chip(l: LunarDate?): String = if (l == null) "" else "${l.short} Âm"

    /** "Mùng 6 tháng 3 Âm", "Rằm tháng 3 Âm", "22 tháng 3 nhuận Âm". */
    fun full(l: LunarDate): String {
        val day = when {
            l.day == 15 -> "Rằm"
            l.day <= 10 -> "Mùng ${l.day}"
            else -> l.day.toString()
        }
        return "$day tháng ${l.month}${if (l.leap) " nhuận" else ""} Âm"
    }

    /** "Mùng 1" or "Rằm" for those two days of a lunar month, otherwise null. */
    fun monthlyMark(l: LunarDate?): String? = when (l?.day) {
        1 -> "Mùng 1"
        15 -> "Rằm"
        else -> null
    }

    /** Body of the "Xoá mục này?" dialog: the title, and a warning when a repeating item takes its later occurrences with it. */
    fun deleteBody(title: String, recurrence: Recurrence?): String =
        if (recurrence == null) title else "$title\nViệc này lặp lại (${recurrence.label.lowercase()}). Xoá sẽ bỏ cả các lần sau."

    /** One line about a holiday for a banner or a notification. */
    fun holidayLine(h: Holiday, daysUntil: Int): String {
        val main = longDate(h.main) + (h.lunarText?.let { " ($it)" } ?: "")
        val span = if (h.from == h.to) dm(h.from) else "${dm(h.from)} đến ${dm(h.to)}"
        val whenText = when {
            daysUntil < 0 -> "Nghỉ đến hết ${dm(h.to)}."
            daysUntil == 0 -> "Nghỉ từ hôm nay."
            daysUntil == 1 -> "Nghỉ từ ngày mai."
            else -> "Còn $daysUntil ngày."
        }
        return "$main. $whenText Nghỉ $span (${h.days} ngày)."
    }

    /** How sure the schedule is, shown under a banner. */
    fun holidayBasis(h: Holiday): String =
        if (h.announced) "Lịch nghỉ đã công bố" + (h.source?.let { ": $it" } ?: "")
        else "Tính theo Bộ luật Lao động, chưa có thông báo riêng. Cơ quan, công ty có thể khác."

    /** "· 10/3 Âm · Giỗ Tổ Hùng Vương" to put after a day's name (empty when nothing applies). */
    fun dayExtras(d: LocalDate, prefs: LunarPrefs, overrides: List<HolidayOverride>): String {
        val parts = mutableListOf<String>()
        val l = Lunar.toLunar(d)
        if (prefs.show && l != null) parts += chip(l)
        if (prefs.holidays) Holidays.on(d, overrides)?.takeIf { it.second != DayKind.WEEKEND }?.let { parts += it.first.name }
        if (prefs.monthly) monthlyMark(l)?.let { m -> if (parts.none { it.contains(m) }) parts += m }
        return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
    }
}

/** What the lunar calendar and holiday features show; mirrors the web settings. */
data class LunarPrefs(
    val show: Boolean = true,
    val holidays: Boolean = true,
    val banner: Boolean = true,
    /** Days of notice before a break (Tết always at least 14). */
    val lead: Int = 5,
    /** Mùng 1 and Rằm marked, with a notice the evening before. Off until chosen. */
    val monthly: Boolean = false,
    /** Banners hidden by the person, as [Holiday.key]. */
    val dismissed: Set<String> = emptySet(),
) {
    companion object {
        val LEAD_CHOICES = listOf(3, 5, 7, 14)
    }
}
