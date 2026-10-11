package com.vh.myrecap.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "Hôm nay theo giờ": today's open items in time order with a "now" marker (same rules as the web Today screen). */
object Timeline {
    const val ALL_DAY = "Cả ngày"
    private const val EVENT_LENGTH_MS = 60 * 60_000L
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    data class Row(
        val item: Item,
        /** "09:30", or [ALL_DAY]. */
        val time: String,
        /** An appointment that is already over. */
        val past: Boolean,
    )

    /** [nowAt] is the index the now line goes before: the first timed entry still ahead, or `rows.size`. */
    data class Day(val rows: List<Row>, val nowAt: Int)

    fun build(today: List<Item>, now: Long, zone: ZoneId): Day {
        val order = compareBy<Item>({ it.whenAt ?: Long.MAX_VALUE }, { it.createdAt })
        val (flat, timed) = today.partition { it.allDay || it.whenAt == null }
        val allDay = flat.sortedWith(order)
        val rows = allDay.map { Row(it, ALL_DAY, false) } + timed.sortedWith(order).map {
            val at = it.whenAt!!
            Row(it, Instant.ofEpochMilli(at).atZone(zone).format(HM), it.type == ItemType.EVENT && at + EVENT_LENGTH_MS <= now)
        }
        val ahead = rows.indices.firstOrNull { it >= allDay.size && rows[it].item.whenAt!! >= now } ?: -1
        return Day(rows, if (ahead < 0) rows.size else ahead)
    }
}
