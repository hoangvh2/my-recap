package com.vh.myrecap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.vh.myrecap.core.ProviderKind
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.SummaryComposer
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.data.SummaryJob
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.work.ProcessWorker
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A long interview (25 clips) summarised by the real worker against a fake OpenAI-compatible
 * server that, like a real model under load, skips one clip. The summary must still contain
 * every clip's question/answer.
 */
@RunWith(AndroidJUnit4::class)
class LongSummaryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = MyRecapApp.from(context)
    private lateinit var server: ServerSocket
    private val requests = CopyOnWriteArrayList<String>()
    private val serverErrors = CopyOnWriteArrayList<String>()

    /** Minimal HTTP/1.1 server answering /chat/completions like an OpenAI-compatible API. */
    @Before
    fun startServer() {
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                try { socket.use { s ->
                    val input = s.getInputStream()
                    // Headers end at CRLFCRLF; then read exactly Content-Length bytes.
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b < 0) break
                        head.append(b.toChar())
                    }
                    val length = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }
                        ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(bytes, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val body = String(bytes, 0, read, Charsets.UTF_8)
                    val user = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")
                    requests += user
                    val reply = JSONObject().put(
                        "choices",
                        org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer(user)))),
                    ).toString().toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    out.write(reply)
                    out.flush()
                } } catch (e: Exception) {
                    serverErrors += e.toString()
                }
            }
        }
    }

    @After
    fun stopServer() = server.close()

    private fun answer(user: String): String {
        if ("GHI CHÚ TỪNG ĐOẠN" in user) return "## Tổng quan\nỨng viên phù hợp (Đoạn 3)."
        val clips = Regex("""<<< Đoạn (\d+)""").findAll(user).map { it.groupValues[1].toInt() }.toList()
        // Clip 7 is always "forgotten", in batches and when asked alone.
        return clips.filter { it != 7 }.joinToString("\n\n") { "=== Đoạn $it ===\nCâu hỏi: Q$it\nTrả lời:\n- A$it" }
    }

    @Test
    fun everyClipSurvivesInALongInterviewSummary() = runBlocking {
        app.settings.update {
            it.copy(
                summaryProvider = ProviderKind.OPENAI_COMPATIBLE,
                openAiBaseUrl = "http://127.0.0.1:${server.localPort}",
                openAiKey = "test-key",
                openAiChatModel = "fake",
            )
        }
        val store = app.store
        val folder = store.create(SessionMode.INTERVIEW, "PV dài")
        val count = 25
        store.update(folder.id) { f ->
            f.copy(
                state = RecState.STOPPED,
                segments = (0 until count).map { i ->
                    Segment(i, SessionStore.segmentFileName(i), i * 120_000L, 100_000, TaskStatus.DONE, title = "Chủ đề ${i + 1}")
                },
                summaries = listOf(SummaryJob("long", System.currentTimeMillis(), SessionMode.INTERVIEW, (0 until count).toList(), TaskStatus.PENDING)),
            )
        }
        repeat(count) { i ->
            store.writeTranscript(folder.id, i, "Người phỏng vấn: Câu hỏi ${i + 1}?\nỨng viên: " + "Trả lời chi tiết. ".repeat(60))
        }

        val worker = TestListenableWorkerBuilder<ProcessWorker>(context)
            .setInputData(workDataOf(ProcessWorker.KEY_ID to folder.id))
            .build()
        val result = worker.doWork()

        val state = store.get(folder.id)!!
        assertEquals(
            "worker result; job error=${state.summaries.single().error}; folder error=${state.error}; " +
                "server errors=$serverErrors; requests=${requests.size}",
            ListenableWorker.Result.success(),
            result,
        )
        val job = store.get(folder.id)!!.summaries.single()
        assertEquals("job error: ${job.error}", TaskStatus.DONE, job.status)
        val summary = store.readSummary(folder.id, "long")!!
        assertTrue(summary.startsWith("## Tổng quan"))
        assertTrue(SummaryComposer.DETAILS_INTERVIEW in summary)
        for (n in 1..count) assertTrue("clip $n missing from summary", "### Đoạn $n — Chủ đề $n" in summary)
        assertTrue("skipped clip falls back to its transcript", "trích transcript" in summary.substringAfter("### Đoạn 7"))
        // ~25k characters in batches of ≤8k: a handful of notes requests, one retry for clip 7, one synthesis.
        assertTrue("requests ${requests.size}", requests.size in 4..8)
        assertTrue("synthesis sees all clips", requests.last().contains("TẤT CẢ $count đoạn"))
        store.delete(folder.id)
    }
}
