package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UpcomingTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val today = LocalDate.of(2026, 10, 10)
    private fun at(day: Int, h: Int = 9, min: Int = 0) = LocalDate.of(2026, 10, day).atTime(h, min).atZone(zone).toInstant().toEpochMilli()
    private fun item(title: String, whenAt: Long, rep: Recurrence? = null) =
        Item(id = title, type = ItemType.TASK, status = ItemStatus.OPEN, title = title, whenAt = whenAt, recurrence = rep)
    private fun split(vararg items: Item) = UpcomingSplit.of(items.toList(), today, zone)
    private fun UpcomingSplit.titles() = Triple(soon.map { it.title }, later.map { it.title }, recurring.map { it.title })

    @Test
    fun withinThreeDaysStaysInSoonWhetherOneOffOrRepeating() {
        val s = split(item("d1", at(11)), item("d3-late", at(13, 23, 30)), item("d3-rep", at(13), Recurrence.WEEKLY))
        assertEquals(Triple(listOf("d1", "d3-late", "d3-rep"), emptyList<String>(), emptyList<String>()), s.titles())
    }

    @Test
    fun furtherOutFoldsAndEachItemLandsInExactlyOneGroup() {
        val s = split(item("d4", at(14, 0, 5)), item("d4-rep", at(14), Recurrence.WEEKLY), item("monthly", at(30), Recurrence.MONTHLY), item("far", at(28)))
        assertEquals(Triple(emptyList<String>(), listOf("d4", "far"), listOf("d4-rep", "monthly")), s.titles())
        assertEquals(4, s.soon.size + s.later.size + s.recurring.size)
    }
}
