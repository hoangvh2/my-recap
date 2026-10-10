package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow

enum class ItemType(val label: String) {
    TASK("Việc"),
    EVENT("Lịch hẹn"),
    EXPENSE("Chi tiêu"),
    NOTE("Ghi chú"),
}

/** DRAFT items were proposed by the AI and wait for the user's confirmation; nothing else counts them. */
enum class ItemStatus { DRAFT, OPEN, DONE }

/**
 * One thing the secretary keeps: a task, an appointment, an expense or a note.
 * [whenAt] is the task deadline, the appointment start or the expense date (epoch ms);
 * with [allDay] only its date matters.
 */
data class Item(
    val id: String,
    val type: ItemType,
    val status: ItemStatus,
    val title: String,
    val details: String = "",
    val whenAt: Long? = null,
    val allDay: Boolean = false,
    /** Whole VND. */
    val amount: Long? = null,
    val category: String? = null,
    val place: String? = null,
    val person: String? = null,
    /** Quick capture this item came from (a MEMO folder id), if any. */
    val sourceId: String? = null,
    /** The words in the capture the item was taken from. */
    val quote: String? = null,
    val createdAt: Long = 0,
    val doneAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type.name)
        .put("status", status.name)
        .put("title", title)
        .put("details", details)
        .putOpt("whenAt", whenAt)
        .put("allDay", allDay)
        .putOpt("amount", amount)
        .putOpt("category", category)
        .putOpt("place", place)
        .putOpt("person", person)
        .putOpt("sourceId", sourceId)
        .putOpt("quote", quote)
        .put("createdAt", createdAt)
        .putOpt("doneAt", doneAt)

    companion object {
        fun fromJson(o: JSONObject) = Item(
            id = o.getString("id"),
            type = ItemType.entries.firstOrNull { it.name == o.optString("type") } ?: ItemType.NOTE,
            status = ItemStatus.entries.firstOrNull { it.name == o.optString("status") } ?: ItemStatus.OPEN,
            title = o.optString("title", ""),
            details = o.optString("details", ""),
            whenAt = o.optLongOrNull("whenAt"),
            allDay = o.optBoolean("allDay", false),
            amount = o.optLongOrNull("amount"),
            category = o.optStringOrNull("category"),
            place = o.optStringOrNull("place"),
            person = o.optStringOrNull("person"),
            sourceId = o.optStringOrNull("sourceId"),
            quote = o.optStringOrNull("quote"),
            createdAt = o.optLong("createdAt", 0),
            doneAt = o.optLongOrNull("doneAt"),
        )
    }
}

/** Expense categories offered to the model and in the editor. */
val EXPENSE_CATEGORIES = listOf(
    "Ăn uống", "Di chuyển", "Mua sắm", "Hoá đơn", "Sức khoẻ", "Giải trí", "Giáo dục", "Gia đình", "Công việc", "Khác",
)

/**
 * Turns a quick voice note into proposed items. The model gets today's date and the next two weeks
 * spelled out with weekdays, so "thứ 5 tuần sau" or "mai 3h chiều" resolve to real dates without
 * the app parsing Vietnamese time phrases.
 */
object ItemExtraction {
    fun system(): String =
        "Bạn là thư ký cá nhân. Đọc ghi chú giọng nói (transcript do máy nhận dạng, có thể sai chính tả) và tách " +
            "thành các mục cần lưu. Chỉ dùng thông tin có trong ghi chú, không bịa. Trả lời DUY NHẤT một JSON hợp lệ, " +
            "không markdown, không giải thích."

    fun userMessage(transcript: String, now: ZonedDateTime): String = buildString {
        appendLine("Thời điểm hiện tại: ${weekday(now.toLocalDate())}, ${now.toLocalDate()} ${now.format(HM)} (${now.zone.id}).")
        appendLine("Lịch 14 ngày tới để quy đổi ngày tương đối (mai, mốt, thứ 5 tuần sau…):")
        for (d in 0L until 14L) {
            val day = now.toLocalDate().plusDays(d)
            appendLine("- ${weekday(day)} $day" + if (d == 0L) " (hôm nay)" else if (d == 1L) " (mai)" else "")
        }
        appendLine()
        appendLine("Loại mục:")
        appendLine("- task: việc cần làm (có thể có hạn).")
        appendLine("- event: lịch hẹn/cuộc họp/sự kiện có thời điểm.")
        appendLine("- expense: khoản đã chi hoặc sẽ chi. amount là số nguyên VND (\"85k\" = 85000, \"1 triệu 2\" = 1200000).")
        appendLine("  category là một trong: ${EXPENSE_CATEGORIES.joinToString(", ")}.")
        appendLine("- note: ý tưởng, thông tin cần nhớ, không thuộc 3 loại trên.")
        appendLine("Một ghi chú có thể chứa nhiều mục. Không tạo mục trùng nhau.")
        appendLine("Giờ nói kiểu Việt: \"3h chiều\" = 15:00, \"8 giờ tối\" = 20:00, \"sáng mai\" không rõ giờ thì để time null.")
        appendLine()
        appendLine("Định dạng:")
        appendLine(
            """{"items":[{"type":"task|event|expense|note","title":"ngắn gọn, ≤ 10 từ","details":"chi tiết thêm hoặc chuỗi rỗng",""" +
                """"date":"YYYY-MM-DD hoặc null","time":"HH:mm hoặc null","amount":null,"category":null,"place":null,""" +
                """"person":null,"quote":"câu gốc trong ghi chú"}]}""",
        )
        appendLine("Nếu không có gì cần lưu, trả về {\"items\":[]}.")
        appendLine()
        appendLine("Ghi chú:")
        appendLine("<<<")
        appendLine(transcript.trim())
        appendLine(">>>")
    }.trim()

    /**
     * Parses the model's JSON into DRAFT items. Tolerates code fences, a bare array and text around
     * the JSON; returns null when no JSON can be read, so the caller can keep the note as text.
     */
    fun parse(raw: String, zone: ZoneId, now: Long, sourceId: String?, newId: () -> String): List<Item>? {
        val array = itemsArray(raw) ?: return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val items = mutableListOf<Item>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val type = when (o.optString("type").lowercase(Locale.ROOT).trim()) {
                "task", "todo" -> ItemType.TASK
                "event", "appointment", "meeting" -> ItemType.EVENT
                "expense", "spending" -> ItemType.EXPENSE
                else -> ItemType.NOTE
            }
            val title = o.optStringOrNull("title")?.trim().orEmpty()
            val details = o.optStringOrNull("details")?.trim().orEmpty()
            if (title.isEmpty() && details.isEmpty()) continue
            val date = o.optStringOrNull("date")?.let { parseDate(it) }
            val time = o.optStringOrNull("time")?.let { parseTime(it) }
            val amount = when (val a = o.opt("amount")) {
                is Number -> a.toLong().takeIf { it > 0 }
                is String -> Money.parseVnd(a)
                else -> null
            }
            var whenAt: Long? = null
            var allDay = false
            when {
                date != null && time != null -> whenAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                date != null -> {
                    whenAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    allDay = true
                }
                time != null && type != ItemType.NOTE -> {
                    // A time without a day means today, or tomorrow when that time has passed.
                    var at = today.atTime(time).atZone(zone)
                    if (at.toInstant().toEpochMilli() < now) at = at.plusDays(1)
                    whenAt = at.toInstant().toEpochMilli()
                }
                type == ItemType.EXPENSE -> {
                    whenAt = today.atStartOfDay(zone).toInstant().toEpochMilli()
                    allDay = true
                }
            }
            items += Item(
                id = newId(),
                type = type,
                status = ItemStatus.DRAFT,
                title = title.ifEmpty { details.take(60) },
                details = if (title.isEmpty()) "" else details,
                whenAt = whenAt,
                allDay = allDay,
                amount = if (type == ItemType.EXPENSE) amount else null,
                category = o.optStringOrNull("category")?.trim()?.let { c ->
                    if (type != ItemType.EXPENSE) null else EXPENSE_CATEGORIES.firstOrNull { it.equals(c, ignoreCase = true) } ?: "Khác"
                } ?: if (type == ItemType.EXPENSE) "Khác" else null,
                place = o.optStringOrNull("place")?.trim()?.ifEmpty { null },
                person = o.optStringOrNull("person")?.trim()?.ifEmpty { null },
                sourceId = sourceId,
                quote = o.optStringOrNull("quote")?.trim()?.ifEmpty { null },
                createdAt = now,
            )
        }
        return items
    }

    private fun itemsArray(raw: String): JSONArray? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val body = text.substring(start)
        return try {
            if (body.startsWith("[")) {
                JSONArray(body.substring(0, body.lastIndexOf(']') + 1))
            } else {
                val obj = JSONObject(body.substring(0, body.lastIndexOf('}') + 1))
                obj.optJSONArray("items") ?: JSONArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim().take(10))
    } catch (_: Exception) {
        null
    }

    private fun parseTime(s: String): LocalTime? {
        val m = Regex("""^(\d{1,2})[:hH.](\d{2})?""").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun weekday(date: LocalDate): String = when (date.dayOfWeek.value) {
        1 -> "Thứ Hai"
        2 -> "Thứ Ba"
        3 -> "Thứ Tư"
        4 -> "Thứ Năm"
        5 -> "Thứ Sáu"
        6 -> "Thứ Bảy"
        else -> "Chủ Nhật"
    }
}

object Money {
    /**
     * Reads Vietnamese spoken/written amounts: "85k", "85 nghìn", "1tr2", "1 triệu 2", "1,5 triệu",
     * "1.200.000đ", "2 tỷ". Returns whole VND, or null when there is no amount.
     */
    fun parseVnd(text: String): Long? {
        val s = text.lowercase(Locale.ROOT).replace("đồng", "").replace("vnd", "").replace("đ", "").trim()
        if (s.isEmpty()) return null
        val unit = Regex("""(\d+(?:[.,]\d+)?)\s*(tỷ|ty|triệu|trieu|tr|m|nghìn|ngàn|ngan|nghin|k)\s*(\d{1,3})?""").find(s)
        if (unit != null) {
            val base = unit.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val mul = when (unit.groupValues[2]) {
                "tỷ", "ty" -> 1_000_000_000.0
                "triệu", "trieu", "tr", "m" -> 1_000_000.0
                else -> 1_000.0
            }
            // "1 triệu 2" / "1tr5" = 1.2M / 1.5M; "1 triệu 250" = 1.25M.
            val tail = unit.groupValues[3]
            val extra = if (tail.isNotEmpty() && mul >= 1_000_000) {
                tail.toDouble() / 10.0.pow(tail.length) * mul
            } else {
                0.0
            }
            return Math.round(base * mul + extra).takeIf { it > 0 }
        }
        val digits = s.filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > 15) return null
        return digits.toLong().takeIf { it > 0 }
    }

    /** "1.250.000 đ" */
    fun format(amount: Long): String {
        val digits = amount.toString()
        val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
        return "$grouped đ"
    }
}

/** Plain-text rendering of items for sharing. */
object ItemText {
    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")

    fun whenText(item: Item, zone: ZoneId): String? {
        val at = item.whenAt ?: return null
        val z = Instant.ofEpochMilli(at).atZone(zone)
        return if (item.allDay) z.format(DATE) else z.format(DATE_TIME)
    }

    fun line(item: Item, zone: ZoneId): String = buildString {
        append(if (item.status == ItemStatus.DONE) "[x] " else "- ")
        append(item.title)
        item.amount?.let { append(" — ").append(Money.format(it)) }
        whenText(item, zone)?.let { append(" (").append(it).append(")") }
        item.place?.let { append(" @ ").append(it) }
        if (item.details.isNotBlank()) append("\n  ").append(item.details)
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() && it != "null" } else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
