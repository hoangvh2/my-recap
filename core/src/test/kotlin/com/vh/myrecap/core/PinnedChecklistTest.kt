package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedChecklistTest {
    private fun item(texts: List<String>, status: ItemStatus = ItemStatus.OPEN, type: ItemType = ItemType.TASK) =
        Item(id = "a", type = type, status = status, title = "Ra khỏi nhà", checklist = Checklist.entriesFrom(texts))

    @Test
    fun onlyAnOpenTaskOrEventWithAChecklistCanBePinned() {
        assertTrue(PinnedChecklist.eligible(item(listOf("Ví"))))
        assertTrue(PinnedChecklist.eligible(item(listOf("Ví"), type = ItemType.EVENT)))
        assertFalse(PinnedChecklist.eligible(item(emptyList())))
        assertFalse(PinnedChecklist.eligible(item(listOf("Ví"), status = ItemStatus.DONE)))
        assertFalse(PinnedChecklist.eligible(item(listOf("Ví"), type = ItemType.NOTE)))
        assertFalse(PinnedChecklist.eligible(null))
        assertNull(PinnedChecklist.view(item(emptyList())))
    }

    @Test
    fun showsWhatIsMissingWithACountAndTheFirstLineToTick() {
        val i = item(listOf("Ví", "Chìa khoá", "Sạc"))
        val v = PinnedChecklist.view(Checklist.toggle(i, i.checklist[0].id))!!
        assertEquals("Còn 2/3", v.summary)
        assertEquals(listOf("Chìa khoá", "Sạc"), v.lines)
        assertEquals("✓ Chìa khoá", v.tickLabel)
        assertEquals(i.checklist[1].id, v.tickId)
        assertFalse(v.complete)
    }

    @Test
    fun longListsShowSixLinesAndACountOfTheRestAndLongLabelsAreClipped() {
        val i = item((1..9).map { "Mục số $it" } )
        val v = PinnedChecklist.view(i)!!
        assertEquals(7, v.lines.size)
        assertEquals("+3 nữa", v.lines.last())
        val long = PinnedChecklist.view(item(listOf("Mang theo giấy tờ xe và bảo hiểm")))!!
        assertEquals("✓ Mang theo giấ…", long.tickLabel)
        assertEquals(16, long.tickLabel!!.length)
    }

    @Test
    fun tickingTheLastMissingLineSaysSoAndOtherLinesDoNot() {
        val i = item(listOf("Ví", "Sạc"))
        val (one, finished1) = PinnedChecklist.tick(i, i.checklist[0].id)
        assertFalse(finished1)
        val (two, finished2) = PinnedChecklist.tick(one, i.checklist[1].id)
        assertTrue(finished2)
        assertTrue(Checklist.progress(two).complete)
    }

    @Test
    fun tickingAnUnknownOrAlreadyTickedLineChangesNothing() {
        val i = item(listOf("Ví", "Sạc"))
        assertSame(i, PinnedChecklist.tick(i, "nope").first)
        val ticked = Checklist.toggle(i, i.checklist[0].id)
        assertSame(ticked, PinnedChecklist.tick(ticked, i.checklist[0].id).first)
    }

    @Test
    fun undoingTheLastTickMakesThatLineMissingAndReopensTheItem() {
        val i = item(listOf("Ví", "Sạc"))
        val done = Checklist.tickAll(i).copy(status = ItemStatus.DONE, doneAt = 5L)
        val back = PinnedChecklist.reopen(done, i.checklist[1].id)
        assertEquals(ItemStatus.OPEN, back.status)
        assertNull(back.doneAt)
        assertEquals(listOf("Sạc"), Checklist.progress(back).remaining.map { it.text })
        assertSame(back, PinnedChecklist.reopen(back, i.checklist[1].id))
    }
}
