package com.vh.myrecap.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * What the owner spent in a month: totals, a pace-based forecast, spending by category, a six-month
 * trend and alerts for things that look off. Pure functions over confirmed expense items, so every
 * number on the dashboard can be unit-tested.
 */
object SpendingStats {
    const val OTHER = "Khác"
    const val MAX_CATEGORIES = 5

    /** A forecast needs this many days of the month behind it; earlier than that it is noise. */
    const val MIN_FORECAST_DAYS = 5

    data class CategoryShare(val name: String, val amount: Long, val share: Double)
    data class MonthTotal(val month: YearMonth, val total: Long)

    enum class AlertKind { PACE, CATEGORY_SPIKE, LARGE_EXPENSE, POSSIBLE_DUPLICATE }
    data class Alert(val kind: AlertKind, val message: String, val itemId: String? = null)

    class Report(
        val month: YearMonth,
        val total: Long,
        val count: Int,
        /** Running total by day of [month], index 0 = day 1, up to today (or the whole month when it is over). */
        val cumulative: List<Long>,
        /** The previous month's running total over its whole length. */
        val previousCumulative: List<Long>,
        /** Last month's total up to the same day of the month, or null when there was no spending then. */
        val previousToDate: Long?,
        /** Month-end total if the current pace holds, or null when it is too early or the month is over. */
        val forecast: Long?,
        val daysInMonth: Int,
        val daysElapsed: Int,
        val categories: List<CategoryShare>,
        /** Up to six months ending at [month], oldest first. */
        val months: List<MonthTotal>,
        val alerts: List<Alert>,
    )

    fun report(expenses: List<Item>, month: YearMonth, now: Long, zone: ZoneId): Report {
        val spent = expenses.filter { it.type == ItemType.EXPENSE && it.status != ItemStatus.DRAFT && (it.amount ?: 0) > 0 && it.whenAt != null }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun dateOf(e: Item) = Instant.ofEpochMilli(e.whenAt!!).atZone(zone).toLocalDate()
        fun monthOf(e: Item) = YearMonth.from(dateOf(e))

        val inMonth = spent.filter { monthOf(it) == month }
        val total = inMonth.sumOf { it.amount ?: 0 }
        val isCurrent = month == YearMonth.from(today)
        val length = month.lengthOfMonth()
        val elapsed = when {
            isCurrent -> today.dayOfMonth
            month.isBefore(YearMonth.from(today)) -> length
            else -> 0
        }
        val cumulative = running(inMonth, elapsed, zone)

        val previous = month.minusMonths(1)
        val previousSpent = spent.filter { monthOf(it) == previous }
        val previousCumulative = running(previousSpent, previous.lengthOfMonth(), zone)
        val previousToDate = previousCumulative.getOrNull(minOf(elapsed, previousCumulative.size) - 1)?.takeIf { it > 0 }

        // Forecast: what is spent so far, plus what the previous months still spent after this day (so a rent paid
        // on the 3rd is not multiplied across the month). With under two months of history, extrapolate the daily pace.
        val history = (1..3).map { back ->
            val m = month.minusMonths(back.toLong())
            running(spent.filter { monthOf(it) == m }, m.lengthOfMonth(), zone)
        }.filter { it.last() > 0 }
        val forecast = if (isCurrent && elapsed in MIN_FORECAST_DAYS until length && total > 0) {
            if (history.size >= 2) {
                val rest = history.map { it.last() - it[minOf(elapsed, it.size) - 1] }.average()
                total + Math.round(rest)
            } else {
                Math.round(total.toDouble() / elapsed * length)
            }
        } else {
            null
        }

        val months = (5 downTo 0).map { back ->
            val m = month.minusMonths(back.toLong())
            MonthTotal(m, spent.filter { monthOf(it) == m }.sumOf { it.amount ?: 0 })
        }

        val categories = categories(inMonth, total)
        val alerts = alerts(spent, inMonth, month, isCurrent, elapsed, history, total, ::dateOf, ::monthOf)
        return Report(month, total, inMonth.size, cumulative, previousCumulative, previousToDate, forecast, length, elapsed, categories, months, alerts)
    }

    private fun running(expenses: List<Item>, days: Int, zone: ZoneId): List<Long> {
        val perDay = LongArray(days + 1)
        for (e in expenses) {
            val day = Instant.ofEpochMilli(e.whenAt!!).atZone(zone).toLocalDate().dayOfMonth
            if (day <= days) perDay[day] += e.amount ?: 0
        }
        var sum = 0L
        return (1..days).map { d -> sum += perDay[d]; sum }
    }

    /** Largest [MAX_CATEGORIES] categories, everything else folded into [OTHER]. */
    private fun categories(inMonth: List<Item>, total: Long): List<CategoryShare> {
        if (total <= 0) return emptyList()
        val byName = inMonth.groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: OTHER }
            .mapValues { (_, list) -> list.sumOf { it.amount ?: 0 } }
        val named = byName.filterKeys { it != OTHER }.entries.sortedByDescending { it.value }
        val top = named.take(MAX_CATEGORIES)
        val other = (byName[OTHER] ?: 0) + named.drop(MAX_CATEGORIES).sumOf { it.value }
        return (top.map { it.key to it.value } + if (other > 0) listOf(OTHER to other) else emptyList())
            .map { (name, amount) -> CategoryShare(name, amount, amount.toDouble() / total) }
    }

    private fun alerts(
        spent: List<Item>,
        inMonth: List<Item>,
        month: YearMonth,
        isCurrent: Boolean,
        elapsed: Int,
        history: List<List<Long>>,
        total: Long,
        dateOf: (Item) -> LocalDate,
        monthOf: (Item) -> YearMonth,
    ): List<Alert> {
        val out = mutableListOf<Alert>()

        // 1. Pace: already spent clearly more by this day than the previous months had (current month only).
        if (isCurrent && elapsed >= MIN_FORECAST_DAYS && history.size >= 2) {
            val avg = history.map { it[minOf(elapsed, it.size) - 1] }.average()
            if (avg > 0 && total > avg * PACE_RATIO && total - avg >= MIN_PACE_EXCESS) {
                out += Alert(AlertKind.PACE, "Đang chi nhanh: ${vnd(total)} sau $elapsed ngày, so với trung bình ${vnd(avg.toLong())} cùng kỳ ${history.size} tháng trước")
            }
        }

        // 2. A category well above its own usual level.
        val prior = (1..3).map { back -> spent.filter { monthOf(it) == month.minusMonths(back.toLong()) } }
        val names = inMonth.mapNotNull { it.category }.toSet()
        for (name in names) {
            val perMonth = prior.map { list -> list.filter { it.category == name }.sumOf { it.amount ?: 0 } }.filter { it > 0 }
            if (perMonth.size < 2) continue
            val avg = perMonth.average()
            val now = inMonth.filter { it.category == name }.sumOf { it.amount ?: 0 }
            if (now > avg * SPIKE_RATIO && now - avg >= MIN_SPIKE_EXCESS) {
                out += Alert(AlertKind.CATEGORY_SPIKE, "$name: ${vnd(now)}, gấp ${ratio(now / avg)} lần mức trung bình ${vnd(avg.toLong())}")
            }
        }

        // 3. A single expense far above what the owner usually pays (median + 4 MAD of the 90 days before the month).
        val start = month.atDay(1)
        val baseline = spent.filter { val d = dateOf(it); d.isBefore(start) && !d.isBefore(start.minusDays(90)) }.map { it.amount!! }
        if (baseline.size >= MIN_BASELINE) {
            val median = median(baseline)
            val mad = median(baseline.map { Math.abs(it - median) })
            val limit = maxOf(median + 4 * mad, median * 2, MIN_LARGE.toDouble())
            for (e in inMonth.filter { it.amount!! > limit }.sortedByDescending { it.amount }.take(2)) {
                out += Alert(AlertKind.LARGE_EXPENSE, "“${e.title}” ${vnd(e.amount!!)} cao hơn nhiều so với mức thường gặp (${vnd(median.toLong())})", e.id)
            }
        }

        // 4. The same thing entered twice the same day.
        inMonth.groupBy { Triple(TextSearch.fold(it.title), it.amount, dateOf(it)) }.values.filter { it.size >= 2 }.take(2).forEach { dup ->
            out += Alert(AlertKind.POSSIBLE_DUPLICATE, "Có thể ghi trùng: “${dup[0].title}” ${vnd(dup[0].amount!!)} xuất hiện ${dup.size} lần trong cùng ngày", dup[0].id)
        }
        return out.sortedBy { it.kind.ordinal }.take(MAX_ALERTS)
    }

    private fun median(values: List<Long>): Double = median(values.map { it.toDouble() })

    @JvmName("medianDouble")
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** "850 nghìn", "1,2 triệu": short enough for a chart label. */
    fun compact(amount: Long): String = when {
        amount >= 1_000_000 -> trim(amount / 1_000_000.0) + " triệu"
        amount >= 1_000 -> trim(amount / 1_000.0) + " nghìn"
        else -> "$amount đ"
    }

    private fun vnd(amount: Long) = compact(amount)

    private fun ratio(x: Double) = trim(x)

    private fun trim(x: Double): String {
        val rounded = Math.round(x * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else String.format(Locale.forLanguageTag("vi"), "%.1f", rounded)
    }

    private const val PACE_RATIO = 1.3
    private const val MIN_PACE_EXCESS = 500_000.0
    private const val SPIKE_RATIO = 1.5
    private const val MIN_SPIKE_EXCESS = 200_000.0
    private const val MIN_BASELINE = 8
    private const val MIN_LARGE = 100_000L
    private const val MAX_ALERTS = 5
}
