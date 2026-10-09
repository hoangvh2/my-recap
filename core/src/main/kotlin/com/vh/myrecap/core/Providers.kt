package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Gemini `generateContent` client. One API key covers transcription (audio sent inline, so no
 * upload step) and summarisation. Chunks stay well under the 20 MB inline request limit
 * (10 min of 32 kbps AAC ≈ 2.4 MB).
 */
class GeminiClient(
    private val config: ProviderConfig,
    private val http: HttpClient = HttpClient(),
) : SpeechToText, TextGenerator {

    override fun transcribe(request: SttRequest): String {
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "text",
                    Prompts.transcriptionInstruction(request.mode, request.previousTail, request.interviewer, request.titleLanguage),
                ),
            )
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", request.clip.mimeType)
                        .put("data", Base64.getEncoder().encodeToString(request.clip.bytes)),
                ),
            )
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        return call(body).trim()
    }

    override fun generate(system: String, user: String): String {
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
        return call(body).trim()
    }

    /** Returns the model ids the key can use, for the Settings "test connection" button. */
    fun listModels(): List<String> {
        val res = http.get("${config.baseUrl}/models?pageSize=1000", headers()).requireSuccess(SERVICE)
        return parseModelList(res.body)
    }

    private fun call(body: JSONObject): String {
        val url = "${config.baseUrl}/models/${config.model}:generateContent"
        val res = http.postJson(url, headers(), body.toString()).requireSuccess(SERVICE)
        return parseGenerateResponse(res.body)
    }

    private fun headers() = mapOf("x-goog-api-key" to config.apiKey)

    companion object {
        private const val SERVICE = "Gemini"

        fun parseGenerateResponse(json: String): String {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw ApiException("$SERVICE: phản hồi không đọc được", retryable = true, cause = e)
            }
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val reason = root.optJSONObject("promptFeedback")?.optString("blockReason", "").orEmpty()
                throw ApiException(
                    "$SERVICE: không có kết quả${if (reason.isNotEmpty()) " (bị chặn: $reason)" else ""}",
                    retryable = reason.isEmpty(),
                )
            }
            val first = candidates.getJSONObject(0)
            val parts = first.optJSONObject("content")?.optJSONArray("parts")
            val text = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.optBoolean("thought", false)) continue
                    text.append(p.optString("text", ""))
                }
            }
            val finish = first.optString("finishReason", "")
            if (text.isBlank()) {
                throw ApiException(
                    "$SERVICE: kết quả rỗng${if (finish.isNotEmpty()) " (finishReason=$finish)" else ""}",
                    retryable = finish.isEmpty() || finish == "OTHER",
                    emptyResult = true,
                )
            }
            if (finish == "MAX_TOKENS") text.append("\n\n[…bị cắt do vượt giới hạn độ dài đầu ra]")
            return text.toString()
        }

        fun parseModelList(json: String): List<String> {
            val arr = JSONObject(json).optJSONArray("models") ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it).optString("name", "").removePrefix("models/") }
                .filter { it.isNotEmpty() }
        }
    }
}

/** Whisper-style `/audio/transcriptions` endpoint (Groq, OpenAI, and compatible servers). */
class OpenAiTranscriber(
    private val config: ProviderConfig,
    private val http: HttpClient = HttpClient(),
    /** ISO-639-1 code, or blank to let the model detect the language. */
    private val language: String = "",
) : SpeechToText {
    override fun transcribe(request: SttRequest): String {
        val parts = mutableListOf(
            FormPart("model", value = config.model),
            FormPart("response_format", value = "json"),
            FormPart("file", bytes = request.clip.bytes, fileName = request.clip.fileName, contentType = request.clip.mimeType),
        )
        if (language.isNotBlank()) parts += FormPart("language", value = language)
        val prompt = Prompts.whisperPrompt(request.previousTail)
        if (prompt.isNotBlank()) parts += FormPart("prompt", value = prompt)
        val res = http.postMultipart("${config.baseUrl}/audio/transcriptions", authHeaders(config), parts)
            .requireSuccess(SERVICE)
        val text = try {
            JSONObject(res.body).optString("text", "")
        } catch (e: Exception) {
            throw ApiException("$SERVICE: phản hồi không đọc được", retryable = true, cause = e)
        }
        return text.trim().ifEmpty { Prompts.NO_SPEECH }
    }

    companion object {
        private const val SERVICE = "STT"
    }
}

/** `/chat/completions` endpoint for summaries (Groq, OpenAI, OpenRouter, DeepSeek, Ollama…). */
class OpenAiChat(
    private val config: ProviderConfig,
    private val http: HttpClient = HttpClient(),
) : TextGenerator {
    override fun generate(system: String, user: String): String {
        val body = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val res = http.postJson("${config.baseUrl}/chat/completions", authHeaders(config), body.toString())
            .requireSuccess(SERVICE)
        return parseChatResponse(res.body)
    }

    fun listModels(): List<String> {
        val res = http.get("${config.baseUrl}/models", authHeaders(config)).requireSuccess(SERVICE)
        val arr = JSONObject(res.body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id", "") }.filter { it.isNotEmpty() }
    }

    companion object {
        private const val SERVICE = "AI"

        fun parseChatResponse(json: String): String {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw ApiException("$SERVICE: phản hồi không đọc được", retryable = true, cause = e)
            }
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) throw ApiException("$SERVICE: không có kết quả", retryable = true)
            val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content", "").orEmpty()
            if (content.isBlank()) throw ApiException("$SERVICE: kết quả rỗng", retryable = true, emptyResult = true)
            return content.trim()
        }
    }
}

private fun authHeaders(config: ProviderConfig) = mapOf("Authorization" to "Bearer ${config.apiKey}")

object Providers {
    fun speechToText(config: ProviderConfig, language: String = ""): SpeechToText = when (config.kind) {
        ProviderKind.GEMINI -> GeminiClient(config)
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiTranscriber(config, language = language)
    }

    fun textGenerator(config: ProviderConfig): TextGenerator = when (config.kind) {
        ProviderKind.GEMINI -> GeminiClient(config)
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiChat(config)
    }

    /** Lists models to prove the URL and key work. */
    fun testConnection(config: ProviderConfig): List<String> = when (config.kind) {
        ProviderKind.GEMINI -> GeminiClient(config).listModels()
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiChat(config).listModels()
    }
}
