package com.vh.myrecap.core

/**
 * Two-stage summary for long recordings.
 *
 * Stage 1 (map): every clip gets structured notes, a few clips per request, so each request is
 * small and the model never has to compress an hour of talk at once. Notes are cached per clip.
 * Stage 2 (reduce): the model writes only the evaluation from the notes; the per-question details
 * are appended by the app from the notes, so no question/answer can be dropped.
 */
object ClipNotes {
    /** Transcript characters per notes request (~2–3k tokens): small enough to be covered in detail. */
    const val MAX_BATCH_CHARS = 8_000
    private val HEADER = Regex("""^\s*={2,}\s*Đoạn\s+(\d+)\s*={2,}\s*$""", RegexOption.MULTILINE)

    fun hasSpeech(clip: ClipText): Boolean {
        val t = clip.text?.trim()
        return !t.isNullOrEmpty() && t != Prompts.NO_SPEECH && t != Prompts.INTERVIEWER_ONLY
    }

    /** Groups consecutive clips into requests of at most [maxChars] transcript characters. */
    fun batches(clips: List<ClipText>, maxChars: Int = MAX_BATCH_CHARS): List<List<ClipText>> {
        val out = mutableListOf<MutableList<ClipText>>()
        var size = 0
        for (clip in clips.sortedBy { it.startMs }) {
            val len = clip.text?.length ?: 0
            if (out.isEmpty() || size + len > maxChars) {
                out += mutableListOf(clip)
                size = len
            } else {
                out.last() += clip
                size += len
            }
        }
        return out
    }

    fun system(language: OutputLanguage): String =
        "Bạn là trợ lý ghi chép. Viết ghi chú ĐẦY ĐỦ cho từng đoạn hội thoại, không bỏ sót đoạn nào, không gộp đoạn, " +
            "không bịa. Giữ nguyên số liệu, tên công nghệ, tên dự án, công ty, con số và ví dụ cụ thể. " +
            "Transcript do máy nhận dạng nên có thể sai chính tả — suy luận hợp lý theo ngữ cảnh. ${language.instruction}"

    fun userMessage(mode: SessionMode, interviewer: InterviewerSpeech, clips: List<ClipText>): String = buildString {
        appendLine("Với MỖI đoạn bên dưới, viết ghi chú theo đúng định dạng sau (giữ nguyên dòng tiêu đề === Đoạn N ===):")
        appendLine()
        appendLine("=== Đoạn N ===")
        when (mode) {
            SessionMode.INTERVIEW -> {
                appendLine("Câu hỏi: <câu hỏi của người phỏng vấn, viết lại rõ ràng; nhiều câu hỏi thì liệt kê đủ>")
                appendLine("Trả lời:")
                appendLine("- <từng ý của ứng viên, kèm chi tiết cụ thể>")
                appendLine("Nhận xét nhanh: <điểm tốt / điểm đáng lưu ý trong câu trả lời, 1 câu>")
                if (interviewer == InterviewerSpeech.DROP) {
                    appendLine("(Lời người phỏng vấn đã bị lược bỏ: suy ra câu hỏi từ tiêu đề đoạn và nội dung trả lời.)")
                }
            }
            SessionMode.MEETING -> {
                appendLine("Chủ đề: <chủ đề chính>")
                appendLine("Nội dung:")
                appendLine("- <từng ý quan trọng, ai nói gì>")
                appendLine("Quyết định: <nếu có, nếu không ghi 'Không'>")
                appendLine("Việc cần làm: <việc – người phụ trách – hạn, nếu có>")
            }
            SessionMode.CUSTOM -> {
                appendLine("Nội dung:")
                appendLine("- <từng ý quan trọng, kèm chi tiết cụ thể>")
            }
        }
        appendLine()
        appendLine("Có ${clips.size} đoạn: ${clips.joinToString(", ") { it.number.toString() }}. Phải có đủ ${clips.size} ghi chú.")
        appendLine()
        for (c in clips) {
            appendLine("<<< Đoạn ${c.number}" + (c.title?.let { " — $it" } ?: ""))
            appendLine(c.text?.trim().orEmpty())
            appendLine(">>>")
        }
    }.trim()

    /** Notes by clip number, for the [expected] clips only. Missing clips are simply absent. */
    fun parse(raw: String, expected: Collection<Int>): Map<Int, String> {
        val matches = HEADER.findAll(raw).toList()
        val out = mutableMapOf<Int, String>()
        matches.forEachIndexed { i, m ->
            val number = m.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            if (number !in expected || number in out) return@forEachIndexed
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            val body = raw.substring(m.range.last + 1, end).trim()
            if (body.isNotEmpty()) out[number] = body
        }
        return out
    }

    /** Used only when the model returns no notes for a clip twice: keep the words instead of losing them. */
    fun fallback(clip: ClipText, maxChars: Int = 1_500): String {
        val text = clip.text?.trim().orEmpty()
        val cut = if (text.length > maxChars) text.take(maxChars) + "…" else text
        return "(Không tạo được ghi chú tự động — trích transcript)\n$cut"
    }
}

object SummaryComposer {
    const val DETAILS_INTERVIEW = "## Chi tiết từng câu hỏi"
    const val DETAILS_MEETING = "## Chi tiết theo đoạn"

    fun system(language: OutputLanguage): String =
        "Bạn là trợ lý ghi chép chuyên nghiệp. Chỉ dựa trên ghi chú được cung cấp; không bịa thông tin. " +
            "Nếu không đủ thông tin để kết luận, ghi rõ 'Chưa đủ thông tin'. Trình bày bằng Markdown gọn gàng, " +
            "dễ đọc trên điện thoại. ${language.instruction}"

    /** Request for the evaluation part only; per-clip details are appended by [compose]. */
    fun synthesisMessage(
        mode: SessionMode,
        customPrompt: String,
        title: String,
        durationMs: Long,
        bookmarksMs: List<Long>,
        notes: List<Pair<ClipText, String>>,
    ): String = buildString {
        when (mode) {
            SessionMode.INTERVIEW -> {
                appendLine("Viết phần ĐÁNH GIÁ buổi phỏng vấn với các mục:")
                appendLine("## Tổng quan\nVị trí, người tham gia, nhận định chung trong 2–3 câu.")
                appendLine("## Điểm mạnh\n## Điểm cần lưu ý / rủi ro")
                appendLine("## Đánh giá kỹ năng\nChuyên môn, giao tiếp, ngoại ngữ (nếu có), thái độ.")
                appendLine("## Câu hỏi của ứng viên\n## Đề xuất bước tiếp theo")
                appendLine()
                appendLine(
                    "KHÔNG liệt kê lại từng câu hỏi (app sẽ tự đính kèm chi tiết từng câu). " +
                        "Mỗi nhận định ghi nguồn dạng (Đoạn N). Xét TẤT CẢ ${notes.size} đoạn, kể cả các đoạn ở giữa và cuối.",
                )
            }
            SessionMode.MEETING -> {
                appendLine("Viết biên bản cuộc họp với các mục:")
                appendLine("## Tóm tắt\n3–5 gạch đầu dòng quan trọng nhất.")
                appendLine("## Quyết định đã chốt")
                appendLine("## Việc cần làm\nDạng bảng: | Việc | Người phụ trách | Hạn | (ghi \"?\" nếu không rõ).")
                appendLine("## Vấn đề còn mở")
                appendLine()
                appendLine(
                    "KHÔNG liệt kê lại chi tiết từng đoạn (app sẽ tự đính kèm). Ghi nguồn dạng (Đoạn N). " +
                        "Xét TẤT CẢ ${notes.size} đoạn.",
                )
            }
            SessionMode.CUSTOM -> {
                appendLine(customPrompt.ifBlank { "Tóm tắt nội dung chính, các quyết định và việc cần làm." })
                appendLine()
                appendLine("Dùng thông tin từ TẤT CẢ ${notes.size} đoạn.")
            }
        }
        if (bookmarksMs.isNotEmpty()) {
            appendLine(
                "Người dùng đã đánh dấu mốc quan trọng tại: " + bookmarksMs.joinToString(", ") { TimeFormat.clock(it) } +
                    ". Thêm mục '## Điểm được đánh dấu' nêu nội dung các đoạn chứa các mốc này.",
            )
        }
        appendLine()
        appendLine("Tiêu đề: $title")
        appendLine("Thời lượng: ${TimeFormat.clock(durationMs)}")
        appendLine()
        appendLine("GHI CHÚ TỪNG ĐOẠN:")
        for ((clip, note) in notes) {
            appendLine()
            appendLine(header(clip, bookmarksMs))
            appendLine(note.trim())
        }
    }.trim()

    /** Final summary: the model's evaluation, then the app-built details for every clip. */
    fun compose(mode: SessionMode, synthesis: String, notes: List<Pair<ClipText, String>>): String {
        val details = when (mode) {
            SessionMode.INTERVIEW -> DETAILS_INTERVIEW
            SessionMode.MEETING -> DETAILS_MEETING
            SessionMode.CUSTOM -> return synthesis.trim()
        }
        return buildString {
            appendLine(synthesis.trim())
            appendLine()
            appendLine(details)
            for ((clip, note) in notes) {
                appendLine()
                appendLine("### Đoạn ${clip.number}" + (clip.title?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""))
                appendLine(note.trim())
            }
        }.trim()
    }

    private fun header(clip: ClipText, bookmarksMs: List<Long>): String {
        val marks = bookmarksMs.filter { it >= clip.startMs && it <= clip.endMs }
        return "=== Đoạn ${clip.number} · ${TimeFormat.clock(clip.startMs)}–${TimeFormat.clock(clip.endMs)}" +
            (clip.title?.let { " · $it" } ?: "") +
            (if (marks.isNotEmpty()) " · ⭐ " + marks.joinToString(", ") { TimeFormat.clock(it) } else "") + " ==="
    }
}
