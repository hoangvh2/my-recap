package com.vh.myrecap.core

object Prompts {
    const val NO_SPEECH = "[không có lời nói]"
    private const val TAIL_CHARS = 600

    fun transcriptionInstruction(mode: SessionMode, previousTail: String): String = buildString {
        appendLine("Transcribe this audio recording verbatim.")
        appendLine(
            "The speakers mainly use Vietnamese, English and sometimes Japanese, and may switch language " +
                "mid-sentence. Write every word in the language and script it was spoken in (Vietnamese with full " +
                "diacritics, Japanese in kana/kanji). Never translate.",
        )
        when (mode) {
            SessionMode.INTERVIEW -> appendLine(
                "This is a job interview, usually between an interviewer and a candidate. Start each speaker turn on " +
                    "a new line with a label: 'Người phỏng vấn:' or 'Ứng viên:' when the role is clear from context, " +
                    "otherwise 'Người nói 1:', 'Người nói 2:' and so on.",
            )
            SessionMode.MEETING, SessionMode.CUSTOM -> appendLine(
                "This is a meeting. Start each speaker turn on a new line with a label 'Người nói 1:', " +
                    "'Người nói 2:' and so on. If a speaker's name is clearly stated, use 'Name:' instead.",
            )
        }
        appendLine("Remove filler sounds (ừm, à, uh) but keep all meaningful content. Mark inaudible words as [không rõ].")
        appendLine("Output only the transcript: no title, no timestamps, no commentary, no summary.")
        appendLine("If the audio contains no speech, output exactly: $NO_SPEECH")
        val tail = tail(previousTail)
        if (tail.isNotBlank()) {
            appendLine()
            appendLine(
                "This audio continues directly from an earlier part. Keep the same speaker labels for the same " +
                    "voices. The earlier part ended with:",
            )
            appendLine("<<<")
            appendLine(tail)
            appendLine(">>>")
        }
    }.trim()

    /** Whisper's `prompt` only conditions vocabulary/style; keep it short (Whisper uses ~224 tokens). */
    fun whisperPrompt(previousTail: String): String = tail(previousTail, 300)

    fun summarySystem(language: OutputLanguage): String =
        "Bạn là trợ lý ghi chép chuyên nghiệp. Chỉ dựa trên transcript được cung cấp; không bịa thông tin. " +
            "Nếu transcript không đủ để kết luận, ghi rõ 'Chưa đủ thông tin'. Transcript do máy nhận dạng giọng nói " +
            "tạo ra nên có thể sai chính tả hoặc gán nhầm người nói — hãy suy luận hợp lý theo ngữ cảnh. " +
            "Trình bày bằng Markdown gọn gàng, dễ đọc trên điện thoại. ${language.instruction}"

    fun summaryTemplate(mode: SessionMode, customPrompt: String): String = when (mode) {
        SessionMode.INTERVIEW -> """
            Viết biên bản phỏng vấn với các mục:
            ## Tổng quan
            Vị trí ứng tuyển, người tham gia, nhận định chung trong 2–3 câu.
            ## Câu hỏi & trả lời chính
            Mỗi ý: **Câu hỏi** → tóm tắt câu trả lời của ứng viên.
            ## Điểm mạnh
            ## Điểm cần lưu ý / rủi ro
            ## Đánh giá kỹ năng
            Chuyên môn, giao tiếp, ngoại ngữ (nếu thể hiện trong buổi), thái độ. Có dẫn chứng ngắn.
            ## Câu hỏi của ứng viên
            ## Đề xuất bước tiếp theo
        """.trimIndent()
        SessionMode.MEETING -> """
            Viết biên bản cuộc họp với các mục:
            ## Tóm tắt
            3–5 gạch đầu dòng quan trọng nhất.
            ## Quyết định đã chốt
            ## Việc cần làm
            Dạng bảng: | Việc | Người phụ trách | Hạn |  (ghi "?" nếu không rõ).
            ## Vấn đề còn mở
            ## Chi tiết theo chủ đề
        """.trimIndent()
        SessionMode.CUSTOM -> customPrompt.ifBlank { "Tóm tắt nội dung chính, các quyết định và việc cần làm." }
    }

    fun summaryUserMessage(
        mode: SessionMode,
        customPrompt: String,
        title: String,
        durationMs: Long,
        bookmarksMs: List<Long>,
        transcript: String,
    ): String = buildString {
        appendLine(summaryTemplate(mode, customPrompt))
        appendLine()
        if (bookmarksMs.isNotEmpty()) {
            appendLine(
                "Người dùng đã đánh dấu các thời điểm quan trọng: " +
                    bookmarksMs.joinToString(", ") { TimeFormat.clock(it) } +
                    ". Thêm mục '## Điểm được đánh dấu' nêu nội dung chính quanh các mốc này " +
                    "(transcript ghi mốc ở đầu mỗi đoạn).",
            )
            appendLine()
        }
        appendLine("Tiêu đề: $title")
        appendLine("Loại: ${mode.label}")
        appendLine("Thời lượng ghi: ${TimeFormat.clock(durationMs)}")
        appendLine()
        appendLine("TRANSCRIPT:")
        appendLine("<<<")
        appendLine(transcript.trim())
        appendLine(">>>")
    }.trim()

    private fun tail(text: String, max: Int = TAIL_CHARS): String {
        val t = text.trim()
        if (t.isEmpty() || t == NO_SPEECH) return ""
        if (t.length <= max) return t
        // Cut on a line boundary when possible so the speaker label stays attached.
        val cut = t.substring(t.length - max)
        val nl = cut.indexOf('\n')
        return if (nl in 0 until max / 2) cut.substring(nl + 1) else cut
    }
}
