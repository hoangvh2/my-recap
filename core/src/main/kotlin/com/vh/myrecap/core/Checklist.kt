package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** One line of a checklist on a task or appointment ("Ví", "Chìa khoá"). */
data class ChecklistEntry(val id: String, val text: String, val done: Boolean = false)

/** Only tasks and appointments can carry a checklist. */
fun ItemType.canChecklist(): Boolean = this == ItemType.TASK || this == ItemType.EVENT

data class ChecklistProgress(val done: Int, val total: Int, val remaining: List<ChecklistEntry>, val ticked: List<ChecklistEntry>) {
    val complete: Boolean get() = total > 0 && remaining.isEmpty()
}

/**
 * The checklist on a task or appointment ("ra khỏi nhà: ví, chìa khoá, sạc"). Pure functions, same rules as
 * the web app. A repeating item starts every occurrence unticked ([reset], used by [Schedule]).
 */
object Checklist {
    const val MAX_ENTRIES = 40
    const val MAX_TEXT = 80

    fun entries(item: Item): List<ChecklistEntry> = if (item.type.canChecklist()) item.checklist else emptyList()

    fun has(item: Item): Boolean = entries(item).isNotEmpty()

    fun progress(item: Item): ChecklistProgress {
        val all = entries(item)
        val ticked = all.filter { it.done }
        val remaining = all.filter { !it.done }
        return ChecklistProgress(ticked.size, all.size, remaining, ticked)
    }

    /** "Còn: Ví · Chìa khoá · Sạc · +2": the first [show] things still missing, never the whole list. */
    fun remainingLabel(item: Item, show: Int = 3): String {
        val remaining = progress(item).remaining
        if (remaining.isEmpty()) return ""
        val names = remaining.take(show).map { it.text }
        val more = remaining.size - names.size
        return "Còn: " + names.joinToString(" · ") + if (more > 0) " · +$more" else ""
    }

    /** "2/5", or "Đủ 5/5" once everything is ticked (words, not only colour). */
    fun chipLabel(item: Item): String {
        val p = progress(item)
        return if (p.complete) "Đủ ${p.total}/${p.total}" else "${p.done}/${p.total}"
    }

    fun toggle(item: Item, id: String): Item {
        val list = entries(item)
        if (list.none { it.id == id }) return item
        return item.copy(checklist = list.map { if (it.id == id) it.copy(done = !it.done) else it })
    }

    /** "Đủ rồi": everything ticked. */
    fun tickAll(item: Item): Item = item.copy(checklist = entries(item).map { it.copy(done = true) })

    /** Every line unticked: a new occurrence of a repeating item, or "Đặt lại". */
    fun reset(item: Item): Item = if (item.checklist.none { it.done }) item else item.copy(checklist = item.checklist.map { it.copy(done = false) })

    private val BULLET = Regex("^\\s*(?:[-*•]|\\[[ xX]?])\\s*")
    private val SPACES = Regex("\\s+")

    private fun clip(s: String): String = s.trim().replace(SPACES, " ").take(MAX_TEXT)

    /** Text → lines: one per line, or split at commas and semicolons when it is a single line. Blanks and repeats dropped. */
    fun parseLines(text: String, splitCommas: Boolean = true): List<String> {
        val parts = if (text.contains('\n') || !splitCommas) text.split(Regex("\r?\n")) else text.split(',', ';')
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (p in parts) {
            val t = clip(BULLET.replace(p, ""))
            if (t.isNotEmpty() && seen.add(t.lowercase())) out += t
        }
        return out.take(MAX_ENTRIES)
    }

    private fun newId(): String = "c" + UUID.randomUUID().toString().replace("-", "").take(10)

    /** [list] plus new unticked entries for [texts], skipping lines already there and stopping at the limit. */
    fun append(list: List<ChecklistEntry>, texts: List<String>): List<ChecklistEntry> {
        val have = list.map { it.text.lowercase() }.toHashSet()
        val added = ArrayList<ChecklistEntry>()
        for (raw in texts) {
            val t = clip(raw)
            if (t.isEmpty() || t.lowercase() in have) continue
            if (list.size + added.size >= MAX_ENTRIES) break
            have += t.lowercase()
            added += ChecklistEntry(newId(), t)
        }
        return if (added.isEmpty()) list else list + added
    }

    fun entriesFrom(texts: List<String>): List<ChecklistEntry> = append(emptyList(), texts)

    /**
     * "ra khỏi nhà 7h30 hằng ngày: ví, chìa khoá, sạc" → the part before the colon is the title line, the rest the
     * checklist. Needs a colon that is not part of a time ("9:30") and at least two lines after it, so
     * "gọi anh Nam: mai 9h" stays an ordinary quick-add line.
     */
    fun splitLine(line: String): Pair<String, List<String>>? {
        val t = line.trim()
        val at = t.indices.firstOrNull { i ->
            t[i] == ':' && !(t.getOrNull(i - 1)?.isDigit() == true && t.getOrNull(i + 1)?.isDigit() == true)
        } ?: return null
        if (at <= 0) return null
        val head = t.substring(0, at).trim()
        val lines = parseLines(t.substring(at + 1))
        return if (head.isNotEmpty() && lines.size >= 2) head to lines else null
    }

    fun encode(list: List<ChecklistEntry>): JSONArray {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("id", e.id).put("text", e.text).apply { if (e.done) put("done", true) })
        return a
    }

    fun decode(a: JSONArray?): List<ChecklistEntry> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = o.optString("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChecklistEntry(id, text, o.optBoolean("done", false))
        }.take(MAX_ENTRIES)
    }

    fun fromCloud(raw: Any?): List<ChecklistEntry> =
        (raw as? List<*>).orEmpty().mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            val text = (m["text"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChecklistEntry(id, text, m["done"] == true)
        }.take(MAX_ENTRIES)

    fun toCloud(list: List<ChecklistEntry>): List<Map<String, Any>> =
        list.map { e -> linkedMapOf<String, Any>("id" to e.id, "text" to e.text).also { if (e.done) it["done"] = true } }
}

/**
 * Checklist templates ("Ra khỏi nhà", "Giao hàng", "Chuẩn bị họp"): a name, the lines, and optionally when it
 * happens. One tap in quick add turns one into a task or appointment with its checklist. Managed in Cài đặt.
 */
data class ChecklistTemplate(
    val id: String,
    val name: String,
    val items: List<String>,
    /** What using it creates: [ItemType.TASK] or [ItemType.EVENT]. */
    val type: ItemType = ItemType.TASK,
    val recurrence: Recurrence? = null,
    /** "07:30"; without it the item is all-day (or undated when there is no recurrence either). */
    val time: String? = null,
    val uses: Int = 0,
    val lastUsed: Long = 0,
)

object ChecklistTemplates {
    const val MAX_TEMPLATES = 30
    const val MAX_NAME = 60
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /** Shown until the user edits the list, so the feature explains itself on first use. */
    val DEFAULTS = listOf(
        ChecklistTemplate("default-leave-home", "Ra khỏi nhà", listOf("Ví", "Chìa khoá", "Điện thoại và sạc", "Thuốc", "Thẻ xe"), recurrence = Recurrence.DAILY, time = "07:30"),
        ChecklistTemplate("default-delivery", "Giao hàng cho khách", listOf("Đủ hàng, đúng số lượng", "Hoá đơn", "Biên bản giao nhận", "Báo khách trước khi tới")),
        ChecklistTemplate("default-meeting", "Chuẩn bị họp", listOf("Tài liệu, báo giá", "Laptop và sạc", "Xem lại ghi chú lần trước"), type = ItemType.EVENT),
    )

    /** Either a message for the form, or the cleaned template ([template] keeps [selfId] when editing). */
    class Check(val error: String?, val template: ChecklistTemplate?)

    fun validate(
        list: List<ChecklistTemplate>,
        name: String,
        lines: String,
        type: ItemType,
        recurrence: Recurrence?,
        time: String?,
        selfId: String? = null,
    ): Check {
        val n = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
        if (n.isEmpty()) return Check("Cần nhập tên mẫu", null)
        if (list.any { it.id != selfId && TextSearch.fold(it.name) == TextSearch.fold(n) }) return Check("Đã có mẫu trùng tên", null)
        val items = Checklist.parseLines(lines, splitCommas = false)
        if (items.isEmpty()) return Check("Cần ít nhất một mục (mỗi dòng một mục)", null)
        val t = time?.takeIf { it.isNotBlank() }
        if (t != null && !TIME.matches(t)) return Check("Giờ chưa đúng", null)
        val kind = if (type == ItemType.EVENT) ItemType.EVENT else ItemType.TASK
        if (recurrence != null && t == null && kind == ItemType.EVENT) return Check("Lịch hẹn lặp cần có giờ", null)
        return Check(null, ChecklistTemplate(selfId ?: newId(), n, items, kind, recurrence, t))
    }

    private fun newId(): String = "tpl-" + UUID.randomUUID().toString().replace("-", "").take(10)

    fun add(list: List<ChecklistTemplate>, t: ChecklistTemplate): List<ChecklistTemplate> =
        if (list.size >= MAX_TEMPLATES) list else list + t

    /** Replaces the template with the same id, keeping how often it was used. */
    fun update(list: List<ChecklistTemplate>, t: ChecklistTemplate): List<ChecklistTemplate> =
        list.map { if (it.id == t.id) t.copy(uses = it.uses, lastUsed = it.lastUsed) else it }

    fun remove(list: List<ChecklistTemplate>, id: String): List<ChecklistTemplate> = list.filter { it.id != id }

    /** Puts back a deleted template where it was (Undo). */
    fun restore(list: List<ChecklistTemplate>, index: Int, t: ChecklistTemplate): List<ChecklistTemplate> =
        if (list.any { it.id == t.id } || list.size >= MAX_TEMPLATES) list else list.toMutableList().also { it.add(index.coerceIn(0, it.size), t) }

    /** Puts back any built-in template (by name) the user deleted, without touching the rest. */
    fun restoreDefaults(list: List<ChecklistTemplate>): List<ChecklistTemplate> {
        val have = list.map { TextSearch.fold(it.name) }.toSet()
        val missing = DEFAULTS.filter { TextSearch.fold(it.name) !in have }
        return list + missing.take((MAX_TEMPLATES - list.size).coerceAtLeast(0))
    }

    fun used(list: List<ChecklistTemplate>, id: String, now: Long): List<ChecklistTemplate> =
        list.map { if (it.id == id) it.copy(uses = it.uses + 1, lastUsed = now) else it }

    /** Most used first; ties go to the latest used, then to the saved order. */
    fun byUse(list: List<ChecklistTemplate>): List<ChecklistTemplate> =
        list.withIndex().sortedWith(compareByDescending<IndexedValue<ChecklistTemplate>> { it.value.uses }
            .thenByDescending { it.value.lastUsed }.thenBy { it.index }).map { it.value }

    /** Templates whose name contains every word typed (accents and case ignored). */
    fun matching(list: List<ChecklistTemplate>, query: String): List<ChecklistTemplate> {
        val words = TextSearch.tokens(query)
        if (words.isEmpty()) return emptyList()
        return byUse(list).filter { t -> val f = TextSearch.fold(t.name); words.all { it in f } }
    }

    fun scheduleLabel(t: ChecklistTemplate): String =
        listOfNotNull(if (t.type == ItemType.EVENT) "Lịch hẹn" else "Việc", t.recurrence?.label, t.time).joinToString(" · ")

    /** Has its own time or repeat, so using it needs no further questions. Otherwise the editor opens to pick a day. */
    fun isScheduled(t: ChecklistTemplate): Boolean = t.recurrence != null || t.time != null

    /**
     * The item a template creates. A repeating template starts at its next time: today when the time is still
     * ahead, else the next occurrence, so it is never overdue at birth.
     */
    fun instantiate(t: ChecklistTemplate, id: String, now: Long, zone: ZoneId): Item {
        val base = Item(
            id = id, type = t.type, status = ItemStatus.OPEN, title = t.name, allDay = t.time == null,
            createdAt = now, checklist = Checklist.entriesFrom(t.items),
        )
        if (!isScheduled(t)) return if (t.type == ItemType.EVENT) base.copy(whenAt = now) else base
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone)
        val time = t.time
        val todayAt = if (time != null) day.withHour(time.substring(0, 2).toInt()).withMinute(time.substring(3, 5).toInt()).toInstant().toEpochMilli() else day.toInstant().toEpochMilli()
        val ahead = time == null || todayAt > now
        val at = when {
            ahead -> todayAt
            t.recurrence != null -> Schedule.nextAfter(todayAt, t.recurrence, zone, now)
            else -> todayAt
        }
        return base.copy(whenAt = at, recurrence = t.recurrence)
    }

    fun encode(list: List<ChecklistTemplate>): String {
        val a = JSONArray()
        for (t in list) {
            a.put(
                JSONObject()
                    .put("id", t.id).put("name", t.name).put("items", JSONArray(t.items)).put("type", t.type.name)
                    .putOpt("recurrence", t.recurrence?.name).putOpt("time", t.time)
                    .put("uses", t.uses).put("lastUsed", t.lastUsed),
            )
        }
        return a.toString()
    }

    /** Null when nothing was ever saved (use [DEFAULTS]); a broken value reads as an empty list. */
    fun decode(json: String?): List<ChecklistTemplate>? {
        if (json == null) return null
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = o.optString("name").trim().take(MAX_NAME).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val arr = o.optJSONArray("items")
                val items = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optString(it).trim().takeIf { s -> s.isNotEmpty() } }.take(Checklist.MAX_ENTRIES)
                if (items.isEmpty()) return@mapNotNull null
                ChecklistTemplate(
                    id = id,
                    name = name,
                    items = items,
                    type = if (o.optString("type") == ItemType.EVENT.name) ItemType.EVENT else ItemType.TASK,
                    recurrence = Recurrence.entries.firstOrNull { it.name == o.optString("recurrence") },
                    time = o.optString("time").takeIf { TIME.matches(it) },
                    uses = o.optInt("uses"),
                    lastUsed = o.optLong("lastUsed"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
