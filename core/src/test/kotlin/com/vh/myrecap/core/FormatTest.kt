package com.vh.myrecap.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatTest {
    @Test
    fun clockFormatsMinutesAndHours() {
        assertEquals("00:00", TimeFormat.clock(0))
        assertEquals("01:05", TimeFormat.clock(65_999))
        assertEquals("1:02:05", TimeFormat.clock(3_725_000))
        assertEquals("00:00", TimeFormat.clock(-5))
    }

    @Test
    fun assemblerOrdersClipsAndPlacesBookmarks() {
        val out = TranscriptAssembler.assemble(
            listOf(
                ClipText(2, 600_000, 900_000, "Kinh nghiệm Kotlin", "B"),
                ClipText(1, 0, 600_000, null, "A"),
            ),
            bookmarksMs = listOf(30_000, 600_000, 900_000),
        )
        val expected = "[Đoạn 1 · 00:00–10:00]  ⭐ 00:30\nA\n\n[Đoạn 2 · 10:00–15:00] Kinh nghiệm Kotlin  ⭐ 10:00, 15:00\nB"
        assertEquals(expected, out)
    }

    @Test
    fun assemblerMarksMissingTranscript() {
        assertTrue("(chưa có transcript)" in TranscriptAssembler.assemble(listOf(ClipText(1, 0, 1000, null, null)), emptyList()))
    }

    @Test
    fun parsesTitleLine() {
        assertEquals(
            ClipTranscript("Lý do nghỉ việc", "Ứng viên: Vì muốn thử thách mới."),
            Prompts.parseClipTranscript("# **Lý do nghỉ việc**\nỨng viên: Vì muốn thử thách mới.\n"),
        )
        assertEquals(ClipTranscript(null, "Xin chào"), Prompts.parseClipTranscript("  Xin chào "))
        assertEquals(ClipTranscript("Chào hỏi", Prompts.NO_SPEECH), Prompts.parseClipTranscript("# Chào hỏi"))
    }

    @Test
    fun interviewerHandlingChangesPrompt() {
        val keep = Prompts.transcriptionInstruction(SessionMode.INTERVIEW, "", InterviewerSpeech.KEEP)
        val condense = Prompts.transcriptionInstruction(SessionMode.INTERVIEW, "", InterviewerSpeech.CONDENSE)
        val drop = Prompts.transcriptionInstruction(SessionMode.INTERVIEW, "", InterviewerSpeech.DROP, OutputLanguage.JA)
        assertFalse("Leave out" in keep || "at most 20 words" in keep)
        assertTrue("at most 20 words" in condense)
        assertTrue("Leave out" in drop && Prompts.INTERVIEWER_ONLY in drop && "Japanese" in drop)
        // Meetings ignore the interviewer option.
        assertFalse("Leave out" in Prompts.transcriptionInstruction(SessionMode.MEETING, "", InterviewerSpeech.DROP))
    }

    @Test
    fun transcriptionPromptIncludesModeAndTail() {
        val longTail = (1..100).joinToString("\n") { "Người nói 1: câu số $it" }
        val p = Prompts.transcriptionInstruction(SessionMode.INTERVIEW, longTail)
        assertTrue("Ứng viên" in p)
        assertTrue("câu số 100" in p)
        assertFalse("câu số 1\n" in p, "tail should be trimmed to the end of the previous chunk")
        assertFalse("earlier part" in Prompts.transcriptionInstruction(SessionMode.MEETING, Prompts.NO_SPEECH))
    }

    @Test
    fun summaryMessageMentionsBookmarksOnlyWhenPresent() {
        val with = Prompts.summaryUserMessage(SessionMode.MEETING, "", "Họp", 60_000, listOf(5_000), "T")
        assertTrue("Điểm được đánh dấu" in with && "00:05" in with && "Việc cần làm" in with)
        val without = Prompts.summaryUserMessage(SessionMode.CUSTOM, "Viết lại thành email", "Họp", 60_000, emptyList(), "T")
        assertFalse("Điểm được đánh dấu" in without)
        assertTrue(without.startsWith("Viết lại thành email"))
    }

    @Test
    fun shareTextRespectsContentChoice() {
        val s = ShareText.build(ShareText.Content.SUMMARY, "T", "08/10", 61_000, "SUM", "TRANS")
        assertTrue("SUM" in s && "TRANS" !in s && "01:01" in s)
        val t = ShareText.build(ShareText.Content.TRANSCRIPT, "T", "08/10", 0, "SUM", "TRANS")
        assertTrue("SUM" !in t && "TRANS" in t)
    }
}
