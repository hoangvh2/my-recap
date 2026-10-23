package com.vh.myrecap.core

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LunarNoticesTest {
    private val overrides = Holidays.parseOverrides(File("../shared/holidays-vn.json").readText())
    private fun at(s: String) = LocalDateTime.parse(s)

    @Test
    fun textMatchesTheWeb() {
        val l = Lunar.toLunar(LocalDate.of(2027, 4, 12))!!
        assertEquals("6/3 Âm", LunarText.chip(l))
        assertEquals("Mùng 6 tháng 3 Âm", LunarText.full(l))
        assertEquals("Rằm tháng 3 Âm", LunarText.full(Lunar.toLunar(LocalDate.of(2027, 4, 21))!!))
        assertEquals("22 tháng 3 Âm", LunarText.full(LunarDate(22, 3, 2027, false)))
        assertEquals("6/6n Âm", LunarText.chip(LunarDate(6, 6, 2025, true)))
        val gioTo = Holidays.ofYear(2027, overrides).first { it.id == HolidayId.GIO_TO }
        assertEquals("Thứ Sáu 16/4/2027", LunarText.longDate(gioTo.main))
        assertEquals("Thứ Sáu 16/4/2027 (10/3 Âm). Còn 4 ngày. Nghỉ 16/4 đến 18/4 (3 ngày).", LunarText.holidayLine(gioTo, 4))
        assertTrue(LunarText.holidayLine(gioTo, 1).contains("Nghỉ từ ngày mai."))
        assertTrue(LunarText.holidayLine(gioTo, -1).contains("Nghỉ đến hết 18/4."))
    }

    @Test
    fun deleteBodyWarnsAboutRepeats() {
        assertEquals("Giỗ ông nội", LunarText.deleteBody("Giỗ ông nội", null))
        assertEquals("Giỗ ông nội\nViệc này lặp lại (hằng năm theo âm). Xoá sẽ bỏ cả các lần sau.", LunarText.deleteBody("Giỗ ông nội", Recurrence.LUNAR_YEARLY))
    }

    @Test
    fun newestPrefersTheLaterScheduleFile() {
        val bundled = Holidays.parseData(Holidays.bundledJson())
        val later = """{"updated":"2999-01-01","periods":[{"id":"gio-to","year":2027,"from":"2027-04-15","to":"2027-04-18"}]}"""
        assertEquals("2999-01-01", Holidays.newest(later).updated)
        assertEquals(bundled, Holidays.newest("""{"updated":"2000-01-01","periods":[{"id":"gio-to","year":2027,"from":"2027-04-15","to":"2027-04-18"}]}"""))
        assertEquals(bundled, Holidays.newest("not json"))
        assertEquals(bundled, Holidays.newest(null))
    }

    @Test
    fun dayExtrasHonoursTheSettings() {
        val d = LocalDate.of(2027, 4, 16)
        assertEquals(" · 10/3 Âm · Giỗ Tổ Hùng Vương", LunarText.dayExtras(d, LunarPrefs(), overrides))
        assertEquals("", LunarText.dayExtras(d, LunarPrefs(show = false, holidays = false), overrides))
        assertEquals(" · 15/3 Âm · Rằm", LunarText.dayExtras(LocalDate.of(2027, 4, 21), LunarPrefs(monthly = true), overrides))
    }

    @Test
    fun holidayNoticeComesOnTheMorningOfTheNoticeDay() {
        val plan = LunarNotices.plan(at("2027-04-01T10:00"), LunarPrefs(), overrides)
        val n = plan.first { it.key == "holiday:gio-to:2027" }
        assertEquals(at("2027-04-11T08:00"), n.at) // 5 days before Friday 16/4
        assertEquals("Sắp nghỉ · Giỗ Tổ Hùng Vương", n.title)
        assertEquals("Thứ Sáu 16/4/2027 (10/3 Âm). Còn 5 ngày. Nghỉ 16/4 đến 18/4 (3 ngày).", n.body)
    }

    @Test
    fun tetGivesTwoWeeksAndHiddenOrDisabledGiveNothing() {
        val tet = LunarNotices.plan(at("2027-01-01T10:00"), LunarPrefs(lead = 3), overrides).first { it.key == "holiday:tet-nguyen-dan:2027" }
        assertEquals(at("2027-01-21T08:00"), tet.at)
        assertTrue(LunarNotices.plan(at("2027-01-01T10:00"), LunarPrefs(dismissed = setOf("tet-nguyen-dan:2027")), overrides).none { it.key == "holiday:tet-nguyen-dan:2027" })
        assertTrue(LunarNotices.plan(at("2027-01-01T10:00"), LunarPrefs(banner = false), overrides).isEmpty())
        assertTrue(LunarNotices.plan(at("2027-01-01T10:00"), LunarPrefs(holidays = false), overrides).isEmpty())
    }

    @Test
    fun noticeInThePastIsNotPlanned() {
        // 11/4/2027 08:00 has already passed at 09:00 that day.
        assertTrue(LunarNotices.plan(at("2027-04-11T09:00"), LunarPrefs(), overrides).none { it.key == "holiday:gio-to:2027" })
    }

    @Test
    fun monthlyNoticeIsTheEveningBeforeMung1AndRam() {
        val plan = LunarNotices.plan(at("2027-04-01T10:00"), LunarPrefs(monthly = true, holidays = false), overrides)
        val ram = plan.first { it.key == "monthly:2027-04-21" }
        assertEquals(at("2027-04-20T18:00"), ram.at)
        assertEquals("Mai là Rằm tháng 3 Âm", ram.title)
        assertEquals("Chuẩn bị đồ cúng.", ram.body)
        assertEquals("Mai là Mùng 1 tháng 3 Âm", plan.first { it.key == "monthly:2027-04-07" }.title)
        assertTrue(LunarNotices.plan(at("2027-04-01T10:00"), LunarPrefs(), overrides).none { it.key.startsWith("monthly") })
    }

    @Test
    fun planIsSortedAndCapped() {
        val plan = LunarNotices.plan(at("2027-01-01T00:00"), LunarPrefs(monthly = true), overrides)
        assertTrue(plan.size <= LunarNotices.MAX)
        assertEquals(plan.sortedBy { it.at }, plan)
    }
}
