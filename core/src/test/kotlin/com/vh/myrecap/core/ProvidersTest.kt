package com.vh.myrecap.core

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProvidersTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Recorded>()
    private var nextStatus = 200
    private var nextBody = "{}"

    class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            requests += Recorded(ex.requestMethod, ex.requestURI.toString(), headers, ex.requestBody.readBytes())
            val bytes = nextBody.toByteArray()
            ex.sendResponseHeaders(nextStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun gemini() = GeminiClient(ProviderConfig(ProviderKind.GEMINI, baseUrl, "g-key", "gemini-x"))

    @Test
    fun geminiTranscribeSendsInlineAudioAndParsesText() {
        nextBody = """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"Người nói 1: Xin chào\n"}]},"finishReason":"STOP"}]}"""
        val audio = byteArrayOf(1, 2, 3, 4)
        val text = gemini().transcribe(SttRequest(AudioClip(audio, "audio/aac", "a.aac"), SessionMode.INTERVIEW, "Ứng viên: trước đó"))

        assertEquals("Người nói 1: Xin chào", text)
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("/models/gemini-x:generateContent", req.path)
        assertEquals("g-key", req.headers["x-goog-api-key"])
        val parts = JSONObject(String(req.body)).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val prompt = parts.getJSONObject(0).getString("text")
        assertTrue("Ứng viên:" in prompt && "trước đó" in prompt, prompt)
        val inline = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("audio/aac", inline.getString("mime_type"))
        assertTrue(Base64.getDecoder().decode(inline.getString("data")).contentEquals(audio))
    }

    @Test
    fun geminiGenerateUsesSystemInstruction() {
        nextBody = """{"candidates":[{"content":{"parts":[{"text":"## Tóm tắt"}]}}]}"""
        assertEquals("## Tóm tắt", gemini().generate("SYS", "USER"))
        val body = JSONObject(String(requests.single().body))
        assertEquals("SYS", body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun authErrorIsNotRetryable() {
        nextStatus = 403
        nextBody = """{"error":{"code":403,"message":"API key not valid"}}"""
        val e = assertFailsWith<ApiException> { gemini().generate("s", "u") }
        assertFalse(e.retryable)
        assertEquals(403, e.httpCode)
        assertTrue("API key not valid" in e.message!!, e.message)
    }

    @Test
    fun rateLimitAndServerErrorsAreRetryable() {
        nextStatus = 429
        assertTrue(assertFailsWith<ApiException> { gemini().generate("s", "u") }.retryable)
        nextStatus = 503
        assertTrue(assertFailsWith<ApiException> { gemini().generate("s", "u") }.retryable)
    }

    @Test
    fun networkFailureIsRetryable() {
        val dead = GeminiClient(ProviderConfig(ProviderKind.GEMINI, "http://127.0.0.1:1", "k", "m"))
        assertTrue(assertFailsWith<ApiException> { dead.generate("s", "u") }.retryable)
    }

    @Test
    fun blockedPromptIsNotRetryable() {
        val e = assertFailsWith<ApiException> {
            GeminiClient.parseGenerateResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        }
        assertFalse(e.retryable)
    }

    @Test
    fun maxTokensIsFlagged() {
        val text = GeminiClient.parseGenerateResponse("""{"candidates":[{"content":{"parts":[{"text":"abc"}]},"finishReason":"MAX_TOKENS"}]}""")
        assertTrue(text.startsWith("abc") && "bị cắt" in text)
    }

    @Test
    fun geminiListModelsStripsPrefix() {
        nextBody = """{"models":[{"name":"models/gemini-3.5-flash-lite"},{"name":"models/gemini-3.5-flash"}]}"""
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.5-flash"), gemini().listModels())
    }

    @Test
    fun whisperMultipartContainsModelFileAndPrompt() {
        nextBody = """{"text":" Xin chào các bạn "}"""
        val stt = OpenAiTranscriber(ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, baseUrl, "o-key", "whisper-large-v3-turbo"), language = "vi")
        val text = stt.transcribe(SttRequest(AudioClip(byteArrayOf(9, 9), "audio/mp4", "seg.m4a"), SessionMode.MEETING, "câu trước"))

        assertEquals("Xin chào các bạn", text)
        val req = requests.single()
        assertEquals("/audio/transcriptions", req.path)
        assertEquals("Bearer o-key", req.headers["authorization"])
        assertTrue(req.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        val body = String(req.body, Charsets.UTF_8)
        assertTrue("name=\"model\"\r\n\r\nwhisper-large-v3-turbo" in body)
        assertTrue("name=\"file\"; filename=\"seg.m4a\"" in body)
        assertTrue("name=\"language\"\r\n\r\nvi" in body)
        assertTrue("câu trước" in body)
    }

    @Test
    fun whisperEmptyTextBecomesNoSpeechMarker() {
        nextBody = """{"text":""}"""
        val stt = OpenAiTranscriber(ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, baseUrl, "k", "m"))
        assertEquals(Prompts.NO_SPEECH, stt.transcribe(SttRequest(AudioClip(byteArrayOf(1), "audio/mp4", "a.m4a"), SessionMode.MEETING)))
    }

    @Test
    fun chatCompletionParsesContent() {
        nextBody = """{"choices":[{"message":{"role":"assistant","content":"## Biên bản"}}]}"""
        val chat = OpenAiChat(ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, baseUrl, "k", "llama"))
        assertEquals("## Biên bản", chat.generate("sys", "user"))
        val body = JSONObject(String(requests.single().body))
        assertEquals("llama", body.getString("model"))
        assertEquals(2, body.getJSONArray("messages").length())
    }
}
