package com.vh.myrecap.core

/** What kind of conversation was recorded; drives the transcription hint and the summary template. */
enum class SessionMode(val label: String) {
    INTERVIEW("Phỏng vấn"),
    MEETING("Cuộc họp"),
    CUSTOM("Tự do"),
}

enum class OutputLanguage(val label: String, val instruction: String) {
    VI("Tiếng Việt", "Viết toàn bộ kết quả bằng tiếng Việt."),
    EN("English", "Write the entire output in English."),
    JA("日本語", "出力はすべて日本語で書いてください。"),
    SAME("Theo ngôn ngữ cuộc hội thoại", "Write the output in the main language spoken in the transcript."),
}

enum class ProviderKind(val label: String) {
    GEMINI("Google Gemini"),
    OPENAI_COMPATIBLE("OpenAI-compatible (Groq, OpenAI, OpenRouter…)"),
}

/** Connection settings for one provider. [baseUrl] has no trailing slash. */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isComplete: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val GROQ_BASE_URL = "https://api.groq.com/openai/v1"
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"

        /** Cheapest Gemini model with audio input at the time of writing; user-editable in Settings. */
        const val GEMINI_DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val GROQ_STT_MODEL = "whisper-large-v3-turbo"
        const val GROQ_CHAT_MODEL = "llama-3.3-70b-versatile"
    }
}

/** One audio chunk to transcribe. */
class AudioClip(val bytes: ByteArray, val mimeType: String, val fileName: String)

class SttRequest(
    val clip: AudioClip,
    val mode: SessionMode,
    /** Tail of the previous chunk's transcript so speaker labels and wording stay consistent. */
    val previousTail: String = "",
)

interface SpeechToText {
    fun transcribe(request: SttRequest): String
}

interface TextGenerator {
    fun generate(system: String, user: String): String
}

/** Text of one recorded segment, positioned on the recording timeline (pauses excluded). */
data class SegmentText(val startMs: Long, val durationMs: Long, val text: String?)
