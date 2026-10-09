package com.vh.myrecap.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/**
 * Error from a remote AI/STT service.
 *
 * [retryable] separates transient failures (network, 429, 5xx) that a background job should retry
 * from permanent ones (bad key, bad model, bad request) that need the user to fix settings.
 */
class ApiException(
    message: String,
    val httpCode: Int? = null,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

class HttpResponse(val code: Int, val body: String)

/** Part of a multipart/form-data request. Exactly one of [value] or [bytes] is set. */
class FormPart(
    val name: String,
    val value: String? = null,
    val bytes: ByteArray? = null,
    val fileName: String? = null,
    val contentType: String? = null,
)

/**
 * Minimal blocking HTTP client on [HttpURLConnection], so the same code runs on Android and on the
 * JVM in unit tests without extra dependencies. Callers run it off the main thread.
 */
open class HttpClient(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 5 * 60_000,
) {
    open fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        execute(url, "GET", headers, null, null)

    open fun postJson(url: String, headers: Map<String, String>, json: String): HttpResponse =
        execute(url, "POST", headers, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

    open fun postMultipart(url: String, headers: Map<String, String>, parts: List<FormPart>): HttpResponse {
        val boundary = "----myrecap" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        for (p in parts) {
            out.write("--$boundary\r\n".toByteArray())
            if (p.bytes != null) {
                out.write(
                    "Content-Disposition: form-data; name=\"${p.name}\"; filename=\"${p.fileName ?: "file"}\"\r\n"
                        .toByteArray(Charsets.UTF_8),
                )
                out.write("Content-Type: ${p.contentType ?: "application/octet-stream"}\r\n\r\n".toByteArray())
                out.write(p.bytes)
            } else {
                out.write("Content-Disposition: form-data; name=\"${p.name}\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write((p.value ?: "").toByteArray(Charsets.UTF_8))
            }
            out.write("\r\n".toByteArray())
        }
        out.write("--$boundary--\r\n".toByteArray())
        return execute(url, "POST", headers, "multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        contentType: String?,
        body: ByteArray?,
    ): HttpResponse {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException("URL không hợp lệ: $url", retryable = false, cause = e)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(code, text)
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw ApiException("Hết thời gian chờ phản hồi từ máy chủ", retryable = true, cause = e)
        } catch (e: IOException) {
            throw ApiException("Lỗi mạng: ${e.message ?: e.javaClass.simpleName}", retryable = true, cause = e)
        } finally {
            conn.disconnect()
        }
    }
}

/** Throws [ApiException] for non-2xx responses, classifying whether a retry can help. */
internal fun HttpResponse.requireSuccess(service: String): HttpResponse {
    if (code in 200..299) return this
    val detail = extractErrorMessage(body)
    val retryable = code == 408 || code == 429 || code >= 500
    val hint = when (code) {
        400 -> "Yêu cầu không hợp lệ (kiểm tra tên model)"
        401, 403 -> "API key không hợp lệ hoặc không có quyền"
        404 -> "Không tìm thấy endpoint/model (kiểm tra Base URL và tên model)"
        413 -> "File âm thanh quá lớn"
        429 -> "Vượt giới hạn tần suất/quota"
        else -> if (code >= 500) "Máy chủ đang lỗi" else "Lỗi HTTP $code"
    }
    throw ApiException("$service: $hint (HTTP $code)${if (detail.isNotBlank()) " – $detail" else ""}", code, retryable)
}

private fun extractErrorMessage(body: String): String {
    val msg = try {
        val o = org.json.JSONObject(body)
        val err = o.opt("error")
        when (err) {
            is org.json.JSONObject -> err.optString("message", "")
            is String -> err
            else -> o.optString("message", "")
        }
    } catch (_: Exception) {
        body
    }
    return msg.trim().take(300)
}
