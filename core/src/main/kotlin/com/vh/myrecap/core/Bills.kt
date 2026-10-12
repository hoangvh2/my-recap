package com.vh.myrecap.core

import java.time.Instant
import java.time.ZoneId

/**
 * Bills are tasks with an amount ("Đóng tiền điện", 850.000 đ, hằng tháng). Ticking one off records
 * the payment as an expense, so the spending stats see it without typing it twice.
 */
object Bills {
    /** Category given to a payment when the bill has none. */
    const val CATEGORY = "Hoá đơn"

    /** Deterministic, so reopening the bill can take its payment back. */
    fun paymentId(billId: String) = "pay-$billId"

    fun isBill(item: Item) = item.type == ItemType.TASK && (item.amount ?: 0) > 0

    /** The expense recorded when [bill] is completed at [now], or null when it is not a bill. */
    fun payment(bill: Item, now: Long): Item? {
        if (!isBill(bill)) return null
        return Item(
            id = paymentId(bill.id),
            type = ItemType.EXPENSE,
            status = ItemStatus.OPEN,
            title = bill.title,
            amount = bill.amount,
            category = bill.category?.takeIf { it in EXPENSE_CATEGORIES } ?: CATEGORY,
            whenAt = now,
            createdAt = now,
        )
    }

    data class Upcoming(val bills: List<Item>, val total: Long)

    /** Open bills due before [now] + [days] (overdue ones included), soonest first. */
    fun upcoming(items: List<Item>, now: Long, zone: ZoneId, days: Long = 30): Upcoming {
        val until = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(days + 1)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val due = items
            .filter { isBill(it) && it.status == ItemStatus.OPEN && it.whenAt != null && it.whenAt < until }
            .sortedBy { it.whenAt }
        return Upcoming(due, due.sumOf { it.amount ?: 0 })
    }
}
