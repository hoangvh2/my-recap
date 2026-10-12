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
        val list = listOf(QuickTemplate("a …", uses = 3, lastUsed = 42), QuickTemplate("b"))
        assertEquals(list, QuickTemplates.decode(QuickTemplates.encode(list)))
        assertNull(QuickTemplates.decode(null))
        assertEquals(emptyList<QuickTemplate>(), QuickTemplates.decode("not json"))
        // An older value saved with "pinned" still reads.
        assertEquals(listOf(QuickTemplate("x")), QuickTemplates.decode("""[{"text":"x","pinned":true}]"""))
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

    @Test
    fun kindOfALine() {
        assertEquals(ItemType.EXPENSE, QuickTemplates.typeOf("ăn trưa …k", now, zone))
        assertEquals(ItemType.EXPENSE, QuickTemplates.typeOf("cà phê 30k", now, zone))
        assertEquals(ItemType.TASK, QuickTemplates.typeOf("gọi … mai 9h", now, zone))
        assertEquals(ItemType.EVENT, QuickTemplates.typeOf("họp … thứ 2 9h", now, zone))
    }

    @Test
    fun featuredIsTheMostUsedOfEachKind() {
        val list = listOf(
            QuickTemplate("cà phê 30k", uses = 2),
            QuickTemplate("gọi … mai 9h"),
            QuickTemplate("đổ xăng …k", uses = 5),
            QuickTemplate("mua sữa", uses = 1),
            QuickTemplate("họp … thứ 2 9h"),
        )
        assertEquals(listOf("mua sữa", "họp … thứ 2 9h", "đổ xăng …k"), QuickTemplates.featured(list, now, zone).map { it.text })
        // No line of a kind: that kind is simply missing.
        assertEquals(listOf("cà phê 30k"), QuickTemplates.featured(listOf(QuickTemplate("cà phê 30k")), now, zone).map { it.text })
    }

    @Test
    fun usingALineCountsIt() {
        val list = QuickTemplates.used(QuickTemplates.DEFAULTS, QuickTemplate("ăn trưa …k"), now)
        assertEquals(1, list.single { it.text == "ăn trưa …k" }.uses)
        assertEquals("ăn trưa …k", QuickTemplates.featured(list, now, zone).last().text)
    }

    @Test
    fun matchingIsLikeSearchOverAllWords() {
        val list = listOf(
            QuickTemplate("cà phê 30k"),
            QuickTemplate("ăn trưa cá kho …k", uses = 4),
            QuickTemplate("gọi … mai 9h"),
            QuickTemplate("cafe với khách …k"),
        )
        // Starts-with first, then most used; accents ignored.
        assertEquals(listOf("cà phê 30k", "cafe với khách …k", "ăn trưa cá kho …k"), QuickTemplates.matching(list, "ca").map { it.text })
        assertEquals(listOf("ăn trưa cá kho …k"), QuickTemplates.matching(list, "trua ca").map { it.text })
        assertEquals(listOf("gọi … mai 9h"), QuickTemplates.matching(list, "goi mai").map { it.text })
        assertTrue(QuickTemplates.matching(list, "  ").isEmpty())
        assertTrue(QuickTemplates.matching(list, "xyz").isEmpty())
        // The box already holds the whole line: nothing more to offer.
        assertTrue(QuickTemplates.matching(list, "cà phê 30k").isEmpty())
    }

    @Test
    fun editingKeepsUseCountsAndRefusesDuplicates() {
        val list = listOf(QuickTemplate("cà phê 30k", uses = 3), QuickTemplate("phở 50k"))
        assertEquals(QuickTemplate("cà phê 35k", uses = 3), QuickTemplates.edit(list, 0, "cà phê   35k")[0])
        assertEquals(list, QuickTemplates.edit(list, 0, "Phở 50k"))
        assertEquals(list, QuickTemplates.edit(list, 0, " "))
        assertEquals(list, QuickTemplates.edit(list, 5, "x"))
    }
}
