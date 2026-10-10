package com.vh.myrecap.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Occurrences of repeating tasks and appointments, in local time (a 9:00 meeting stays at 9:00 across DST). */
object Schedule {
    private const val MAX_STEPS = 5_000
    private const val EVENT_LENGTH_MS = 60 * 60_000L

    fun next(at: Long, rule: Recurrence, zone: ZoneId): Long {
        val z = Instant.ofEpochMilli(at).atZone(zone)
        val n: ZonedDateTime = when (rule) {
            Recurrence.DAILY -> z.plusDays(1)
            Recurrence.WEEKDAYS -> {
                var d = z.plusDays(1)
                while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                d
            }
            Recurrence.WEEKLY -> z.plusWeeks(1)
            // Day 31 becomes the month's last day (30, 28…) and stays there; acceptable for reminders.
            Recurrence.MONTHLY -> z.plusMonths(1)
        }
        return n.toInstant().toEpochMilli()
    }

    /** First occurrence strictly after [after], stepping from [at]. */
    fun nextAfter(at: Long, rule: Recurrence, zone: ZoneId, after: Long): Long {
        var t = next(at, rule, zone)
        var steps = 0
        while (t <= after && steps++ < MAX_STEPS) t = next(t, rule, zone)
        return t
    }

    /**
     * The task that replaces a completed repeating task: same content, next date that is still
     * ahead (a daily task finished three days late comes back tomorrow, not three times).
     */
    fun nextInstance(done: Item, newId: String, zone: ZoneId, now: Long): Item? {
        val rule = done.recurrence ?: return null
        val at = done.whenAt ?: return null
        if (done.type != ItemType.TASK) return null
        val nextAt = nextAfter(at, rule, zone, maxOf(at, if (done.allDay) startOfDay(now, zone) else now))
        return done.copy(id = newId, status = ItemStatus.OPEN, whenAt = nextAt, doneAt = null, createdAt = now)
    }

    /**
     * A repeating appointment whose occurrence is over moves to its next one (nobody ticks
     * appointments off). Null when nothing changes.
     */
    fun rollForward(item: Item, zone: ZoneId, now: Long): Item? {
        val rule = item.recurrence ?: return null
        val at = item.whenAt ?: return null
        if (item.type != ItemType.EVENT || item.status != ItemStatus.OPEN) return null
        fun end(t: Long) = if (item.allDay) startOfDay(t, zone) + 24 * 3600_000L else t + EVENT_LENGTH_MS
        if (end(at) > now) return null
        var t = at
        var steps = 0
        while (end(t) <= now && steps++ < MAX_STEPS) t = next(t, rule, zone)
        return item.copy(whenAt = t)
    }

    private fun startOfDay(t: Long, zone: ZoneId) =
        Instant.ofEpochMilli(t).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
