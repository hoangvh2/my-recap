package com.vh.myrecap.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanyHolidaysTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val summer = CompanyHoliday("a", "Nghỉ hè công ty", d("2026-07-20"), d("2026-07-22"), true)
    private val once = CompanyHoliday("b", "Team building", d("2026-11-27"), d("2026-11-27"), false)
    private val yearEnd = CompanyHoliday("c", "Nghỉ cuối năm", d("2026-12-30"), d("2027-01-02"), true)

    @Test
    fun yearlyRepeatsFromTheYearEnteredAndOneOffStays() {
        assertEquals(listOf(d("2028-07-20")), CompanyHolidays.ofYear(listOf(summer, once), 2028).map { it.from })
        assertEquals(listOf("Nghỉ hè công ty", "Team building"), CompanyHolidays.ofYear(listOf(summer, once), 2026).map { it.name })
        assertTrue(CompanyHolidays.ofYear(listOf(summer), 2025).isEmpty())
    }

    @Test
    fun findsTheBreakADateIsInIncludingAcrossNewYear() {
        assertEquals("Nghỉ hè công ty", CompanyHolidays.on(d("2027-07-21"), listOf(summer))?.name)
        assertNull(CompanyHolidays.on(d("2027-07-23"), listOf(summer)))
        assertNotNull(CompanyHolidays.on(d("2028-01-01"), listOf(yearEnd)))
    }

    @Test
    fun leapDayFallsBackTo28() {
        val leap = CompanyHoliday("d", "X", d("2028-02-29"), d("2028-02-29"), true)
        assertEquals(d("2029-02-28"), CompanyHolidays.ofYear(listOf(leap), 2029).single().from)
    }

    @Test
    fun bannerCountsDaysAndHonoursDismissal() {
        assertNull(CompanyHolidays.banner(d("2026-07-10"), 5, emptySet(), listOf(summer)))
        assertEquals(3, CompanyHolidays.banner(d("2026-07-17"), 5, emptySet(), listOf(summer))?.daysUntil)
        assertEquals(-1, CompanyHolidays.banner(d("2026-07-21"), 5, emptySet(), listOf(summer))?.daysUntil)
        assertNull(CompanyHolidays.banner(d("2026-07-17"), 5, setOf("company:a:2026"), listOf(summer)))
    }

    @Test
    fun noticeUsesTheChosenLeadAndMorningHour() {
        val plan = LunarNotices.plan(LocalDateTime.parse("2026-07-01T10:00"), LunarPrefs(lead = 7, company = listOf(summer)), emptyList())
        val n = plan.first { it.key == "company:a:2026" }
        assertEquals(LocalDateTime.parse("2026-07-13T08:00"), n.at)
        assertEquals("Sắp nghỉ · Nghỉ hè công ty", n.title)
        assertEquals("Còn 7 ngày. Nghỉ 20/7 đến 22/7 (3 ngày). Ngày nghỉ của công ty.", n.body)
        assertTrue(LunarNotices.plan(LocalDateTime.parse("2026-07-01T10:00"), LunarPrefs(banner = false, company = listOf(summer)), emptyList()).none { it.key.startsWith("company:") })
    }

    @Test
    fun dayExtrasNamesTheCompanyBreak() {
        assertTrue(LunarText.dayExtras(d("2026-07-21"), LunarPrefs(show = false, holidays = false, company = listOf(summer)), emptyList()).contains("Nghỉ hè công ty"))
    }

    @Test
    fun problemsAndStorage() {
        assertEquals("Nhập tên kỳ nghỉ.", CompanyHolidays.problem(" ", d("2026-07-20"), d("2026-07-20")))
        assertEquals("Ngày kết thúc phải sau ngày bắt đầu.", CompanyHolidays.problem("x", d("2026-07-20"), d("2026-07-19")))
        assertNull(CompanyHolidays.problem("x", d("2026-07-20"), d("2026-07-20")))
        val list = listOf(summer, once, yearEnd)
        assertEquals(list, CompanyHolidays.parse(CompanyHolidays.toJson(list)))
        assertTrue(CompanyHolidays.parse("not json").isEmpty())
        assertEquals(listOf(summer), CompanyHolidays.parse("""[{"id":"a","name":"Nghỉ hè công ty","from":"2026-07-20","to":"2026-07-22","yearly":true},{"id":"a","name":"dup","from":"2026-01-01","to":"2026-01-01"},{"id":"z","name":"bad","from":"x","to":"y"}]"""))
    }
}
