package com.vh.myrecap.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * One week (Monday to Sunday) at a glance: what got done, what was spent, how many recordings were
 * made, what is stuck, and how heavy next week looks. Pure functions so every number is testable.
 */
object WeeklyRecap {
    enum class Kind { STUCK, LOW_OUTPUT, SPEND_UP, BUSY_DAY }
    data class Insight(val kind: Kind, val message: String)

    class Report(
        val weekStart: LocalDate,
        /** Tasks finished on each day, Monday first. */
        val doneByDay: List<Int>,
        val done: Int,
        val previousDone: Int,
        /** Average tasks finished per week over the four weeks before, or null with under two weeks of history. */
        val averageDone: Double?,
        /** Open tasks past due as of [Report] time, and those overdue for more than [STALE_DAYS] days. */
        val overdue: Int,
        val stale: Int,
        val spent: Long,
        val previousSpent: Long,
        val topCategory: Pair<String, Long>?,
        val recordings: Int,
        /** Open tasks and appointments due on each day of the following week, Monday first. */
        val nextWeekByDay: List<Int>,
        val insights: List<Insight>,
    ) {
        val nextWeekTotal: Int get() = nextWeekByDay.sum()
    }

    const val STALE_DAYS = 7
    private const val WEEKDAYS = "Thứ Hai,Thứ Ba,Thứ Tư,Thứ Năm,Thứ Sáu,Thứ Bảy,Chủ nhật"

    fun weekStartOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun build(items: List<Item>, recordingTimes: List<Long>, weekStart: LocalDate, now: Long, zone: ZoneId): Report {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun inWeek(ms: Long?, start: LocalDate) = ms != null && date(ms).let { !it.isBefore(start) && it.isBefore(start.plusDays(7)) }
        val previousStart = weekStart.minusWeeks(1)
        val nextStart = weekStart.plusWeeks(1)

        val doneTasks = items.filter { it.type == ItemType.TASK && it.status == ItemStatus.DONE && it.doneAt != null }
        fun doneIn(start: LocalDate) = doneTasks.count { inWeek(it.doneAt, start) }
        val doneByDay = (0L..6L).map { d -> doneTasks.count { it.doneAt != null && date(it.doneAt!!) == weekStart.plusDays(d) } }
        val done = doneByDay.sum()
        val history = (1L..4L).map { doneIn(weekStart.minusWeeks(it)) }
        val weeksWithData = history.count { it > 0 }
        val averageDone = if (weeksWithData >= 2) history.average() else null

        val overdueTasks = items.filter { t ->
            val at = t.whenAt
            t.type == ItemType.TASK && t.status == ItemStatus.OPEN && at != null && if (t.allDay) date(at).isBefore(today) else at < now
        }
        val stale = overdueTasks.count { t -> date(t.whenAt!!).isBefore(today.minusDays(STALE_DAYS.toLong())) }

        val expenses = items.filter { it.type == ItemType.EXPENSE && it.status != ItemStatus.DRAFT && (it.amount ?: 0) > 0 }
        fun spentIn(start: LocalDate) = expenses.filter { inWeek(it.whenAt, start) }.sumOf { it.amount ?: 0 }
        val spent = spentIn(weekStart)
        val previousSpent = spentIn(previousStart)
        val top = expenses.filter { inWeek(it.whenAt, weekStart) }
            .groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: SpendingStats.OTHER }
            .mapValues { (_, l) -> l.sumOf { it.amount ?: 0 } }
            .maxByOrNull { it.value }?.toPair()

        val recordings = recordingTimes.count { inWeek(it, weekStart) }

        val next = items.filter { (it.type == ItemType.TASK || it.type == ItemType.EVENT) && it.status == ItemStatus.OPEN && inWeek(it.whenAt, nextStart) }
        val nextByDay = (0L..6L).map { d -> next.count { date(it.whenAt!!) == nextStart.plusDays(d) } }

        val insights = mutableListOf<Insight>()
        if (stale > 0) {
            insights += Insight(Kind.STUCK, "$stale việc quá hạn hơn $STALE_DAYS ngày: dời sang ngày khác hoặc bỏ hẳn bằng thẻ Rà soát")
        }
        // Judge output only once most of the week has passed.
        val weekIsOver = weekStart.plusDays(3).isBefore(today)
        if (averageDone != null && averageDone >= 3 && weekIsOver && done < averageDone * 0.5) {
            insights += Insight(Kind.LOW_OUTPUT, "Mới xong $done việc, chưa bằng nửa mức trung bình ${trim(averageDone)} việc/tuần của 4 tuần trước")
        }
        val spendHistory = (1L..4L).map { spentIn(weekStart.minusWeeks(it)) }.filter { it > 0 }
        if (spendHistory.size >= 3) {
            val avg = spendHistory.average()
            if (spent > avg * 1.5 && spent - avg >= 300_000) {
                insights += Insight(Kind.SPEND_UP, "Chi ${SpendingStats.compact(spent)} trong tuần, gấp ${trim(spent / avg)} lần mức trung bình ${SpendingStats.compact(avg.toLong())}")
            }
        }
        val busiest = nextByDay.indices.maxByOrNull { nextByDay[it] }
        if (busiest != null && nextByDay[busiest] >= 5 && nextByDay[busiest] >= 2 * nextByDay.average()) {
            insights += Insight(Kind.BUSY_DAY, "${weekdayName(busiest)} tuần sau có ${nextByDay[busiest]} việc/lịch hẹn, dày nhất tuần: cân nhắc dời bớt")
        }
        return Report(weekStart, doneByDay, done, doneIn(previousStart), averageDone, overdueTasks.size, stale, spent, previousSpent, top, recordings, nextByDay, insights)
    }

    fun weekdayName(index: Int): String = WEEKDAYS.split(',')[index]

    private fun trim(x: Double): String {
        val r = Math.round(x * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
    }
}
