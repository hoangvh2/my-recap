package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ItemsTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")

    // Saturday 10/10/2026 14:00 local.
    private val now = ZonedDateTime.of(2026, 10, 10, 14, 0, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private var counter = 0
    private fun parse(raw: String) = ItemExtraction.parse(raw, zone, nowMs, "memo1") { "i${counter++}" }
    private fun local(ms: Long) = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone)

    @Test
    fun moneyParsesSpokenVietnameseAmounts() {
        assertEquals(85_000L, Money.parseVnd("85k"))
        assertEquals(85_000L, Money.parseVnd("85 nghìn"))
        assertEquals(1_200_000L, Money.parseVnd("1 triệu 2"))
        assertEquals(1_500_000L, Money.parseVnd("1tr5"))
        assertEquals(1_500_000L, Money.parseVnd("1,5 triệu"))
        assertEquals(1_250_000L, Money.parseVnd("1 triệu 250"))
        assertEquals(1_200_000L, Money.parseVnd("1.200.000đ"))
        assertEquals(2_000_000_000L, Money.parseVnd("2 tỷ"))
        assertNull(Money.parseVnd("không có"))
        assertEquals("1.250.000 đ", Money.format(1_250_000))
        assertEquals("500 đ", Money.format(500))
    }

    @Test
    fun parsesMixedItemsWithDatesTimesAndAmounts() {
        val raw = """
            ```json
            {"items":[
              {"type":"event","title":"Họp với anh Nam","details":"","date":"2026-10-15","time":"15:00","place":"Cafe Highlands","person":"anh Nam","quote":"thứ 5 tuần sau 3h chiều họp anh Nam"},
              {"type":"expense","title":"Ăn trưa","date":null,"time":null,"amount":"85k","category":"ăn uống","quote":"ăn trưa 85k"},
              {"type":"task","title":"Gửi báo giá","date":"2026-10-11","time":null},
              {"type":"note","title":"Ý tưởng app","details":"widget ghi nhanh","date":null,"time":null,"amount":5000}
            ]}
            ```
        """.trimIndent()
        val items = parse(raw)!!
        assertEquals(4, items.size)
        assertTrue(items.all { it.status == ItemStatus.DRAFT && it.sourceId == "memo1" })

        val event = items[0]
        assertEquals(ItemType.EVENT, event.type)
        assertEquals(LocalDateTime.of(2026, 10, 15, 15, 0), local(event.whenAt!!))
        assertFalse(event.allDay)
        assertEquals("Cafe Highlands", event.place)

        val expense = items[1]
        assertEquals(ItemType.EXPENSE, expense.type)
        assertEquals(85_000L, expense.amount)
        assertEquals("Ăn uống", expense.category)
        assertTrue("expense without date is today", expense.allDay)
        assertEquals(LocalDateTime.of(2026, 10, 10, 0, 0), local(expense.whenAt!!))

        val task = items[2]
        assertTrue(task.allDay)
        assertEquals(LocalDateTime.of(2026, 10, 11, 0, 0), local(task.whenAt!!))

        val note = items[3]
        assertNull("only expenses carry amounts", note.amount)
        assertNull(note.whenAt)
    }

    @Test
    fun timeWithoutDateIsTheNextOccurrence() {
        val items = parse("""[{"type":"task","title":"Gọi mẹ","time":"9h"},{"type":"task","title":"Uống thuốc","time":"20:30"}]""")!!
        assertEquals(LocalDateTime.of(2026, 10, 11, 9, 0), local(items[0].whenAt!!))
        assertEquals(LocalDateTime.of(2026, 10, 10, 20, 30), local(items[1].whenAt!!))
    }

    @Test
    fun unknownCategoryAndTypeFallBack() {
        val items = parse("""{"items":[{"type":"expense","title":"Quà","amount":200000,"category":"Lặt vặt"},{"type":"idea","title":"X"}]}""")!!
        assertEquals("Khác", items[0].category)
        assertEquals(ItemType.NOTE, items[1].type)
    }

    @Test
    fun emptyAndBrokenOutput() {
        assertEquals(0, parse("""{"items":[]}""")!!.size)
        assertEquals(0, parse("""Kết quả: {"items":[{"type":"task","title":"","details":""}]}""")!!.size)
        assertNull(parse("Xin lỗi, tôi không hiểu."))
        assertNull(parse("{\"items\": [ {\"type\": "))
    }

    @Test
    fun itemJsonRoundTrip() {
        val item = Item(
            id = "a", type = ItemType.EXPENSE, status = ItemStatus.OPEN, title = "Taxi", details = "sân bay",
            whenAt = nowMs, allDay = true, amount = 250_000, category = "Di chuyển", sourceId = "memo1",
            quote = "taxi 250k", createdAt = nowMs,
        )
        assertEquals(item, Item.fromJson(item.toJson()))
        val minimal = Item("b", ItemType.NOTE, ItemStatus.DONE, "x", doneAt = 5)
        assertEquals(minimal, Item.fromJson(minimal.toJson()))
    }

    @Test
    fun promptCarriesTodayAndUpcomingWeekdays() {
        val msg = ItemExtraction.userMessage("mai họp", now)
        assertTrue(msg.contains("Thứ Bảy, 2026-10-10 14:00"))
        assertTrue(msg.contains("- Chủ Nhật 2026-10-11 (mai)"))
        assertTrue(msg.contains("- Thứ Năm 2026-10-15"))
        assertTrue(msg.contains("mai họp"))
    }

    @Test
    fun itemLineForSharing() {
        val item = Item("a", ItemType.EXPENSE, ItemStatus.OPEN, "Taxi", whenAt = nowMs, allDay = true, amount = 250_000)
        assertEquals("- Taxi — 250.000 đ (10/10/2026)", ItemText.line(item, zone))
    }
}
