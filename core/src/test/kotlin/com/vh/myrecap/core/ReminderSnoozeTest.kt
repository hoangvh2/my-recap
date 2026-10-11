package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderSnoozeTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun task(important: Boolean, status: ItemStatus = ItemStatus.OPEN) =
        Item("t", ItemType.TASK, status, "Nộp báo cáo", whenAt = ms(2026, 10, 11, 9), important = important)

    @Test
    fun snoozeChoicesLandWhereTheLabelSays() {
        val now = ms(2026, 10, 11, 22, 30)
        assertEquals(now + 600_000, ReminderPolicy.snoozeAt(ReminderPolicy.Snooze.MINUTES_10, now, zone))
        assertEquals(now + 3_600_000, ReminderPolicy.snoozeAt(ReminderPolicy.Snooze.HOUR_1, now, zone))
        assertEquals("tomorrow morning, even when it is late tonight", ms(2026, 10, 12, 8), ReminderPolicy.snoozeAt(ReminderPolicy.Snooze.TOMORROW, now, zone))
    }

    @Test
    fun tomorrowMorningAfterMidnightIsTheNextCalendarDay() {
        val now = ms(2026, 10, 12, 0, 20)
        assertEquals(ms(2026, 10, 13, 8), ReminderPolicy.snoozeAt(ReminderPolicy.Snooze.TOMORROW, now, zone))
    }

    @Test
    fun onlyImportantOpenItemsRepeatAndOnlyUpToTheLimit() {
        val now = ms(2026, 10, 11, 9)
        assertEquals(now + ReminderPolicy.NAG_INTERVAL_MS, ReminderPolicy.nextNagAt(task(true), 0, now))
        assertEquals(now + ReminderPolicy.NAG_INTERVAL_MS, ReminderPolicy.nextNagAt(task(true), ReminderPolicy.MAX_NAGS - 1, now))
        assertNull(ReminderPolicy.nextNagAt(task(true), ReminderPolicy.MAX_NAGS, now))
        assertNull("plain items are reminded once", ReminderPolicy.nextNagAt(task(false), 0, now))
        assertNull("done items stop nagging", ReminderPolicy.nextNagAt(task(true, ItemStatus.DONE), 0, now))
    }
}
