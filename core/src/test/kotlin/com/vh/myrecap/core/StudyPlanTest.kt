package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlanTest {
    private fun item(vararg texts: String, type: ItemType = ItemType.EVENT, status: ItemStatus = ItemStatus.OPEN) =
        Item(id = "s", type = type, status = status, title = "Học bài mới", checklist = Checklist.entriesFrom(texts.toList()))

    @Test
    fun readsMinutesFromTheStartOfALine() {
        assertEquals(10, StudyPlan.minutesOf("10 phút: AnkiDroid ôn thẻ đến hạn."))
        assertEquals(20, StudyPlan.minutesOf("20 phút: Voca Prep → BÀI GIẢNG"))
        assertEquals(5, StudyPlan.minutesOf("5p ghi 3 lỗi"))
        assertEquals(15, StudyPlan.minutesOf("  15 min nghe lại"))
        assertEquals(25, StudyPlan.minutesOf("25' đọc"))
        assertEquals(10, StudyPlan.minutesOf("10 PHÚT: viết"))
    }

    @Test
    fun readsComposedAndDecomposedVietnamese() {
        val decomposed = java.text.Normalizer.normalize("10 phút: ôn", java.text.Normalizer.Form.NFD)
        assertEquals(10, StudyPlan.minutesOf(decomposed))
    }

    @Test
    fun ignoresNumbersThatAreNotATimeAtTheStart() {
        assertNull(StudyPlan.minutesOf("Ôn 10 phút"))
        assertNull(StudyPlan.minutesOf("10 phim hay"))
        assertNull(StudyPlan.minutesOf("10 pages"))
        assertNull(StudyPlan.minutesOf("0 phút: không"))
        assertNull(StudyPlan.minutesOf("999 phút: quá dài"))
        assertNull(StudyPlan.minutesOf("Nghe lại"))
    }

    @Test
    fun onlyLinesStillToDoBecomeSteps() {
        val i = item("10 phút: Anki", "20 phút: Voca", "Ghi chú")
        assertEquals(listOf(10, 20, null), StudyPlan.phases(i).map { it.minutes })
        val done = StudyPlan.tick(i, listOf(i.checklist[0].id, "unknown"))
        assertEquals(listOf(true, false, false), done.checklist.map { it.done })
        assertEquals(listOf(20, null), StudyPlan.phases(done).map { it.minutes })
        assertEquals(20, StudyPlan.totalMinutes(StudyPlan.phases(done)))
        assertEquals("nothing new to tick", done, StudyPlan.tick(done, listOf(i.checklist[0].id)))
    }

    @Test
    fun aSessionNeedsAnOpenItemWithATimedLine() {
        assertTrue(StudyPlan.canStart(item("10 phút: Anki", "Ghi chú")))
        assertFalse("no times", StudyPlan.canStart(item("Anki", "Ghi chú")))
        assertFalse("finished item", StudyPlan.canStart(item("10 phút: Anki", status = ItemStatus.DONE)))
        assertFalse("notes have no checklist", StudyPlan.canStart(item("10 phút: Anki", type = ItemType.NOTE)))
    }
}
