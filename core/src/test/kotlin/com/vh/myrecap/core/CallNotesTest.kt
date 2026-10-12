package com.vh.myrecap.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallNotesTest {
    private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
    private val t0 = ZonedDateTime.of(2026, 10, 12, 14, 5, 0, 0, zone).toInstant().toEpochMilli()

    /** Feeds states one by one; returns the last tracker and every call that ended. */
    private fun run(vararg steps: Pair<CallState, Long>): Pair<CallTrack, List<EndedCall>> {
        var track = CallTrack()
        val ended = mutableListOf<EndedCall>()
        for ((state, at) in steps) {
            val (next, call) = CallNotes.step(track, state, at)
            track = next
            call?.let { ended += it }
        }
        return track to ended
    }

    @Test
    fun answeredIncomingCallEndsWithItsDuration() {
        val (track, ended) = run(
            CallState.RINGING to t0 - 5_000,
            CallState.OFFHOOK to t0,
            CallState.IDLE to t0 + 4 * 60_000,
        )
        assertEquals(listOf(EndedCall(t0, 4 * 60_000, incoming = true)), ended)
        assertEquals(CallTrack(), track)
    }

    @Test
    fun outgoingCallHasNoRinging() {
        val (_, ended) = run(CallState.OFFHOOK to t0, CallState.IDLE to t0 + 90_000)
        assertEquals(listOf(EndedCall(t0, 90_000, incoming = false)), ended)
    }

    @Test
    fun missedAndShortCallsDoNotPrompt() {
        assertTrue(run(CallState.RINGING to t0, CallState.IDLE to t0 + 20_000).second.isEmpty())
        assertTrue(run(CallState.OFFHOOK to t0, CallState.IDLE to t0 + 29_000).second.isEmpty())
    }

    @Test
    fun duplicateBroadcastsAreIgnored() {
        val (_, ended) = run(
            CallState.RINGING to t0 - 3_000,
            CallState.RINGING to t0 - 2_000,
            CallState.OFFHOOK to t0,
            CallState.OFFHOOK to t0 + 1_000,
            CallState.IDLE to t0 + 60_000,
            CallState.IDLE to t0 + 61_000,
        )
        assertEquals(listOf(EndedCall(t0, 60_000, incoming = true)), ended)
    }

    @Test
    fun waitingCallKeepsTheFirstCallsStartAndDirection() {
        val (_, ended) = run(
            CallState.OFFHOOK to t0,
            CallState.RINGING to t0 + 60_000,
            CallState.OFFHOOK to t0 + 70_000,
            CallState.IDLE to t0 + 120_000,
        )
        assertEquals(listOf(EndedCall(t0, 120_000, incoming = false)), ended)
    }

    @Test
    fun staleConnectedStateIsDropped() {
        // The phone was off-hook "since yesterday": a broadcast was lost, so no prompt.
        assertTrue(run(CallState.OFFHOOK to t0, CallState.IDLE to t0 + 7 * 3_600_000L).second.isEmpty())
    }

    @Test
    fun titlesUseNameThenNumberThenDirection() {
        val call = EndedCall(t0, 4 * 60_000, incoming = true)
        assertEquals("Gọi với Anh Tuấn · 14:05", CallNotes.title(call, "Anh Tuấn", "0901234567", zone))
        assertEquals("Gọi với 0901234567 · 14:05", CallNotes.title(call, " ", "0901234567", zone))
        assertEquals("Cuộc gọi đến · 14:05", CallNotes.title(call, null, null, zone))
        assertEquals("Cuộc gọi đi · 14:05", CallNotes.title(call.copy(incoming = false), null, "", zone))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("45 giây", CallNotes.duration(45_000))
        assertEquals("1 phút", CallNotes.duration(60_000))
        assertEquals("5 phút", CallNotes.duration(4 * 60_000 + 40_000))
    }

    @Test
    fun contextNamesTheCallerForTheExtractionPrompt() {
        val call = EndedCall(t0, 4 * 60_000, incoming = false)
        val withName = CallNotes.context(call, "Chị Lan", null, zone)
        assertTrue(withName.contains("cuộc gọi đi với Chị Lan lúc 14:05 12/10, dài 4 phút"))
        assertTrue(withName.contains("person = \"Chị Lan\""))
        val anonymous = CallNotes.context(call, null, null, zone)
        assertFalse(anonymous.contains("person"))

        val now = ZonedDateTime.of(2026, 10, 12, 14, 10, 0, 0, zone)
        val msg = ItemExtraction.userMessage("hẹn gửi báo giá thứ 5", now, withName)
        assertTrue(msg.contains("Bối cảnh: Đây là lời người dùng tự tóm tắt"))
        assertFalse(ItemExtraction.userMessage("x", now).contains("Bối cảnh"))
    }
}
