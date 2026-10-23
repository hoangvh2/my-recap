package com.vh.myrecap.core

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** A lunar date. [leap] is true in a leap month (tháng nhuận). */
data class LunarDate(val day: Int, val month: Int, val year: Int, val leap: Boolean) {
    /** "6/3" or "1/3" (a leap month gets "n"), the form shown next to a solar date. */
    val short: String get() = "$day/$month${if (leap) "n" else ""}"
}

/** Where a lunar month starts and how many days (29 or 30) it has. */
data class LunarMonth(val start: LocalDate, val days: Int)

/**
 * Vietnamese lunisolar calendar (UTC+7), computed on the device. Port of shared/lunar.ts (astronomical method of
 * Hồ Ngọc Đức); both are tested against shared/lunar-vectors.json and must stay identical.
 * Valid for 1900 to 2199 (outside it the functions return null).
 */
object Lunar {
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2199

    private const val TZ = 7
    private const val SYNODIC = 29.530588853
    private const val JD_OF_EPOCH = 2440588 // Julian day number of 1970-01-01

    private fun jdOf(d: LocalDate): Int = (d.toEpochDay() + JD_OF_EPOCH).toInt()
    private fun dateOfJd(jd: Int): LocalDate = LocalDate.ofEpochDay((jd - JD_OF_EPOCH).toLong())
    private fun fl(x: Double): Int = floor(x).toInt()
    private fun inRange(y: Int) = y in MIN_YEAR..MAX_YEAR

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = PI / 180
        var jd1 = 2415020.75933 + SYNODIC * k + 0.0001178 * t2 - 0.000000155 * t3
        jd1 += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c1 += -0.4068 * sin(mpr * dr) + 0.0161 * sin(dr * 2 * mpr) - 0.0004 * sin(dr * 3 * mpr)
        c1 += 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr)) - 0.0074 * sin(dr * (m - mpr))
        c1 += 0.0004 * sin(dr * (2 * f + m)) - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mpr))
        c1 += 0.001 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
        val dt = if (t < -11) 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        else -0.000278 + 0.000265 * t + 0.000262 * t2
        return jd1 + c1 - dt
    }

    /** Sun longitude at local midnight of the day, as a 30° sector 0..11. */
    private fun sunSector(jdn: Int): Int {
        val t = (jdn - 0.5 - TZ / 24.0 - 2451545.0) / 36525
        val t2 = t * t
        val dr = PI / 180
        val m = 357.5291 + 35999.0503 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var dl = (1.9146 - 0.004817 * t - 0.000014 * t2) * sin(dr * m)
        dl += (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.00029 * sin(dr * 3 * m)
        var l = (l0 + dl) * dr
        l -= PI * 2 * floor(l / (PI * 2))
        return fl(l / PI * 6)
    }

    private fun newMoonDay(k: Int): Int = fl(newMoon(k) + 0.5 + TZ / 24.0)

    private fun month11Start(yy: Int): Int {
        val off = jdOf(LocalDate.of(yy, 12, 31)) - 2415021
        val k = fl(off / SYNODIC)
        var nm = newMoonDay(k)
        if (sunSector(nm) >= 9) nm = newMoonDay(k - 1)
        return nm
    }

    private fun leapOffset(a11: Int): Int {
        val k = fl((a11 - 2415021.076998695) / SYNODIC + 0.5)
        var i = 1
        var arc = sunSector(newMoonDay(k + i))
        var last: Int
        do {
            last = arc
            i++
            arc = sunSector(newMoonDay(k + i))
        } while (arc != last && i < 14)
        return i - 1
    }

    fun toLunar(date: LocalDate): LunarDate? {
        val yy = date.year
        if (!inRange(yy)) return null
        val dayNumber = jdOf(date)
        // The estimate of the lunation can be one off when a new moon falls just after midnight, so settle it by checking.
        var k = fl((dayNumber - 2415021.076998695) / SYNODIC)
        while (newMoonDay(k + 1) <= dayNumber) k++
        while (newMoonDay(k) > dayNumber) k--
        val monthStart = newMoonDay(k)
        var a11 = month11Start(yy)
        var b11 = a11
        var year: Int
        if (a11 >= monthStart) {
            year = yy
            a11 = month11Start(yy - 1)
        } else {
            year = yy + 1
            b11 = month11Start(yy + 1)
        }
        val day = dayNumber - monthStart + 1
        val diff = (monthStart - a11) / 29
        var leap = false
        var month = diff + 11
        if (b11 - a11 > 365) {
            val lo = leapOffset(a11)
            if (diff >= lo) {
                month = diff + 10
                if (diff == lo) leap = true
            }
        }
        if (month > 12) month -= 12
        if (month >= 11 && diff < 4) year -= 1
        return LunarDate(day, month, year, leap)
    }

    /** Where a lunar month starts and its length; null when that month does not exist. */
    fun month(year: Int, month: Int, leap: Boolean = false): LunarMonth? {
        if (!inRange(year) || month !in 1..12) return null
        val a11: Int
        val b11: Int
        if (month < 11) {
            a11 = month11Start(year - 1)
            b11 = month11Start(year)
        } else {
            a11 = month11Start(year)
            b11 = month11Start(year + 1)
        }
        val k = fl(0.5 + (a11 - 2415021.076998695) / SYNODIC)
        var off = month - 11
        if (off < 0) off += 12
        if (b11 - a11 > 365) {
            val lo = leapOffset(a11)
            var leapMonth = lo - 2
            if (leapMonth < 0) leapMonth += 12
            if (leap && month != leapMonth) return null
            if (leap || off >= lo) off += 1
        } else if (leap) {
            return null
        }
        val start = newMoonDay(k + off)
        return LunarMonth(dateOfJd(start), newMoonDay(k + off + 1) - start)
    }

    /** The solar date of a lunar date; null when it does not exist (no such leap month, or day 30 of a 29-day month). */
    fun toSolar(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate? {
        val m = month(year, month, leap) ?: return null
        return if (day < 1 || day > m.days) null else m.start.plusDays((day - 1).toLong())
    }

    /** Like [toSolar] but day 30 of a 29-day month becomes its last day (the rule for lunar repeats). */
    fun toSolarClamped(year: Int, month: Int, day: Int, leap: Boolean = false): LocalDate? {
        val m = month(year, month, leap) ?: return null
        return if (day < 1) null else m.start.plusDays((minOf(day, m.days) - 1).toLong())
    }

    /** The month after (year, month, leap): a leap month is followed by the regular next month. */
    fun nextMonth(d: LunarDate): Triple<Int, Int, Boolean> {
        if (!d.leap && month(d.year, d.month, true) != null) return Triple(d.year, d.month, true)
        return if (d.month == 12) Triple(d.year + 1, 1, false) else Triple(d.year, d.month + 1, false)
    }

    /**
     * Date of the next occurrence of a lunar repeat after [date]. Yearly: same lunar day and month next lunar year
     * (a date in a leap month moves to the regular month). Monthly: same lunar day next lunar month (leap months count).
     * Day 30 of a 29-day month becomes day 29. Null outside the supported years.
     */
    fun nextOccurrence(date: LocalDate, rule: Recurrence): LocalDate? {
        val l = toLunar(date) ?: return null
        return when (rule) {
            Recurrence.LUNAR_YEARLY -> toSolarClamped(l.year + 1, l.month, l.day, false)
            Recurrence.LUNAR_MONTHLY -> nextMonth(l).let { (y, m, leap) -> toSolarClamped(y, m, l.day, leap) }
            else -> null
        }
    }
}
