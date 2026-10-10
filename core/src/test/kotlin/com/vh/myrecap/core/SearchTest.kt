package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SearchTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun foldRemovesVietnameseDiacritics() {
        assertEquals("gui bao gia cho khach", TextSearch.fold("Gửi  BÁO GIÁ cho khách"))
        assertEquals("dang do duong", TextSearch.fold("Đang đổ đường"))
        assertEquals("nghieng", TextSearch.fold("nghiêng"))
    }

    @Test
    fun matchesAllTokensInAnyOrder() {
        val text = TextSearch.fold("Họp với anh Nam ở văn phòng")
        assertTrue(TextSearch.matches(text, TextSearch.tokens("nam hop")))
        assertTrue(TextSearch.matches(text, TextSearch.tokens("VĂN phong")))
        assertFalse(TextSearch.matches(text, TextSearch.tokens("nam cafe")))
        assertFalse("empty query matches nothing", TextSearch.matches(text, TextSearch.tokens("  ")))
    }

    @Test
    fun snippetCentersOnTheMatchAndKeepsAccents() {
        val text = "Đầu buổi giới thiệu. " + "Nội dung dài. ".repeat(20) + "Ứng viên nói về Kotlin coroutines rất kỹ. " +
            "Kết thúc. ".repeat(20)
        val s = TextSearch.snippet(text, TextSearch.tokens("kotlin"), radius = 20)
        assertTrue(s, s.contains("Kotlin"))
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertEquals("Ngắn gọn", TextSearch.snippet("Ngắn gọn", TextSearch.tokens("gon")))
    }

    @Test
    fun csvQuotesAndNeutralisesFormulas() {
        val items = listOf(
            Item("b", ItemType.EXPENSE, ItemStatus.OPEN, "Taxi, sân bay", whenAt = ms(2026, 10, 11), allDay = true, amount = 250_000, category = "Di chuyển"),
            Item("a", ItemType.EXPENSE, ItemStatus.OPEN, "=HYPERLINK(\"x\")", details = "ghi \"chú\"", whenAt = ms(2026, 10, 10), amount = 65_000, category = "Ăn uống"),
        )
        val csv = ExpenseCsv.build(items, zone)
        val lines = csv.removePrefix(ExpenseCsv.BOM).split("\r\n")
        assertTrue(csv.startsWith(ExpenseCsv.BOM))
        assertEquals("Ngày,Khoản chi,Danh mục,Số tiền (VND),Ghi chú", lines[0])
        assertEquals("2026-10-10,\"'=HYPERLINK(\"\"x\"\")\",Ăn uống,65000,\"ghi \"\"chú\"\"\"", lines[1])
        assertEquals("2026-10-11,\"Taxi, sân bay\",Di chuyển,250000,", lines[2])
        assertEquals(",Tổng,,315000,", lines[3])
    }

    @Test
    fun reminderTimes() {
        val event = Item("e", ItemType.EVENT, ItemStatus.OPEN, "Họp", whenAt = ms(2026, 10, 15, 15))
        assertEquals(ms(2026, 10, 15, 14, 45), ReminderPolicy.triggerAt(event, zone))
        val task = Item("t", ItemType.TASK, ItemStatus.OPEN, "Gửi", whenAt = ms(2026, 10, 15, 9, 30))
        assertEquals(ms(2026, 10, 15, 9, 30), ReminderPolicy.triggerAt(task, zone))
        val allDay = task.copy(whenAt = ms(2026, 10, 16), allDay = true)
        assertEquals(ms(2026, 10, 16, 8), ReminderPolicy.triggerAt(allDay, zone))
        assertNull("drafts never remind", ReminderPolicy.triggerAt(task.copy(status = ItemStatus.DRAFT), zone))
        assertNull("done tasks never remind", ReminderPolicy.triggerAt(task.copy(status = ItemStatus.DONE), zone))
        assertNull("expenses never remind", ReminderPolicy.triggerAt(task.copy(type = ItemType.EXPENSE), zone))
        assertNull("no time, no reminder", ReminderPolicy.triggerAt(task.copy(whenAt = null), zone))
    }
}
