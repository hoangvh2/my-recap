package com.vh.myrecap.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryPipelineTest {
    private fun clip(n: Int, chars: Int, title: String? = "Câu $n") =
        ClipText(n, n * 60_000L, n * 60_000L + 50_000, title, "x".repeat(chars))

    @Test
    fun batchesRespectSizeAndOrderAndKeepOversizedClipsAlone() {
        val clips = listOf(clip(3, 3_000), clip(1, 3_000), clip(2, 3_000), clip(4, 20_000), clip(5, 100))
        val b = ClipNotes.batches(clips, maxChars = 8_000)
        assertEquals(listOf(listOf(1, 2), listOf(3), listOf(4), listOf(5)), b.map { batch -> batch.map { it.number } })
    }

    @Test
    fun parsesNotesPerClipAndIgnoresUnexpectedOnes() {
        val raw = """
            Đây là ghi chú:
            === Đoạn 2 ===
            Câu hỏi: Vì sao chọn Kotlin?
            Trả lời:
            - Null safety
            ===Đoạn 3===
            Câu hỏi: Dự án lớn nhất?
            === Đoạn 9 ===
            không mong đợi
        """.trimIndent()
        val notes = ClipNotes.parse(raw, expected = listOf(2, 3, 4))
        assertEquals(setOf(2, 3), notes.keys)
        assertTrue(notes.getValue(2).startsWith("Câu hỏi: Vì sao chọn Kotlin?") && "Null safety" in notes.getValue(2))
        assertEquals("Câu hỏi: Dự án lớn nhất?", notes.getValue(3))
    }

    @Test
    fun notesRequestListsEveryClipAndModeFormat() {
        val msg = ClipNotes.userMessage(SessionMode.INTERVIEW, InterviewerSpeech.DROP, listOf(clip(1, 10), clip(2, 10)))
        assertTrue("<<< Đoạn 1 — Câu 1" in msg && "<<< Đoạn 2 — Câu 2" in msg)
        assertTrue("Phải có đủ 2 ghi chú" in msg && "Câu hỏi:" in msg && "lược bỏ" in msg)
        assertTrue("Việc cần làm" in ClipNotes.userMessage(SessionMode.MEETING, InterviewerSpeech.KEEP, listOf(clip(1, 10))))
    }

    @Test
    fun speechFilterAndFallbackKeepWords() {
        assertFalse(ClipNotes.hasSpeech(ClipText(1, 0, 1, null, Prompts.NO_SPEECH)))
        assertFalse(ClipNotes.hasSpeech(ClipText(1, 0, 1, null, null)))
        assertTrue(ClipNotes.hasSpeech(ClipText(1, 0, 1, null, "Ứng viên: chào")))
        val fb = ClipNotes.fallback(clip(1, 2_000), maxChars = 100)
        assertTrue(fb.contains("trích transcript") && fb.endsWith("…") && fb.length < 200)
    }

    @Test
    fun interviewSummaryAppendsEveryClipDeterministically() {
        val notes = (1..40).map { clip(it, 10) to "Câu hỏi: Q$it\nTrả lời:\n- A$it" }
        val synthesis = "## Tổng quan\nỨng viên tốt (Đoạn 3)."
        val out = SummaryComposer.compose(SessionMode.INTERVIEW, synthesis, notes)
        assertTrue(out.startsWith("## Tổng quan"))
        assertTrue(SummaryComposer.DETAILS_INTERVIEW in out)
        // Every question survives, however long the interview: none depends on the model's choices.
        (1..40).forEach { assertTrue("### Đoạn $it — Câu $it\nCâu hỏi: Q$it" in out, "missing clip $it") }
    }

    @Test
    fun customSummaryIsNotPaddedWithDetails() {
        val out = SummaryComposer.compose(SessionMode.CUSTOM, "Email gửi khách", listOf(clip(1, 10) to "n"))
        assertEquals("Email gửi khách", out)
    }

    @Test
    fun synthesisAsksForEvaluationOnlyAndCitesAllClips() {
        val notes = listOf(clip(1, 10) to "Câu hỏi: A", clip(2, 10) to "Câu hỏi: B")
        val msg = SummaryComposer.synthesisMessage(SessionMode.INTERVIEW, "", "PV", 600_000, listOf(70_000), notes)
        assertTrue("KHÔNG liệt kê lại từng câu hỏi" in msg && "TẤT CẢ 2 đoạn" in msg)
        assertTrue("=== Đoạn 1 · 01:00–01:50 · Câu 1 · ⭐ 01:10 ===" in msg)
        assertTrue("Điểm được đánh dấu" in msg)
    }
}
