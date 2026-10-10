package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun local(t: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), zone)

    @Test
    fun nextOccurrences() {
        val fri = ms(2026, 10, 9, 9) // Friday
        assertEquals(LocalDateTime.of(2026, 10, 10, 9, 0), local(Schedule.next(fri, Recurrence.DAILY, zone)))
        assertEquals("weekdays skip the weekend", LocalDateTime.of(2026, 10, 12, 9, 0), local(Schedule.next(fri, Recurrence.WEEKDAYS, zone)))
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 0), local(Schedule.next(fri, Recurrence.WEEKLY, zone)))
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), local(Schedule.next(fri, Recurrence.MONTHLY, zone)))
        assertEquals("31st → last day of a short month", LocalDateTime.of(2026, 2, 28, 9, 0), local(Schedule.next(ms(2026, 1, 31, 9), Recurrence.MONTHLY, zone)))
    }

    @Test
    fun localTimeSurvivesDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        val before = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
        val after = Instant.ofEpochMilli(Schedule.next(before, Recurrence.DAILY, ny)).atZone(ny)
        assertEquals(9, after.hour)
        assertEquals(8, after.dayOfMonth)
    }

    @Test
    fun completingARepeatingTaskCreatesTheNextOneStillAhead() {
        val task = Item("t", ItemType.TASK, ItemStatus.DONE, "Uống thuốc", whenAt = ms(2026, 10, 7, 20), recurrence = Recurrence.DAILY, doneAt = 1)
        // Done three days late, at 10:00 on the 10th: next is today 20:00, not the 8th or 9th.
        val next = Schedule.nextInstance(task, "t2", zone, now = ms(2026, 10, 10, 10))!!
        assertEquals("t2", next.id)
        assertEquals(ItemStatus.OPEN, next.status)
        assertNull(next.doneAt)
        assertEquals(LocalDateTime.of(2026, 10, 10, 20, 0), local(next.whenAt!!))
        // Done early (before it was due): the next one is the following period.
        val early = Schedule.nextInstance(task.copy(whenAt = ms(2026, 10, 10, 20)), "t3", zone, now = ms(2026, 10, 10, 8))!!
        assertEquals(LocalDateTime.of(2026, 10, 11, 20, 0), local(early.whenAt!!))
        assertNull("one-off tasks have no next", Schedule.nextInstance(task.copy(recurrence = null), "x", zone, 0))
        assertNull("appointments roll instead", Schedule.nextInstance(task.copy(type = ItemType.EVENT), "x", zone, 0))
    }

    @Test
    fun allDayRepeatingTaskDoneLateComesBackToday() {
        val task = Item("t", ItemType.TASK, ItemStatus.DONE, "Tưới cây", whenAt = ms(2026, 10, 5), allDay = true, recurrence = Recurrence.DAILY)
        val next = Schedule.nextInstance(task, "n", zone, now = ms(2026, 10, 10, 15))!!
        assertEquals(LocalDateTime.of(2026, 10, 11, 0, 0), local(next.whenAt!!))
    }

    @Test
    fun repeatingAppointmentsRollToTheirNextOccurrence() {
        val standup = Item("e", ItemType.EVENT, ItemStatus.OPEN, "Họp team", whenAt = ms(2026, 10, 5, 9), recurrence = Recurrence.WEEKLY)
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), local(Schedule.rollForward(standup, zone, ms(2026, 10, 10, 12))!!.whenAt!!))
        assertNull("still running (within the hour)", Schedule.rollForward(standup, zone, ms(2026, 10, 5, 9, 30)))
        assertNull("one-off", Schedule.rollForward(standup.copy(recurrence = null), zone, ms(2026, 12, 1)))
    }

    @Test
    fun repeatingAppointmentRemindsForTheNextOccurrence() {
        val standup = Item("e", ItemType.EVENT, ItemStatus.OPEN, "Họp team", whenAt = ms(2026, 10, 5, 9), recurrence = Recurrence.WEEKLY)
        assertEquals(ms(2026, 10, 12, 8, 45), ReminderPolicy.triggerAt(standup, zone, now = ms(2026, 10, 10, 12)))
        assertEquals(ms(2026, 10, 5, 8, 45), ReminderPolicy.triggerAt(standup, zone, now = ms(2026, 10, 1)))
    }

    @Test
    fun extractionReadsRepeatAndJsonKeepsIt() {
        val now = ZonedDateTime.of(2026, 10, 10, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        var n = 0
        val items = ItemExtraction.parse(
            """{"items":[{"type":"event","title":"Họp team","date":"2026-10-12","time":"09:00","repeat":"weekly"},
               {"type":"task","title":"Uống thuốc","time":"20:00","repeat":"daily"},
               {"type":"note","title":"Ý tưởng","repeat":"daily"},
               {"type":"task","title":"Không ngày","repeat":"weekly"}]}""",
            zone, now, null,
        ) { "i${n++}" }!!
        assertEquals(Recurrence.WEEKLY, items[0].recurrence)
        assertEquals(Recurrence.DAILY, items[1].recurrence)
        assertNull("notes never repeat", items[2].recurrence)
        assertNull("repeat needs a date", items[3].recurrence)
        assertEquals(items[0], Item.fromJson(items[0].toJson()))
    }
}
