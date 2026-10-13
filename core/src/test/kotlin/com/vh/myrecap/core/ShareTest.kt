package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ShareTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun at(d: Int, h: Int) = ZonedDateTime.of(2026, 10, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun codesAreReadAndPrintedTheWayTheServerExpects() {
        assertEquals("ABCDEFGH", Share.normalizeCode(" abcd-efgh "))
        assertEquals("ABCD2345", Share.normalizeCode("abcd 2345"))
        assertNull(Share.normalizeCode("ABCD EFG")) // too short
        assertNull(Share.normalizeCode("ABCD EFG0")) // 0 is not in the alphabet (looks like O)
        assertNull(Share.normalizeCode("ABCD EFGI")) // nor I
        assertEquals("ABCD EFGH", Share.formatCode("ABCDEFGH"))
        assertEquals("Em Lan", Share.cleanAlias("  Em   Lan "))
        assertEquals(40, Share.cleanAlias("x".repeat(60)).length)
    }

    @Test
    fun repeatingTasksKeepOneSeriesIdOnEveryDevice() {
        assertEquals("t1", Share.parentOfRepeat("t1_n1760000000000"))
        assertEquals("t1", Share.parentOfRepeat("t1_n1_n2"))
        assertNull(Share.parentOfRepeat("t1"))
        assertNull(Share.parentOfRepeat("t1_nx"))
        assertEquals("t1_n5", Share.repeatId("t1", 5))
        assertEquals("t1_n7", Share.repeatId("t1_n5_n6", 7))
        assertEquals("u1_t1", Share.shareId("u1", "t1"))
    }

    @Test
    fun expensesAndDraftsAreNeverShareable() {
        val task = Item("a", ItemType.TASK, ItemStatus.OPEN, "x")
        assertTrue(Share.isShareable(task))
        assertTrue(Share.isShareable(task.copy(type = ItemType.NOTE)))
        assertFalse(Share.isShareable(task.copy(type = ItemType.EXPENSE)))
        assertFalse(Share.isShareable(task.copy(status = ItemStatus.DRAFT)))
    }

    @Test
    fun cloudCopyFitsTheRulesAndDoesNotBounceBack() {
        val long = Item("a1", ItemType.NOTE, ItemStatus.OPEN, "  " + "t".repeat(150), details = "d".repeat(2500), amount = 5, recurrence = Recurrence.DAILY, createdAt = 9)
        val m = CloudItem.toMap(long)
        assertEquals(120, (m["title"] as String).length)
        assertEquals(2000, (m["details"] as String).length)
        assertNull(m["amount"]) // only expenses carry money
        assertNull(m["recurrence"]) // only tasks and appointments repeat
        assertNull(m["important"]) // false is written as "no field", like the web
        assertTrue(m.containsKey("place")) // present as null: removes a place cleared on this phone

        val back = CloudItem.fromMap("a1", m)!!
        assertTrue(CloudItem.sameInCloud(long, back))
        assertFalse(CloudItem.sameInCloud(long, back.copy(title = "changed on the web")))
        assertFalse(CloudItem.sameInCloud(null, back))

        assertFalse(CloudItem.canSync(long.copy(status = ItemStatus.DRAFT)))
        assertFalse(CloudItem.canSync(long.copy(id = "has space")))
        assertEquals("(không tiêu đề)", CloudItem.clamp(long.copy(title = "  ")).title)
    }

    @Test
    fun readsWhatTheWebAndTheServerWrite() {
        val web = mapOf<String, Any?>(
            "id" to "w1", "type" to "EVENT", "status" to "OPEN", "title" to "Họp", "details" to "",
            "whenAt" to 1_760_000_000_000L, "allDay" to false, "createdAt" to 1L, "customerId" to "c1", "important" to true,
        )
        val item = CloudItem.fromMap("w1", web)!!
        assertEquals(ItemType.EVENT, item.type)
        assertTrue(item.important)
        assertNull(CloudItem.fromMap("x", web - "type"))

        val share = SharedItem.fromMap(
            mapOf(
                "id" to "uA_w1", "ownerUid" to "uA", "ownerName" to "An", "itemId" to "w1", "audience" to listOf("uB"),
                "type" to "EVENT", "status" to "OPEN", "title" to "Nhậu", "details" to "", "whenAt" to 5L, "allDay" to false,
                "createdAt" to 1L, "updatedAt" to 2L,
            ),
        )!!
        assertEquals("uA_w1", share.asItem().id)
        assertEquals("An", share.ownerLabel(emptyList()))
        assertEquals("Anh An", share.ownerLabel(listOf(Contact("uA", "Anh An", "An", "a•••@x.com", 0, true))))
    }

    @Test
    fun someoneElsesLateItemsDropOutOfMyLists() {
        fun s(id: String, whenAt: Long?, type: ItemType = ItemType.TASK, status: ItemStatus = ItemStatus.OPEN, allDay: Boolean = false) =
            SharedItem(id, "uA", "An", id, listOf("uB"), type, status, id, "", whenAt, allDay, null, null, false, null, 0, 0)
        val list = listOf(
            s("yesterday", at(12, 9)),
            s("this-morning", at(13, 8)),
            s("tonight", at(13, 22)),
            s("today-all-day", at(13, 0), allDay = true),
            s("undated", null),
            s("old-note", at(1, 9), type = ItemType.NOTE),
            s("done", at(14, 9), status = ItemStatus.DONE),
        )
        val shown = SharedItem.visible(list, zone, java.time.LocalDate.of(2026, 10, 13)).map { it.id }
        assertEquals(listOf("this-morning", "tonight", "today-all-day", "undated", "old-note"), shown)
    }
}
