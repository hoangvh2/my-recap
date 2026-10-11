package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ActionItemsTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val now = ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, zone)
    private var n = 0
    private fun id() = "i${n++}"

    private val answer = """
        {"items":[
          {"type":"task","title":"Gửi báo giá cho ABC","person":"Nam","date":"2026-10-16","time":null,"quote":"Nam gửi báo giá cho ABC trước thứ Sáu","important":false},
          {"type":"note","title":"Khách quan tâm gói Pro","quote":"..."},
          {"type":"expense","title":"Cà phê","amount":50000},
          {"type":"event","title":"Họp lại với ABC","date":"2026-10-20","time":"09:00","quote":"Hẹn họp lại 9h sáng thứ Ba"}
        ]}
    """.trimIndent()

    @Test
    fun keepsOnlyTasksAndAppointmentsAsDraftsLinkedToTheSource() {
        val items = ActionItems.parse(answer, zone, now.toInstant().toEpochMilli(), "folder1", ::id)!!
        assertEquals(listOf("Gửi báo giá cho ABC", "Họp lại với ABC"), items.map { it.title })
        assertTrue(items.all { it.status == ItemStatus.DRAFT && it.sourceId == "folder1" })
        assertEquals("Nam", items[0].person)
        assertEquals("Nam gửi báo giá cho ABC trước thứ Sáu", items[0].quote)
    }

    @Test
    fun unreadableAnswerIsNullSoTheCallerCanSayItFailed() {
        assertNull(ActionItems.parse("xin lỗi, tôi không làm được", zone, 0, "f", ::id))
    }

    @Test
    fun noMoreThanTheCap() {
        val many = (1..40).joinToString(",") { """{"type":"task","title":"Việc $it"}""" }
        assertEquals(ActionItems.MAX_ITEMS, ActionItems.parse("""{"items":[$many]}""", zone, 0, "f", ::id)!!.size)
    }

    @Test
    fun runningTwiceDoesNotDuplicateWhatTheOwnerAlreadyHas() {
        val first = ActionItems.parse(answer, zone, 0, "f", ::id)!!
        val kept = first[0].copy(status = ItemStatus.OPEN)
        val second = ActionItems.parse(answer, zone, 0, "f", ::id)!!
        assertEquals(listOf("Họp lại với ABC"), ActionItems.withoutDuplicates(second, listOf(kept.copy(title = "GỬI BÁO GIÁ CHO abc"))).map { it.title })
    }

    @Test
    fun promptCarriesTheSummaryAndTheCalendar() {
        val msg = ActionItems.userMessage("Nam sẽ gửi báo giá.", now)
        assertTrue(msg.contains("Nam sẽ gửi báo giá."))
        assertTrue(msg.contains("2026-10-14 (hôm nay)"))
        assertTrue(msg.contains("không bịa") || ActionItems.system().contains("không bịa"))
    }
}
