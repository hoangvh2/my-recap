package com.vh.myrecap.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class LunarTest {
    // Gradle runs core tests with core/ as the working directory.
    private val vectors = JSONObject(File("../shared/lunar-vectors.json").readText())

    private fun pairs(key: String): List<Pair<String, Int>> =
        vectors.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getInt(1) } } }

    @Test
    fun newYearAndGioToMatchPublishedDates() {
        for ((iso, year) in pairs("newYear")) {
            assertEquals(LunarDate(1, 1, year, false), Lunar.toLunar(LocalDate.parse(iso)), iso)
            assertEquals(LocalDate.parse(iso), Lunar.toSolar(year, 1, 1), "$year")
        }
        for ((iso, year) in pairs("gioTo")) assertEquals(LocalDate.parse(iso), Lunar.toSolar(year, 3, 10), "$year")
    }

    @Test
    fun knownLeapMonthsAndNoOthers() {
        val found = mutableListOf<List<Int>>()
        for (y in 2020..2029) for (m in 1..12) if (Lunar.month(y, m, true) != null) found += listOf(y, m)
        val expected = vectors.getJSONArray("leapMonths").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> listOf(p.getInt(0), p.getInt(1)) } } }
        assertEquals(expected.filter { it[0] <= 2029 }, found)
    }

    @Test
    fun roundTripsEveryDayAndIsContinuous() {
        var d = LocalDate.of(1950, 1, 1)
        val end = LocalDate.of(2150, 12, 31)
        var prev: LunarDate? = null
        while (d <= end) {
            val l = assertNotNull(Lunar.toLunar(d))
            assertEquals(d, Lunar.toSolar(l.year, l.month, l.day, l.leap), "$d")
            if (prev != null) {
                if (l.day == 1) assertTrue(prev.day == 29 || prev.day == 30, "$d")
                else assertEquals(LunarDate(prev.day + 1, prev.month, prev.year, prev.leap), l, "$d")
            }
            prev = l
            d = d.plusDays(1)
        }
    }

    @Test
    fun outsideSupportedYearsIsNull() {
        assertNull(Lunar.toLunar(LocalDate.of(1850, 1, 1)))
        assertNull(Lunar.toLunar(LocalDate.of(2300, 1, 1)))
    }

    @Test
    fun repeatsMatchTheWebRules() {
        assertEquals(LocalDate.of(2028, 4, 14), Lunar.nextOccurrence(LocalDate.of(2027, 4, 26), Recurrence.LUNAR_YEARLY))
        val leap = Lunar.toSolar(2025, 6, 15, true)!!
        assertEquals(Lunar.toSolar(2026, 6, 15), Lunar.nextOccurrence(leap, Recurrence.LUNAR_YEARLY))
        assertEquals(Lunar.toSolar(2025, 6, 15, true), Lunar.nextOccurrence(Lunar.toSolar(2025, 6, 15)!!, Recurrence.LUNAR_MONTHLY))
        assertEquals(Lunar.toSolar(2025, 7, 15), Lunar.nextOccurrence(leap, Recurrence.LUNAR_MONTHLY))
    }

    @Test
    fun scheduleKeepsTheWallClockTime() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val at = LocalDate.of(2027, 4, 26).atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        val n = java.time.Instant.ofEpochMilli(Schedule.next(at, Recurrence.LUNAR_YEARLY, zone)).atZone(zone)
        assertEquals(LocalDate.of(2028, 4, 14).atTime(8, 30), n.toLocalDateTime())
    }
}
