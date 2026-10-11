package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TriageTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = ms(2026, 10, 14, 10) // Wednesday 10:00

    private fun task(id: String, at: Long?, allDay: Boolean = false, type: ItemType = ItemType.TASK, status: ItemStatus = ItemStatus.OPEN, created: Long = 0) =
        Item(id, type, status, id, whenAt = at, allDay = allDay, createdAt = created)

    @Test
    fun onlyOpenOverdueTasksAreListedOldestFirst() {
        val items = listOf(
            task("late-today", ms(2026, 10, 14, 8)),
            task("old", ms(2026, 10, 10, 9)),
            task("all-day-yesterday", ms(2026, 10, 13), allDay = true),
            task("all-day-today", ms(2026, 10, 14), allDay = true),
            task("later-today", ms(2026, 10, 14, 15)),
            task("no-date", null),
            task("done", ms(2026, 10, 1), status = ItemStatus.DONE),
            task("meeting", ms(2026, 10, 12, 9), type = ItemType.EVENT),
        )
        assertEquals(listOf("old", "all-day-yesterday", "late-today"), Triage.overdueTasks(items, now, zone).map { it.id })
    }

    @Test
    fun tomorrowKeepsTheTimeOfDay() {
        val moved = Triage.apply(task("t", ms(2026, 10, 10, 9, 30)), Triage.Move.TOMORROW, now, zone)
        assertEquals(ms(2026, 10, 15, 9, 30), moved.whenAt)
        assertFalse(moved.allDay)
    }

    @Test
    fun todayKeepsATimeStillAheadButNeverLeavesTheTaskOverdue() {
        val ahead = Triage.apply(task("t", ms(2026, 10, 10, 16)), Triage.Move.TODAY, now, zone)
        assertEquals(ms(2026, 10, 14, 16), ahead.whenAt)
        val passed = Triage.apply(task("t", ms(2026, 10, 10, 9)), Triage.Move.TODAY, now, zone)
        assertEquals(ms(2026, 10, 14), passed.whenAt)
        assertTrue(passed.allDay)
        assertTrue("moved to today must not be overdue again", Triage.overdueTasks(listOf(passed), now, zone).isEmpty())
    }

    @Test
    fun nextWeekIsTheComingMondayEvenOnAMonday() {
        assertEquals(ms(2026, 10, 19, 9), Triage.apply(task("t", ms(2026, 10, 10, 9)), Triage.Move.NEXT_WEEK, now, zone).whenAt)
        val monday = ms(2026, 10, 19, 10)
        assertEquals(ms(2026, 10, 26), Triage.apply(task("t", ms(2026, 10, 12), allDay = true), Triage.Move.NEXT_WEEK, monday, zone).whenAt)
    }

    @Test
    fun somedayDropsTheDateAndKeepsTheTaskOpen() {
        val moved = Triage.apply(task("t", ms(2026, 10, 10, 9)), Triage.Move.SOMEDAY, now, zone)
        assertNull(moved.whenAt)
        assertFalse(moved.allDay)
        assertEquals(ItemStatus.OPEN, moved.status)
    }

    @Test
    fun allDayTaskStaysAllDay() {
        val moved = Triage.apply(task("t", ms(2026, 10, 12), allDay = true), Triage.Move.TOMORROW, now, zone)
        assertEquals(ms(2026, 10, 15), moved.whenAt)
        assertTrue(moved.allDay)
    }
}
