package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimelineTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 10, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun item(title: String, type: ItemType = ItemType.TASK, whenAt: Long? = null, allDay: Boolean = false, createdAt: Long = 0) =
        Item(id = title, type = type, status = ItemStatus.OPEN, title = title, whenAt = whenAt, allDay = allDay, createdAt = createdAt)

    @Test
    fun allDayFirstThenByTimeWithNowLineBeforeFirstEntryAhead() {
        val day = item("day", whenAt = at(0), allDay = true)
        val nine = item("nine", ItemType.EVENT, at(9))
        val fifteen = item("fifteen", whenAt = at(15))
        val sixteen = item("sixteen", ItemType.EVENT, at(16))
        val t = Timeline.build(listOf(sixteen, day, fifteen, nine), at(14), zone)
        assertEquals(
            listOf(Triple("Cả ngày", "day", false), Triple("09:00", "nine", true), Triple("15:00", "fifteen", false), Triple("16:00", "sixteen", false)),
            t.rows.map { Triple(it.time, it.item.title, it.past) },
        )
        assertEquals(2, t.nowAt)
    }

    @Test
    fun appointmentIsOverOneHourAfterStartAndTasksAreNeverDimmed() {
        val t = Timeline.build(listOf(item("ev", ItemType.EVENT, at(13)), item("task", whenAt = at(8))), at(14), zone)
        assertEquals(mapOf("task" to false, "ev" to true), t.rows.associate { it.item.title to it.past })
    }

    @Test
    fun nowLineGoesLastWhenEverythingIsBehindAndNeverBeforeAllDay() {
        val past = Timeline.build(listOf(item("a", whenAt = at(8)), item("b", whenAt = at(9))), at(20), zone)
        assertEquals(2, past.nowAt)
        val onlyAllDay = Timeline.build(listOf(item("d", allDay = true, whenAt = at(0))), at(10), zone)
        assertEquals(1, onlyAllDay.nowAt)
        assertEquals(0, Timeline.build(emptyList(), at(10), zone).rows.size)
    }

    @Test
    fun undatedAndTiesKeepCreationOrder() {
        val t = Timeline.build(listOf(item("late", createdAt = 2), item("early", createdAt = 1), item("x", whenAt = at(9), createdAt = 5)), at(8), zone)
        assertEquals(listOf("early", "late", "x"), t.rows.map { it.item.title })
        assertEquals(2, t.nowAt)
    }
}
