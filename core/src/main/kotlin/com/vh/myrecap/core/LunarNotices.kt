package com.vh.myrecap.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A notification to schedule: [key] is stable, so a re-plan replaces rather than repeats it. */
data class LunarNotice(val key: String, val at: LocalDateTime, val title: String, val body: String)

/**
 * The local notifications for upcoming breaks and (when chosen) the evening before mùng 1 and rằm.
 * Pure, so the Android scheduler only has to arm what this returns.
 */
object LunarNotices {
    const val HOLIDAY_HOUR = 8
    const val MONTHLY_HOUR = 18
    const val HORIZON_DAYS = 400
    const val MAX = 40

    fun plan(now: LocalDateTime, prefs: LunarPrefs, overrides: List<HolidayOverride>): List<LunarNotice> {
        val today = now.toLocalDate()
        val out = mutableListOf<LunarNotice>()

        if (prefs.banner && prefs.holidays) {
            val horizon = today.plusDays(HORIZON_DAYS.toLong())
            for (year in today.year..today.year + 1) {
                for (h in Holidays.ofYear(year, overrides)) {
                    if (h.from > horizon || h.to < today || h.key in prefs.dismissed) continue
                    val day = h.from.minusDays(Holidays.leadFor(h.id, prefs.lead).toLong())
                    val at = day.atTime(HOLIDAY_HOUR, 0)
                    if (at <= now) continue
                    val until = java.time.temporal.ChronoUnit.DAYS.between(day, h.from).toInt()
                    out += LunarNotice("holiday:${h.key}", at, "Sắp nghỉ · ${h.name}", LunarText.holidayLine(h, until))
                }
            }
        }

        if (prefs.monthly) {
            var d = today
            while (d <= today.plusDays(HORIZON_DAYS.toLong())) {
                val l = Lunar.toLunar(d.plusDays(1))
                val mark = LunarText.monthlyMark(l)
                val at = d.atTime(MONTHLY_HOUR, 0)
                if (mark != null && l != null && at > now) {
                    val name = LunarText.full(l).removePrefix("$mark ")
                    out += LunarNotice("monthly:${d.plusDays(1)}", at, "Mai là $mark $name", "Chuẩn bị đồ cúng.")
                }
                d = d.plusDays(1)
            }
        }
        return out.sortedBy { it.at }.take(MAX)
    }

    fun epochMs(n: LunarNotice, zone: ZoneId): Long = n.at.atZone(zone).toInstant().toEpochMilli()
}
