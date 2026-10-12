package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QuickTemplatesTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(d: Int, h: Int = 12, mi: Int = 0) = LocalDateTime.of(2026, 10, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(14, 10)

    @Test
    fun fillPutsTheCursorOnTheBlank() {
        assertEquals("ăn trưa k" to 8, QuickTemplates.fill("ăn trưa …k"))
        val (text, cursor) = QuickTemplates.fill("gọi ... mai 9h")
        assertEquals("gọi  mai 9h", text)
        assertEquals(4, cursor) // between "gọi " and " mai"
        assertEquals("cà phê 30k " to 11, QuickTemplates.fill("cà phê 30k"))
    }

    @Test
    fun blanksAndDuplicates() {
        assertTrue(QuickTemplates.hasBlank("đổ xăng ...k"))
        assertFalse(QuickTemplates.hasBlank("cà phê 30k"))
        val list = listOf(QuickTemplate("Cà phê 30k"))
        assertEquals(list, QuickTemplates.add(list, "ca phe   30k"))
        assertEquals(2, QuickTemplates.add(list, "phở 50k").size)
        assertEquals(list, QuickTemplates.add(list, "  "))
    }

    @Test
    fun encodeRoundTripsAndMissingMeansDefaults() {
        val list = listOf(QuickTemplate("a …", pinned = true), QuickTemplate("b"))
        assertEquals(list, QuickTemplates.decode(QuickTemplates.encode(list)))
        assertNull(QuickTemplates.decode(null))
        assertEquals(emptyList<QuickTemplate>(), QuickTemplates.decode("not json"))
        assertEquals(listOf("b", "a"), QuickTemplates.ordered(listOf(QuickTemplate("a"), QuickTemplate("b", pinned = true))).map { it.text })
    }

    @Test
    fun shortAmountsParseBack() {
        for (amount in listOf(45_000L, 5_000_000L, 1_250_000L, 1_200_000L, 45_500L, 120_000L)) {
            val line = "x " + QuickTemplates.short(amount)
            assertEquals(line, amount, QuickAdd.parse(line, now, zone).amount)
        }
        assertEquals("1.2tr", QuickTemplates.short(1_200_000))
        assertEquals("45k", QuickTemplates.short(45_000))
    }

    private fun expense(title: String, amount: Long, day: Int) =
        Item(id = "$title$day", type = ItemType.EXPENSE, status = ItemStatus.OPEN, title = title, amount = amount, createdAt = ms(day))

    private fun task(title: String, day: Int, hour: Int) =
        Item(id = "$title$day", type = ItemType.TASK, status = ItemStatus.OPEN, title = title, whenAt = ms(day, hour), createdAt = ms(day))

    @Test
    fun suggestsRepeatedEntries() {
        val history = listOf(
            expense("cà phê", 45_000, 1), expense("Cà phê", 45_000, 2), expense("cà phê", 50_000, 3),
            expense("đổ xăng", 80_000, 4), expense("đổ xăng", 100_000, 5), expense("đổ xăng", 120_000, 6),
            task("họp team", 7, 9), task("họp team", 8, 9), task("họp team", 9, 9),
            expense("phở", 50_000, 1), expense("phở", 50_000, 2), // only twice
        )
        val out = QuickTemplates.suggest(history, emptyList(), now, zone)
        assertEquals(setOf("cà phê 45k", "đổ xăng …k", "họp team 9h"), out.toSet())
    }

    @Test
    fun suggestionsSkipSavedOldAndDrafts() {
        val history = listOf(expense("cà phê", 45_000, 1), expense("cà phê", 45_000, 2), expense("cà phê", 45_000, 3))
        assertTrue(QuickTemplates.suggest(history, listOf(QuickTemplate("cà phê …k")), now, zone).isEmpty())
        assertTrue(QuickTemplates.suggest(history, emptyList(), now + 90 * 86_400_000L, zone).isEmpty())
        assertTrue(QuickTemplates.suggest(history.map { it.copy(status = ItemStatus.DRAFT) }, emptyList(), now, zone).isEmpty())
    }
}
