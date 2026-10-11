package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class SpendingStatsTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, mo, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private var n = 0
    private fun spend(amount: Long, y: Int, mo: Int, d: Int, cat: String? = "Ăn uống", title: String = "Chi ${n}", status: ItemStatus = ItemStatus.OPEN) =
        Item("e${n++}", ItemType.EXPENSE, status, title, whenAt = ms(y, mo, d), allDay = true, amount = amount, category = cat)

    private val oct = YearMonth.of(2026, 10)
    private val now = ms(2026, 10, 10) // day 10 of 31

    @Test
    fun totalsCumulativeAndForecastFollowTheCurrentPace() {
        val items = listOf(spend(100_000, 2026, 10, 2), spend(200_000, 2026, 10, 10), spend(999, 2026, 10, 20))
        val r = SpendingStats.report(items.dropLast(1), oct, now, zone)
        assertEquals(300_000, r.total)
        assertEquals(10, r.cumulative.size)
        assertEquals(100_000, r.cumulative[1])
        assertEquals(300_000, r.cumulative.last())
        assertEquals("300k over 10 days → 930k over 31", 930_000L, r.forecast)
    }

    @Test
    fun noForecastTooEarlyOrForAFinishedMonth() {
        assertNull(SpendingStats.report(listOf(spend(100_000, 2026, 10, 2)), oct, ms(2026, 10, 3), zone).forecast)
        assertNull(SpendingStats.report(listOf(spend(100_000, 2026, 9, 2)), YearMonth.of(2026, 9), now, zone).forecast)
    }

    @Test
    fun draftsAndInvalidAmountsAreIgnored() {
        val items = listOf(spend(100_000, 2026, 10, 2, status = ItemStatus.DRAFT), spend(0, 2026, 10, 3), spend(50_000, 2026, 10, 4))
        assertEquals(50_000, SpendingStats.report(items, oct, now, zone).total)
    }

    @Test
    fun comparesWithLastMonthUpToTheSameDay() {
        val items = listOf(spend(100_000, 2026, 9, 5), spend(400_000, 2026, 9, 25), spend(50_000, 2026, 10, 5))
        val r = SpendingStats.report(items, oct, now, zone)
        assertEquals(100_000L, r.previousToDate)
        assertEquals(30, r.previousCumulative.size)
    }

    @Test
    fun categoriesAreTheTopFiveThenOther() {
        val cats = listOf("A", "B", "C", "D", "E", "F", "G")
        val items = cats.mapIndexed { i, c -> spend((100 - i * 10) * 1000L, 2026, 10, 2, cat = c) } + spend(5_000, 2026, 10, 3, cat = null)
        val r = SpendingStats.report(items, oct, now, zone)
        assertEquals(listOf("A", "B", "C", "D", "E", SpendingStats.OTHER), r.categories.map { it.name })
        assertEquals((50 + 40 + 5) * 1000L, r.categories.last().amount)
        assertEquals(1.0, r.categories.sumOf { it.share }, 1e-9)
    }

    @Test
    fun trendHasSixMonthsOldestFirst() {
        val r = SpendingStats.report(listOf(spend(100_000, 2026, 5, 2), spend(10_000, 2026, 10, 2)), oct, now, zone)
        assertEquals(listOf(5, 6, 7, 8, 9, 10), r.months.map { it.month.monthValue })
        assertEquals(100_000, r.months.first().total)
        assertEquals(10_000, r.months.last().total)
    }

    @Test
    fun flagsAHugeExpenseAgainstTheUsualOnes() {
        val usual = (1..10).map { spend(50_000L + it * 1000, 2026, 9, it, cat = "Ăn uống") }
        val r = SpendingStats.report(usual + spend(2_000_000, 2026, 10, 4, cat = "Mua sắm", title = "Tai nghe"), oct, now, zone)
        val alert = r.alerts.single { it.kind == SpendingStats.AlertKind.LARGE_EXPENSE }
        assertTrue(alert.message.contains("Tai nghe"))
        assertNotNull(alert.itemId)
    }

    @Test
    fun noLargeExpenseAlertWithoutEnoughHistory() {
        val few = (1..3).map { spend(50_000, 2026, 9, it) }
        val r = SpendingStats.report(few + spend(2_000_000, 2026, 10, 4), oct, now, zone)
        assertTrue(r.alerts.none { it.kind == SpendingStats.AlertKind.LARGE_EXPENSE })
    }

    @Test
    fun flagsACategoryFarAboveItsOwnAverage() {
        val history = listOf(7, 8, 9).flatMap { m -> listOf(spend(1_000_000, 2026, m, 3, cat = "Ăn uống")) }
        val r = SpendingStats.report(history + spend(2_000_000, 2026, 10, 3, cat = "Ăn uống"), oct, now, zone)
        val alert = r.alerts.single { it.kind == SpendingStats.AlertKind.CATEGORY_SPIKE }
        assertTrue(alert.message.startsWith("Ăn uống"))
        assertTrue(alert.message.contains("gấp 2 lần"))
    }

    @Test
    fun quietMonthRaisesNoAlerts() {
        val history = listOf(7, 8, 9).map { m -> spend(1_000_000, 2026, m, 3) }
        val r = SpendingStats.report(history + spend(1_000_000, 2026, 10, 3), oct, now, zone)
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun flagsFastPaceAgainstTheSameDayOfPreviousMonths() {
        val history = listOf(8, 9).map { m -> spend(1_000_000, 2026, m, 3, cat = "Khác") }
        val r = SpendingStats.report(history + spend(3_000_000, 2026, 10, 3, cat = "Khác"), oct, now, zone)
        assertTrue(r.alerts.any { it.kind == SpendingStats.AlertKind.PACE })
    }

    @Test
    fun rentEarlyInTheMonthIsNotFlaggedAsPaceOrMultipliedIntoTheForecast() {
        val history = listOf(8, 9).map { m -> spend(5_000_000, 2026, m, 3, cat = "Nhà") } +
            listOf(8, 9).map { m -> spend(1_000_000, 2026, m, 20, cat = "Ăn uống") }
        val r = SpendingStats.report(history + spend(5_000_000, 2026, 10, 3, cat = "Nhà"), oct, now, zone)
        assertEquals("rent paid + the 1tr the other months spent later", 6_000_000L, r.forecast)
        assertTrue(r.alerts.none { it.kind == SpendingStats.AlertKind.PACE })
    }

    @Test
    fun flagsTheSameExpenseEnteredTwiceInADay() {
        val r = SpendingStats.report(listOf(spend(45_000, 2026, 10, 4, title = "Cà phê"), spend(45_000, 2026, 10, 4, title = "ca phe")), oct, now, zone)
        assertEquals(1, r.alerts.count { it.kind == SpendingStats.AlertKind.POSSIBLE_DUPLICATE })
    }

    @Test
    fun compactAmounts() {
        assertEquals("850 nghìn", SpendingStats.compact(850_000))
        assertEquals("1,2 triệu", SpendingStats.compact(1_200_000))
        assertEquals("2 triệu", SpendingStats.compact(2_000_000))
    }
}
