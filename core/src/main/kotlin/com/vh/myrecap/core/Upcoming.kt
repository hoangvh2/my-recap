package com.vh.myrecap.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Splits the dated, not-yet-due items of the dashboard. "Sắp tới" holds only what is at most [DAYS] calendar days
 * away; one-off items further out fold into [later] ("Sau này") and repeating ones into [recurring] ("Định kỳ").
 * A repeating item is one open record whose `whenAt` is its next occurrence, so that decides the group, and every
 * item lands in exactly one of the three (never counted twice).
 */
data class UpcomingSplit(val soon: List<Item>, val later: List<Item>, val recurring: List<Item>) {
    companion object {
        const val DAYS = 3L

        /** [items] must already be dated and after [today]; they keep their order inside each group. */
        fun of(items: List<Item>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): UpcomingSplit {
            val limit = today.plusDays(DAYS)
            val (near, far) = items.partition { it.whenAt == null || !Instant.ofEpochMilli(it.whenAt!!).atZone(zone).toLocalDate().isAfter(limit) }
            val (rep, once) = far.partition { it.recurrence != null }
            return UpcomingSplit(near, once, rep)
        }
    }
}
