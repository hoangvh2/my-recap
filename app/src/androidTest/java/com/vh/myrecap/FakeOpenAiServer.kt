package com.vh.myrecap

import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server answering `/chat/completions` like an OpenAI-compatible API, so the real
 * worker and providers run end to end without network access. [answer] gets the user message.
 */
class FakeOpenAiServer(private val answer: (String) -> String) : AutoCloseable {
    private val server = ServerSocket(0)
    val requests = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                try {
                    socket.use { s ->
                        val input = s.getInputStream()
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
                            JSONArray().put(JSONObject().put("message", JSONObject().put("content", answer(user)))),
                        ).toString().toByteArray(Charsets.UTF_8)
                        val out = s.getOutputStream()
                        out.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        out.write(reply)
                        out.flush()
                    }
                } catch (e: Exception) {
                    errors += e.toString()
                }
            }
        }
    }

    override fun close() = server.close()
}
