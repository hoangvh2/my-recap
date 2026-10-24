package com.vh.myrecap.core

import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidaysTest {
    private val overrides = Holidays.parseOverrides(File("../shared/holidays-vn.json").readText())
    private fun d(s: String) = LocalDate.parse(s)
    private fun get(id: HolidayId, y: Int) = Holidays.ofYear(y, overrides).first { it.id == id }

    @Test
    fun bundledFileIsTheSharedFile() {
        assertEquals(overrides, Holidays.bundled())
        assertTrue(overrides.isNotEmpty())
    }

    @Test
    fun ruleBreaksMatchTheWeb() {
        get(HolidayId.GIO_TO, 2026).let { assertEquals(listOf(d("2026-04-25"), d("2026-04-27"), false), listOf(it.from, it.to, it.announced)) }
        get(HolidayId.LE_30_4_1_5, 2026).let { assertEquals(listOf(d("2026-04-30"), d("2026-05-03")), listOf(it.from, it.to)) }
        get(HolidayId.LE_30_4_1_5, 2027).let { assertEquals(listOf(d("2027-04-30"), d("2027-05-03")), listOf(it.from, it.to)) }
        get(HolidayId.GIO_TO, 2027).let { assertEquals(listOf(d("2027-04-16"), d("2027-04-18")), listOf(it.main, it.to)) }
        get(HolidayId.TET_DUONG_LICH, 2027).let { assertEquals(listOf(d("2027-01-01"), d("2027-01-03")), listOf(it.from, it.to)) }
        get(HolidayId.TET_NGUYEN_DAN, 2028).let {
            assertFalse(it.announced)
            assertEquals(d("2028-01-26"), it.main)
            assertTrue(it.from <= d("2028-01-25") && it.to >= d("2028-01-29"))
        }
    }

    @Test
    fun announcedSchedulesReplaceTheRule() {
        val tet = get(HolidayId.TET_NGUYEN_DAN, 2027)
        assertEquals(listOf(d("2027-02-04"), d("2027-02-10"), 7, true), listOf(tet.from, tet.to, tet.days, tet.announced))
        assertTrue(tet.source!!.contains("11483/TB-BNV"))
        assertEquals(listOf(d("2026-08-22")), get(HolidayId.QUOC_KHANH, 2026).workdays)
    }

    @Test
    fun bannerShowsTheBreakWithinNoticeAndRespectsHide() {
        val b = assertNotNull(Holidays.banner(d("2027-04-12"), 5, emptySet(), overrides))
        assertEquals(listOf(HolidayId.GIO_TO, 4), listOf(b.holiday.id, b.daysUntil))
        assertNull(Holidays.banner(d("2027-04-05"), 5, emptySet(), overrides))
        assertNull(Holidays.banner(d("2027-04-12"), 5, setOf("gio-to:2027"), overrides))
        assertEquals(HolidayId.TET_NGUYEN_DAN, Holidays.banner(d("2027-01-25"), 3, emptySet(), overrides)!!.holiday.id)
        assertNull(Holidays.banner(d("2027-01-15"), 3, emptySet(), overrides))
        assertEquals(-2, Holidays.banner(d("2027-02-06"), 5, emptySet(), overrides)!!.daysUntil)
        assertEquals(HolidayId.TET_DUONG_LICH, Holidays.next(d("2026-12-30"), overrides)!!.holiday.id)
    }

    @Test
    fun calendarMarks() {
        assertEquals(DayKind.LEGAL, Holidays.on(d("2027-04-16"), overrides)!!.second)
        assertEquals(DayKind.WEEKEND, Holidays.on(d("2027-04-17"), overrides)!!.second)
        assertEquals(DayKind.EXTRA, Holidays.on(d("2027-02-04"), overrides)!!.second)
        assertNull(Holidays.on(d("2027-03-10"), overrides))
    }

    @Test
    fun malformedFileIsIgnoredRowByRow() {
        val json = """{"periods":[{"id":"nope","year":2027,"from":"2027-01-01","to":"2027-01-02"},
            {"id":"gio-to","year":2027,"from":"2027-04-20","to":"2027-04-16"},
            {"id":"gio-to","year":2027,"from":"2027-04-16","to":"2027-04-18"}]}"""
        assertEquals(listOf(HolidayOverride(HolidayId.GIO_TO, 2027, d("2027-04-16"), d("2027-04-18"))), Holidays.parseOverrides(json))
        assertTrue(Holidays.parseOverrides("not json").isEmpty())
        assertTrue(Holidays.parseOverrides(null).isEmpty())
    }
}
