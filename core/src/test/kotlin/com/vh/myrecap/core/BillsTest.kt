package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BillsTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun bill(id: String = "b", amount: Long? = 850_000, at: Long = ms(2026, 10, 15), status: ItemStatus = ItemStatus.OPEN) =
        Item(id, ItemType.TASK, status, "Đóng tiền điện", whenAt = at, allDay = true, amount = amount, recurrence = Recurrence.MONTHLY)

    @Test
    fun payingABillRecordsAnExpenseOfTheSameAmount() {
        val now = ms(2026, 10, 14, 20)
        val pay = Bills.payment(bill(), now)!!
        assertEquals(ItemType.EXPENSE, pay.type)
        assertEquals(850_000L, pay.amount)
        assertEquals(Bills.CATEGORY, pay.category)
        assertEquals("paid today, not on the due date", now, pay.whenAt)
        assertEquals("pay-b", pay.id)
    }

    @Test
    fun aTaskWithoutAmountIsNotABill() {
        assertNull(Bills.payment(bill(amount = null), 0))
        assertNull(Bills.payment(bill(amount = 0), 0))
        assertNull(Bills.payment(bill().copy(type = ItemType.EVENT), 0))
    }

    @Test
    fun aKnownCategoryIsKeptAnUnknownOneBecomesHoaDon() {
        assertEquals("Giáo dục", Bills.payment(bill().copy(category = "Giáo dục"), 0)!!.category)
        assertEquals(Bills.CATEGORY, Bills.payment(bill().copy(category = "lạ"), 0)!!.category)
    }

    @Test
    fun upcomingListsOpenBillsInTheWindowSoonestFirstWithOverdue() {
        val now = ms(2026, 10, 12, 9)
        val items = listOf(
            bill("late", 100_000, ms(2026, 10, 10)),
            bill("soon", 200_000, ms(2026, 10, 20)),
            bill("far", 300_000, ms(2026, 12, 1)),
            bill("paid", 400_000, ms(2026, 10, 13), ItemStatus.DONE),
            bill("edge", 50_000, ms(2026, 11, 11, 23)),
        )
        val up = Bills.upcoming(items, now, zone)
        assertEquals(listOf("late", "soon", "edge"), up.bills.map { it.id })
        assertEquals(350_000L, up.total)
    }

    @Test
    fun advanceReminderFiresDaysBeforeAtEight() {
        val now = ms(2026, 10, 1, 9)
        val item = bill().copy(remindDaysBefore = 3)
        assertEquals(ms(2026, 10, 12, 8), ReminderPolicy.advanceAt(item, zone, now))
    }

    @Test
    fun advanceReminderMovesToTheNextOccurrenceOnceItsTimeHasPassed() {
        val now = ms(2026, 10, 13, 9) // after 12/10 08:00, the October advance is gone
        val item = bill().copy(remindDaysBefore = 3)
        assertEquals(ms(2026, 11, 12, 8), ReminderPolicy.advanceAt(item, zone, now))
    }

    @Test
    fun yearlyDocumentRenewalRemindsAMonthAhead() {
        val renewal = Item("p", ItemType.TASK, ItemStatus.OPEN, "Gia hạn bảo hiểm xe", whenAt = ms(2027, 3, 5), allDay = true,
            recurrence = Recurrence.YEARLY, remindDaysBefore = 30)
        assertEquals(ms(2027, 2, 3, 8), ReminderPolicy.advanceAt(renewal, zone, ms(2026, 10, 12)))
        assertEquals(ms(2028, 3, 5), Schedule.next(ms(2027, 3, 5), Recurrence.YEARLY, zone))
    }

    @Test
    fun noAdvanceReminderWhenNoneSetDoneOrNotBeforeTheNormalOne() {
        val now = ms(2026, 10, 1)
        assertNull(ReminderPolicy.advanceAt(bill(), zone, now))
        assertNull(ReminderPolicy.advanceAt(bill(status = ItemStatus.DONE).copy(remindDaysBefore = 3), zone, now))
        val oneOff = bill().copy(recurrence = null, remindDaysBefore = 3)
        assertNull("already past for a one-off item", ReminderPolicy.advanceAt(oneOff, zone, ms(2026, 10, 14)))
    }

    @Test
    fun remindDaysBeforeSurvivesJson() {
        val item = bill().copy(remindDaysBefore = 7, recurrence = Recurrence.YEARLY)
        assertEquals(item, Item.fromJson(item.toJson()))
    }
}
