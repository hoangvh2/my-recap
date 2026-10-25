package com.vh.myrecap.core

import org.json.JSONObject

/**
 * "Tóm tắt bằng AI" on a note: only when the person taps it, only this note's own text goes to the model, and the
 * answer is a suggestion they accept or drop. It fills what is still empty and never replaces what they wrote.
 */
object NoteAi {
    /** Characters of the note sent to the model; the rest is cut. */
    const val MAX_INPUT = 6_000
    const val MAX_SUMMARY = 600

    /** What the model made of the note. Every part may be missing. */
    data class Suggestion(
        val kind: NoteKind?,
        val summary: String,
        val place: String?,
        val attrs: Map<String, String>,
    ) {
        val isEmpty: Boolean get() = kind == null && summary.isEmpty() && place == null && attrs.isEmpty()
    }

    fun system(): String =
        "Bạn là trợ lý ghi chú cá nhân. Đọc nội dung một ghi chú (có thể là bài đăng, mẹo, review, địa chỉ quán) và " +
            "trích ý chính. Chỉ dùng thông tin có trong ghi chú, không bịa, không thêm kiến thức ngoài. " +
            "Trả lời DUY NHẤT một JSON hợp lệ, không markdown, không giải thích."

    /** The text of [item] the model sees: title, body, place and link, cut to [MAX_INPUT]. */
    fun input(item: Item): String = listOfNotNull(
        "Tiêu đề: ${item.title}".takeIf { item.title.isNotBlank() },
        item.place?.takeIf { it.isNotBlank() }?.let { "Địa chỉ: $it" },
        item.url?.takeIf { it.isNotBlank() }?.let { "Link: $it" },
        item.details.takeIf { it.isNotBlank() },
    ).joinToString("\n").take(MAX_INPUT)

    fun userMessage(item: Item): String = buildString {
        appendLine("Loại ghi chú (kind) là một trong:")
        NoteKind.entries.forEach { k ->
            val values = k.fields.filter { !it.flag }.joinToString(", ") { "${it.key} (${it.label})" }
            appendLine("- ${k.key}: ${k.label}. Giá trị có thể điền: $values.")
        }
        appendLine("- null: ghi chú thường, hoặc không chắc.")
        appendLine()
        appendLine("Yêu cầu:")
        appendLine("- summary: 1–3 câu tiếng Việt nêu ý chính hoặc điều cần nhớ, ≤ $MAX_SUMMARY ký tự. Chuỗi rỗng nếu ghi chú quá ngắn để tóm tắt.")
        appendLine("- place: địa chỉ đầy đủ nếu ghi chú nêu rõ, nếu không thì null.")
        appendLine("- values: chỉ các giá trị được nói rõ trong ghi chú (ví dụ món đặc trưng, giá, kênh); khoá không có thì bỏ qua. Không đoán.")
        appendLine("- kind: chỉ chọn khi ghi chú rõ ràng thuộc loại đó.")
        appendLine()
        appendLine("""Định dạng: {"kind":"place|read|video|null","summary":"…","place":"… hoặc null","values":{"dish":"…"}}""")
        appendLine()
        appendLine("Ghi chú:")
        append(input(item))
    }

    /** Reads the model's answer; null when no JSON can be found. A suggestion with nothing in it is also null. */
    fun parse(raw: String): Suggestion? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        val kind = NoteKind.fromKey(o.optStringOrNull("kind")?.lowercase())
        val values = o.optJSONObject("values")?.let { v ->
            v.keys().asSequence().mapNotNull { k -> v.optStringOrNull(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        }.orEmpty()
        val known = (kind?.fields ?: NoteKind.entries.flatMap { it.fields }).filter { !it.flag }.map { it.key }.toSet()
        return Suggestion(
            kind = kind,
            summary = o.optStringOrNull("summary")?.trim().orEmpty().take(MAX_SUMMARY),
            place = o.optStringOrNull("place")?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
            attrs = values.filterKeys { it in known }.mapValues { it.value.take(NoteKinds.MAX_ATTR_VALUE) },
        ).takeUnless { it.isEmpty }
    }

    /** What the suggestion dialog shows, so the person sees exactly what "Dùng" would add. */
    fun preview(s: Suggestion): String {
        val fields = (s.kind?.fields ?: NoteKind.entries.flatMap { it.fields }).associateBy { it.key }
        return listOfNotNull(
            s.kind?.let { "Loại: ${it.glyph} ${it.label}" },
            s.summary.takeIf { it.isNotEmpty() }?.let { "Ý chính: $it" },
            s.place?.let { "Địa chỉ: $it" },
        ).plus(s.attrs.map { (k, v) -> "${fields[k]?.label ?: k}: $v" }).joinToString("\n")
    }

    /** The note with the suggestion's empty-slot parts filled in; what the person already wrote stays. */
    fun apply(item: Item, s: Suggestion): Item {
        val kind = item.noteKind ?: s.kind
        val ownKeys = kind?.fields?.filter { !it.flag }?.map { it.key }?.toSet().orEmpty()
        val attrs = item.attrs + s.attrs.filter { (k, v) -> k in ownKeys && item.attrs[k].isNullOrBlank() && v.isNotBlank() }
        val line = s.summary.takeIf { it.isNotEmpty() }?.let { "Ý chính: $it" }
        val details = when {
            line == null || item.details.contains(s.summary) -> item.details
            item.details.isBlank() -> line
            line.length + 2 + item.details.length <= CloudItem.MAX_DETAILS -> line + "\n\n" + item.details
            else -> item.details
        }
        return item.copy(
            kind = kind?.key,
            attrs = attrs,
            place = item.place?.takeIf { it.isNotBlank() } ?: s.place,
            details = details,
        )
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() && it != "null" } else null
