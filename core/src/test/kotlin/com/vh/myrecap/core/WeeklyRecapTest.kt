package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WeeklyRecapTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 10) = LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private var n = 0

    // Week of Monday 2026-10-12 .. Sunday 10-18; "now" is Friday 10-16.
    private val week = LocalDate.of(2026, 10, 12)
    private val now = ms(2026, 10, 16)

    private fun weeksBefore(w: Long, plusDays: Long = 0): LocalDate = week.minusWeeks(w).plusDays(plusDays)
    private fun done(date: LocalDate) = done(date.year, date.monthValue, date.dayOfMonth)
    private fun spend(a: Long, date: LocalDate) = spend(a, date.year, date.monthValue, date.dayOfMonth)
    private fun done(y: Int, mo: Int, d: Int) = Item("d${n++}", ItemType.TASK, ItemStatus.DONE, "xong", doneAt = ms(y, mo, d), whenAt = ms(y, mo, d))
    private fun open(type: ItemType, y: Int, mo: Int, d: Int) = Item("o${n++}", type, ItemStatus.OPEN, "mở", whenAt = ms(y, mo, d), allDay = true)
    private fun spend(a: Long, y: Int, mo: Int, d: Int, cat: String = "Ăn uống") = Item("s${n++}", ItemType.EXPENSE, ItemStatus.OPEN, "chi", whenAt = ms(y, mo, d), allDay = true, amount = a, category = cat)

    @Test
    fun weekStartIsMondayEvenFromSunday() {
        assertEquals(week, WeeklyRecap.weekStartOf(LocalDate.of(2026, 10, 18)))
        assertEquals(week, WeeklyRecap.weekStartOf(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun countsDoneTasksPerDayAndAgainstLastWeek() {
        val items = listOf(done(2026, 10, 12), done(2026, 10, 12), done(2026, 10, 15), done(2026, 10, 7), done(2026, 10, 19))
        val r = WeeklyRecap.build(items, emptyList(), week, now, zone)
        assertEquals(listOf(2, 0, 0, 1, 0, 0, 0), r.doneByDay)
        assertEquals(3, r.done)
        assertEquals("only the 7th is in the previous week", 1, r.previousDone)
    }

    @Test
    fun spendingTotalsTopCategoryAndPreviousWeek() {
        val items = listOf(spend(100_000, 2026, 10, 12), spend(300_000, 2026, 10, 14, "Mua sắm"), spend(50_000, 2026, 10, 8))
        val r = WeeklyRecap.build(items, emptyList(), week, now, zone)
        assertEquals(400_000, r.spent)
        assertEquals(50_000, r.previousSpent)
        assertEquals("Mua sắm" to 300_000L, r.topCategory)
    }

    @Test
    fun recordingsInTheWeekOnly() {
        val r = WeeklyRecap.build(emptyList(), listOf(ms(2026, 10, 13), ms(2026, 10, 18, 23), ms(2026, 10, 19, 1), ms(2026, 10, 11)), week, now, zone)
        assertEquals(2, r.recordings)
    }

    @Test
    fun stuckTasksAreFlaggedOnlyWhenOlderThanAWeek() {
        val old = Item("a", ItemType.TASK, ItemStatus.OPEN, "cũ", whenAt = ms(2026, 10, 5), allDay = true)
        val recent = Item("b", ItemType.TASK, ItemStatus.OPEN, "mới", whenAt = ms(2026, 10, 14), allDay = true)
        val r = WeeklyRecap.build(listOf(old, recent), emptyList(), week, now, zone)
        assertEquals(2, r.overdue)
        assertEquals(1, r.stale)
        assertTrue(r.insights.any { it.kind == WeeklyRecap.Kind.STUCK })
    }

    @Test
    fun lowOutputNeedsHistoryAndALateEnoughDay() {
        val history = (1L..4L).flatMap { w -> (1L..4L).map { done(weeksBefore(w, it % 3)) } }
        val r = WeeklyRecap.build(history + done(2026, 10, 13), emptyList(), week, now, zone)
        assertTrue(r.insights.any { it.kind == WeeklyRecap.Kind.LOW_OUTPUT })
        val tooEarly = WeeklyRecap.build(history, emptyList(), week, ms(2026, 10, 13), zone)
        assertTrue(tooEarly.insights.none { it.kind == WeeklyRecap.Kind.LOW_OUTPUT })
        val noHistory = WeeklyRecap.build(listOf(done(2026, 10, 13)), emptyList(), week, now, zone)
        assertNull(noHistory.averageDone)
        assertTrue(noHistory.insights.none { it.kind == WeeklyRecap.Kind.LOW_OUTPUT })
    }

    @Test
    fun spendSpikeNeedsThreeWeeksOfHistory() {
        val history = (1L..3L).map { spend(500_000, weeksBefore(it)) }
        val r = WeeklyRecap.build(history + spend(1_500_000, 2026, 10, 13), emptyList(), week, now, zone)
        assertTrue(r.insights.any { it.kind == WeeklyRecap.Kind.SPEND_UP })
        val fewer = WeeklyRecap.build(history.take(2) + spend(1_500_000, 2026, 10, 13), emptyList(), week, now, zone)
        assertTrue(fewer.insights.none { it.kind == WeeklyRecap.Kind.SPEND_UP })
    }

    @Test
    fun busiestDayNextWeekIsFlagged() {
        val items = (1..6).map { open(ItemType.TASK, 2026, 10, 20) } + open(ItemType.EVENT, 2026, 10, 22)
        val r = WeeklyRecap.build(items, emptyList(), week, now, zone)
        assertEquals(listOf(0, 6, 0, 1, 0, 0, 0), r.nextWeekByDay)
        assertEquals(7, r.nextWeekTotal)
        assertTrue(r.insights.single { it.kind == WeeklyRecap.Kind.BUSY_DAY }.message.startsWith("Thứ Ba"))
    }

    @Test
    fun quietWeekRaisesNothing() {
        val r = WeeklyRecap.build(listOf(done(2026, 10, 13), open(ItemType.TASK, 2026, 10, 21)), emptyList(), week, now, zone)
        assertTrue(r.insights.isEmpty())
    }
}
