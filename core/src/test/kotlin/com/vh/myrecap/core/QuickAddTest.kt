package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class QuickAddTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Wednesday 2026-10-14 10:00
    private val now = ms(2026, 10, 14, 10)
    private fun parse(text: String, off: Set<QuickAdd.Part> = emptySet()) = QuickAdd.parse(text, now, zone, off)

    @Test
    fun tomorrowWithAfternoonTime() {
        val r = parse("gọi anh Nam mai 3h chiều")
        assertEquals("gọi anh Nam", r.title)
        assertEquals(ms(2026, 10, 15, 15), r.whenAt)
        assertFalse(r.allDay)
        assertEquals(ItemType.TASK, r.type)
    }

    @Test
    fun periodBeforeOrAfterTheHour() {
        assertEquals(ms(2026, 10, 15, 20), parse("uống thuốc mai 8 giờ tối").whenAt)
        assertEquals(ms(2026, 10, 15, 9, 30), parse("mai sáng 9h30 nộp báo cáo").whenAt)
        assertEquals(ms(2026, 10, 15, 13), parse("ăn trưa mai 1h trưa").whenAt)
        assertEquals(ms(2026, 10, 15, 7, 30), parse("dậy mai 7 giờ rưỡi").whenAt)
        assertEquals(ms(2026, 10, 15, 14, 5), parse("mai 14:05 kiểm tra").whenAt)
    }

    @Test
    fun weekdaysAndNextWeek() {
        assertEquals(ms(2026, 10, 15), parse("nộp báo cáo thứ 5").whenAt)
        assertEquals(ms(2026, 10, 22, 9), parse("họp nhóm thứ năm tuần sau 9h").whenAt)
        assertEquals(ms(2026, 10, 18), parse("đi chơi chủ nhật").whenAt)
        assertEquals("a weekday that already passed this week means the next one", ms(2026, 10, 19), parse("báo cáo thứ hai").whenAt)
        assertEquals(ms(2026, 10, 12), parse("việc đó thứ hai tuần này").whenAt)
    }

    @Test
    fun explicitDates() {
        assertEquals(ms(2026, 11, 20), parse("hạn nộp 20/11").whenAt)
        assertEquals("a date already gone this year means next year", ms(2027, 3, 5), parse("sinh nhật 5/3").whenAt)
        assertEquals(ms(2026, 12, 1), parse("ký hợp đồng ngày 1 tháng 12").whenAt)
        assertEquals(ms(2026, 10, 20), parse("trả sách ngày 20").whenAt)
        assertEquals("ngày 5 already passed in October", ms(2026, 11, 5), parse("đóng tiền ngày 5").whenAt)
        assertNull("31/2 is not a date", parse("việc 31/2").whenAt)
    }

    @Test
    fun timeOnlyMeansTodayOrTomorrowIfPassed() {
        assertEquals(ms(2026, 10, 14, 15), parse("gửi mail 3h chiều").whenAt)
        assertEquals(ms(2026, 10, 15, 9), parse("gửi mail 9h sáng").whenAt)
    }

    @Test
    fun appointmentsBecomeEvents() {
        val r = parse("họp với khách mai 9h")
        assertEquals(ItemType.EVENT, r.type)
        assertEquals("họp với khách", r.title)
        assertEquals(ItemType.TASK, parse("họp với khách").type) // no time, nothing to put on a calendar
    }

    @Test
    fun repeatAndImportant() {
        val daily = parse("uống thuốc mỗi ngày 8h tối gấp")
        assertEquals(Recurrence.DAILY, daily.recurrence)
        assertTrue(daily.important)
        assertEquals("uống thuốc", daily.title)
        assertEquals(ms(2026, 10, 14, 20), daily.whenAt)
        val weekly = parse("họp giao ban mỗi thứ 2 9h")
        assertEquals(Recurrence.WEEKLY, weekly.recurrence)
        assertEquals(ms(2026, 10, 19, 9), weekly.whenAt)
        assertEquals(Recurrence.MONTHLY, parse("đóng tiền nhà hằng tháng ngày 5").recurrence)
    }

    @Test
    fun lookAlikeWordsAreNotMisread() {
        assertFalse("gặp is not gấp", parse("gặp anh Nam mai").important)
        assertTrue(parse("nộp hồ sơ gấp").important)
        assertNull("thứ tự is not Wednesday", parse("xếp thứ tự ưu tiên").whenAt)
        assertEquals(ms(2026, 10, 14 + 7), parse("kiểm tra thứ tư").whenAt)
        assertNull("a bare number is not a time", parse("mua 3 cái bánh").whenAt)
        assertNull("2 hộp is not 2 o'clock", parse("mua 2 hộp sữa").whenAt)
    }

    @Test
    fun turningAPartOffKeepsItsWordsInTheTitle() {
        val r = parse("gọi anh Nam mai 3h chiều", off = setOf(QuickAdd.Part.DAY))
        assertEquals("gọi anh Nam mai", r.title)
        assertEquals(ms(2026, 10, 14, 15), r.whenAt) // time alone → today 15:00
    }

    @Test
    fun nothingRecognisedIsAPlainTask() {
        val r = parse("mua sữa cho bé")
        assertEquals("mua sữa cho bé", r.title)
        assertNull(r.whenAt)
        assertTrue(r.spans.isEmpty())
    }

    @Test
    fun onlyDateWordsLeavesTheTextAsTheTitle() {
        val r = parse("mai")
        assertEquals("mai", r.title)
        assertNull(r.whenAt)
    }

    @Test
    fun spansDescribeWhatWasUnderstood() {
        val r = parse("gọi anh Nam mai 3h chiều")
        assertEquals(listOf("Mai", "15:00"), r.spans.map { it.shown })
        assertEquals(listOf(QuickAdd.Part.DAY, QuickAdd.Part.TIME), r.spans.map { it.part })
    }

    @Test
    fun toItemKeepsEverything() {
        val item = parse("họp nhóm mai 9h gấp").toItem("x", now)
        assertEquals(ItemStatus.OPEN, item.status)
        assertEquals(ItemType.EVENT, item.type)
        assertTrue(item.important)
        assertEquals(LocalDate.of(2026, 10, 15), java.time.Instant.ofEpochMilli(item.whenAt!!).atZone(zone).toLocalDate())
    }
}
