package com.vh.myrecap.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Phone states as Android reports them in its phone-state broadcast. */
enum class CallState { IDLE, RINGING, OFFHOOK }

/** What the app remembers between phone-state broadcasts; the receiver itself keeps no memory. */
data class CallTrack(
    val state: CallState = CallState.IDLE,
    /** When the call was picked up or dialled; 0 when no call is connected. */
    val offhookAt: Long = 0,
    val incoming: Boolean = false,
)

data class EndedCall(val startedAt: Long, val durationMs: Long, val incoming: Boolean)

/**
 * After-call note prompt: the app cannot hear the call itself (Android blocks call audio), so once a
 * real conversation ends it offers the user to speak a short recap. Nothing is sent to the AI unless
 * the user records one.
 */
object CallNotes {
    /** Shorter calls (missed, voicemail, "call you back") are not worth a prompt. */
    const val MIN_DURATION_MS = 30_000L
    /** A connected state older than this means a broadcast was lost; never prompt on it. */
    private const val MAX_DURATION_MS = 6 * 60 * 60_000L

    /**
     * Advances the tracker for one broadcast. Returns the new tracker and the call that just ended,
     * when it was long enough to ask about. Repeated broadcasts of the same state change nothing
     * (Android sends each change twice when the app may read the call log).
     */
    fun step(track: CallTrack, next: CallState, now: Long): Pair<CallTrack, EndedCall?> {
        if (next == track.state) return track to null
        return when (next) {
            // A second call ringing during a conversation keeps the first call's start and direction.
            CallState.RINGING -> track.copy(state = CallState.RINGING) to null
            CallState.OFFHOOK ->
                if (track.offhookAt > 0) track.copy(state = CallState.OFFHOOK) to null
                else CallTrack(CallState.OFFHOOK, offhookAt = now, incoming = track.state == CallState.RINGING) to null
            CallState.IDLE -> {
                val duration = now - track.offhookAt
                val ended = if (track.offhookAt > 0 && duration in MIN_DURATION_MS..MAX_DURATION_MS) {
                    EndedCall(track.offhookAt, duration, track.incoming)
                } else {
                    null
                }
                CallTrack() to ended
            }
        }
    }

    /** "Gọi với Anh Tuấn · 14:05"; falls back to the number, then to the call direction. */
    fun title(call: EndedCall, name: String?, number: String?, zone: ZoneId): String {
        val who = who(name, number)
        val head = if (who != null) "Gọi với $who" else if (call.incoming) "Cuộc gọi đến" else "Cuộc gọi đi"
        return "$head · ${at(call.startedAt, zone, HM)}"
    }

    /** One line for the notification: how long the call was and what tapping does. */
    fun prompt(call: EndedCall): String = "${duration(call.durationMs)} · Nói nhanh nội dung chính để tạo việc, lịch hẹn."

    /** Background for the AI, so the recap's "anh ấy", "gửi lại cho họ" resolve to the caller. */
    fun context(call: EndedCall, name: String?, number: String?, zone: ZoneId): String {
        val who = who(name, number)
        return buildString {
            append("Đây là lời người dùng tự tóm tắt ngay sau một cuộc gọi ")
            append(if (call.incoming) "đến" else "đi")
            if (who != null) append(" với $who")
            append(" lúc ${at(call.startedAt, zone, DAY_HM)}, dài ${duration(call.durationMs)}.")
            if (who != null) {
                append(" Việc hay lịch hẹn liên quan đến người gọi thì điền person = \"$who\" khi ghi chú không nói người khác.")
            }
        }
    }

    fun duration(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec < 60) "$sec giây" else "${(sec + 30) / 60} phút"
    }

    private fun who(name: String?, number: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() } ?: number?.trim()?.takeIf { it.isNotEmpty() }

    private fun at(ms: Long, zone: ZoneId, format: DateTimeFormatter): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(format)

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_HM = DateTimeFormatter.ofPattern("HH:mm dd/MM")
}
