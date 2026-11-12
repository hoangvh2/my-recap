package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTextTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(m: Int, d: Int, h: Int = 7, min: Int = 30, y: Int = 2026) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private val now = ms(11, 12, 7, 29)

    private fun item(
        lines: List<String> = emptyList(),
        details: String = "",
        whenAt: Long? = ms(11, 12),
        place: String? = null,
        allDay: Boolean = false,
    ) = Item(
        id = "a", type = ItemType.EVENT, status = ItemStatus.OPEN, title = "TOEIC · Sáng: Anki", details = details,
        whenAt = whenAt, allDay = allDay, place = place, checklist = Checklist.entriesFrom(lines),
    )

    @Test
    fun theScreenshotCase_collapsedIsTimeAndWhatIsLeft_expandedIsOneLinePerThing() {
        val t = ReminderTexts.of(
            item(
                lines = listOf("12 phút: Anki ôn thẻ đến hạn: nhìn từ, đọc to, nhớ nghĩa, đặt câu ví dụ riêng cho từ khó", "8 phút: Anki 8 thẻ mới: nghe, đọc, ghi chú"),
                details = "Ngày làm việc · Giai đoạn 1: Nền tảng (2/11 – 13/12/2026). Kế hoạch đầy đủ: tài liệu Routine TOEIC.",
            ),
            now, zone,
        )
        assertEquals("07:30 hôm nay · còn 2 việc · ~20 phút", t.collapsed)
        assertEquals("07:30 hôm nay", t.header)
        assertEquals(2, t.entries.size)
        assertTrue(t.entries.all { it.startsWith("• ") && it.length <= ReminderTexts.ENTRY_CHARS + 2 })
        assertTrue(t.entries[0].endsWith("…"))
        assertEquals("8 phút: Anki 8 thẻ mới: nghe, đọc, ghi chú", t.entries[1].removePrefix("• "))
        assertTrue(t.summary!!.length <= ReminderTexts.SUMMARY_CHARS)
        assertTrue(t.summary!!.startsWith("Ngày làm việc"))
        // The long description is last, never in the collapsed line.
        assertTrue(t.asParagraph().lines().last() == t.summary)
        assertTrue("Giai đoạn" !in t.collapsed)
    }

    @Test
    fun manyLinesShowThreeThenACount_neverMoreThanFiveLinesInAll() {
        val t = ReminderTexts.of(item(lines = (1..7).map { "Việc $it" }), now, zone)
        assertEquals(listOf("• Việc 1", "• Việc 2", "• Việc 3", "+4 việc khác"), t.entries)
        assertEquals("07:30 hôm nay · còn 7 việc", t.collapsed)
        assertTrue(1 + t.entries.size + (if (t.summary != null) 1 else 0) <= 5)
    }

    @Test
    fun withoutChecklist_collapsedShowsPlace_andOnlyFirstLineOfDetailsIsKept() {
        val t = ReminderTexts.of(item(place = "Phòng họp 3", details = "\nMang laptop\nDòng hai"), now, zone)
        assertEquals("07:30 hôm nay · Phòng họp 3", t.collapsed)
        assertEquals(emptyList<String>(), t.entries)
        assertEquals("Mang laptop", t.summary)
        assertEquals("07:30 hôm nay · Phòng họp 3\nMang laptop", t.asParagraph())
    }

    @Test
    fun dayLabels() {
        assertEquals("07:30 ngày mai", ReminderTexts.whenLabel(item(whenAt = ms(11, 13)), now, zone))
        assertEquals("07:30 20/11", ReminderTexts.whenLabel(item(whenAt = ms(11, 20)), now, zone))
        assertEquals("07:30 02/01/2027", ReminderTexts.whenLabel(item(whenAt = ms(1, 2, y = 2027)), now, zone))
        assertEquals("Hôm nay", ReminderTexts.whenLabel(item(allDay = true), now, zone))
        assertNull(ReminderTexts.whenLabel(item(whenAt = null), now, zone))
    }

    @Test
    fun emptyItemFallsBackToTitle() {
        val t = ReminderTexts.of(item(whenAt = null), now, zone)
        assertEquals("TOEIC · Sáng: Anki", t.collapsed)
        assertNull(t.summary)
    }
}
