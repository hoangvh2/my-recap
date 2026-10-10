package com.vh.myrecap.core

object Prompts {
    const val NO_SPEECH = "[không có lời nói]"
    const val INTERVIEWER_ONLY = "[chỉ có người phỏng vấn]"
    private const val TITLE_PREFIX = "# "
    private const val TAIL_CHARS = 600

    fun transcriptionInstruction(
        mode: SessionMode,
        previousTail: String,
        interviewer: InterviewerSpeech = InterviewerSpeech.KEEP,
        titleLanguage: OutputLanguage = OutputLanguage.VI,
    ): String = buildString {
        appendLine("Transcribe this audio recording verbatim.")
        appendLine(
            "The speakers mainly use Vietnamese, English and sometimes Japanese, and may switch language " +
                "mid-sentence. Write every word in the language and script it was spoken in (Vietnamese with full " +
                "diacritics, Japanese in kana/kanji). Never translate.",
        )
        when (mode) {
            SessionMode.INTERVIEW -> {
                appendLine(
                    "This is a job interview, usually between an interviewer and a candidate. Start each speaker turn " +
                        "on a new line with a label: 'Người phỏng vấn:' or 'Ứng viên:' when the role is clear from " +
                        "context, otherwise 'Người nói 1:', 'Người nói 2:' and so on.",
                )
                when (interviewer) {
                    InterviewerSpeech.KEEP -> Unit
                    InterviewerSpeech.CONDENSE -> appendLine(
                        "Do not transcribe the interviewer verbatim: write each interviewer turn as one line " +
                            "'Người phỏng vấn: <the question or point, at most 20 words>'. Transcribe the candidate verbatim.",
                    )
                    InterviewerSpeech.DROP -> appendLine(
                        "Leave out everything the interviewer says; transcribe only the candidate, verbatim. " +
                            "If only the interviewer speaks in this audio, output exactly: $INTERVIEWER_ONLY",
                    )
                }
            }
            SessionMode.MEMO -> appendLine(
                "This is a short personal voice note, usually one speaker. Do not add speaker labels.",
            )
            SessionMode.MEETING, SessionMode.CUSTOM -> appendLine(
                "This is a meeting. Start each speaker turn on a new line with a label 'Người nói 1:', " +
                    "'Người nói 2:' and so on. If a speaker's name is clearly stated, use 'Name:' instead.",
            )
        }
        appendLine("Remove filler sounds (ừm, à, uh) but keep all meaningful content. Mark inaudible words as [không rõ].")
        appendLine(
            "First line: '$TITLE_PREFIX' followed by a topic title of at most 8 words for this excerpt " +
                "(for an interview, the question being answered), written in ${titleLanguageName(titleLanguage)}.",
        )
        appendLine("Then the transcript only: no timestamps, no commentary, no summary.")
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

    /** Splits model output into the optional `# title` first line and the transcript body. */
    fun parseClipTranscript(raw: String): ClipTranscript {
        val text = raw.trim()
        val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
        if (!firstLine.startsWith("#")) return ClipTranscript(null, text)
        val title = firstLine.trimStart('#').trim().trim('*', '"').take(80).ifBlank { null }
        val body = text.substringAfter('\n', "").trim()
        return ClipTranscript(title, body.ifEmpty { NO_SPEECH })
    }

    private fun titleLanguageName(language: OutputLanguage) = when (language) {
        OutputLanguage.VI -> "Vietnamese"
        OutputLanguage.EN -> "English"
        OutputLanguage.JA -> "Japanese"
        OutputLanguage.SAME -> "the language mainly spoken"
    }

    /** Whisper's `prompt` only conditions vocabulary/style; keep it short (Whisper uses ~224 tokens). */
    fun whisperPrompt(previousTail: String): String = tail(previousTail, 300)

    private fun tail(text: String, max: Int = TAIL_CHARS): String {
        val t = text.trim()
        if (t.isEmpty() || t == NO_SPEECH || t == INTERVIEWER_ONLY) return ""
        if (t.length <= max) return t
        // Cut on a line boundary when possible so the speaker label stays attached.
        val cut = t.substring(t.length - max)
        val nl = cut.indexOf('\n')
        return if (nl in 0 until max / 2) cut.substring(nl + 1) else cut
    }
}
