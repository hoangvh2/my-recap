package com.vh.myrecap.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Morning review of overdue tasks: one tap moves a task to a new day (or off the calendar) so the list stays clean. */
object Triage {
    enum class Move(val label: String) {
        TODAY("Hôm nay"),
        TOMORROW("Mai"),
        NEXT_WEEK("Tuần sau"),
        SOMEDAY("Để sau"),
    }

    /** Open tasks past their due time or day, oldest first. Appointments are not triaged: a missed one is over. */
    fun overdueTasks(items: List<Item>, now: Long, zone: ZoneId): List<Item> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return items.filter { it.type == ItemType.TASK && it.status == ItemStatus.OPEN && isOverdue(it, now, today, zone) }
            .sortedWith(compareBy({ it.whenAt }, { it.createdAt }))
    }

    private fun isOverdue(item: Item, now: Long, today: LocalDate, zone: ZoneId): Boolean {
        val at = item.whenAt ?: return false
        return if (item.allDay) Instant.ofEpochMilli(at).atZone(zone).toLocalDate().isBefore(today) else at < now
    }

    /**
     * The task after [move]. The time of day is kept; a timed task moved to today whose time has
     * already passed becomes an all-day task today, so it is not overdue again the moment it is moved.
     * [Move.SOMEDAY] removes the due date (the task stays open under "Chưa có hạn").
     */
    fun apply(item: Item, move: Move, now: Long, zone: ZoneId): Item {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        if (move == Move.SOMEDAY) return item.copy(whenAt = null, allDay = false)
        val day = when (move) {
            Move.TODAY -> today
            Move.TOMORROW -> today.plusDays(1)
            else -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        }
        val time = item.whenAt?.takeIf { !item.allDay }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        if (time == null) return item.copy(whenAt = day.atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true)
        val at = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return if (at < now) item.copy(whenAt = day.atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true)
        else item.copy(whenAt = at, allDay = false)
    }
}
