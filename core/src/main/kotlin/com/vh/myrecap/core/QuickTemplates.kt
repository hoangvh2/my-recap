package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Saved quick-add lines ("cà phê 45k", "đổ xăng …k"). A line without a blank is added in one tap; a
 * line with a blank ("…") is put in the quick-add box with the cursor on the blank. The empty box offers
 * only the most used line of each kind; the others come up as the user types words they contain.
 */
data class QuickTemplate(val text: String, val uses: Int = 0, val lastUsed: Long = 0)

object QuickTemplates {
    /** What the user types for "fill this in"; "..." is accepted too. */
    const val BLANK = "…"

    /** Shown until the user edits the list, so the feature explains itself on first use. */
    val DEFAULTS = listOf(
        QuickTemplate("gọi … mai 9h"),
        QuickTemplate("họp … thứ 2 9h"),
        QuickTemplate("cà phê 30k"),
        QuickTemplate("ăn trưa …k"),
        QuickTemplate("đổ xăng …k"),
    )

    /** The kinds the empty box offers one line for, in this order. */
    val FEATURED_TYPES = listOf(ItemType.TASK, ItemType.EVENT, ItemType.EXPENSE)

    fun normalize(text: String): String = text.replace("...", BLANK).trim().replace(Regex("\\s+"), " ")

    fun hasBlank(text: String): Boolean = BLANK in normalize(text)

    /** The text to put in the box and where the cursor goes: the first blank, else the end. */
    fun fill(text: String): Pair<String, Int> {
        val t = normalize(text)
        val at = t.indexOf(BLANK)
        if (at < 0) return "$t " to t.length + 1
        val before = t.substring(0, at)
        val after = t.substring(at + BLANK.length).replace(BLANK, "")
        // "gọi … mai" → "gọi | mai" (a space on each side of the cursor); "ăn trưa …k" → "ăn trưa |k".
        val head = if (before.isEmpty() || before.endsWith(" ")) before else "$before "
        val tail = if (after.startsWith(" ")) " " + after.trimStart() else after
        return head + tail to head.length
    }

    /**
     * What a line adds: an expense, an appointment or a task. A blank is read as "1" so "ăn trưa …k"
     * counts as an expense and "gọi … mai 9h" as a task.
     */
    fun typeOf(text: String, now: Long, zone: ZoneId): ItemType =
        QuickAdd.parse(normalize(text).replace(BLANK, "1"), now, zone).type

    /** Adds [text] unless an equal line (ignoring accents and case) is already saved. */
    fun add(list: List<QuickTemplate>, text: String): List<QuickTemplate> {
        val t = normalize(text)
        if (t.isEmpty() || list.any { same(it.text, t) }) return list
        return list + QuickTemplate(t)
    }

    /** Replaces the line at [index]; blank text or a duplicate of another line leaves the list as is. */
    fun edit(list: List<QuickTemplate>, index: Int, text: String): List<QuickTemplate> {
        val t = normalize(text)
        if (index !in list.indices || t.isEmpty() || list.withIndex().any { (i, it) -> i != index && same(it.text, t) }) return list
        return list.toMutableList().also { it[index] = it[index].copy(text = t) }
    }

    /** Counts one more use of [template], so the most used line of its kind is the one offered. */
    fun used(list: List<QuickTemplate>, template: QuickTemplate, now: Long): List<QuickTemplate> =
        list.map { if (same(it.text, template.text)) it.copy(uses = it.uses + 1, lastUsed = now) else it }

    /** Most used first; ties go to the latest used, then to the order saved. */
    fun byUse(list: List<QuickTemplate>): List<QuickTemplate> =
        list.withIndex().sortedWith(compareByDescending<IndexedValue<QuickTemplate>> { it.value.uses }
            .thenByDescending { it.value.lastUsed }.thenBy { it.index }).map { it.value }

    /** The most used line of each kind in [FEATURED_TYPES], for the empty box and the widget. */
    fun featured(list: List<QuickTemplate>, now: Long, zone: ZoneId): List<QuickTemplate> {
        val ranked = byUse(list)
        return FEATURED_TYPES.mapNotNull { type -> ranked.firstOrNull { typeOf(it.text, now, zone) == type } }
    }

    /**
     * Lines containing every word typed so far, accents and case ignored, each word anywhere in the line
     * ("ca" finds "cà phê 30k" and "ăn trưa cá …k"). Lines starting with the first word come first, then
     * the most used. A line the box already holds word for word is left out.
     */
    fun matching(list: List<QuickTemplate>, query: String, limit: Int = 4): List<QuickTemplate> {
        val words = TextSearch.tokens(query)
        if (words.isEmpty()) return emptyList()
        val q = TextSearch.fold(query)
        return byUse(list)
            .filter { t ->
                val folded = TextSearch.fold(normalize(t.text).replace(BLANK, " "))
                folded != q && words.all { it in folded }
            }
            .sortedByDescending { TextSearch.fold(it.text).startsWith(words.first()) }
            .take(limit)
    }

    fun encode(list: List<QuickTemplate>): String =
        JSONArray(list.map { JSONObject().put("text", it.text).put("uses", it.uses).put("lastUsed", it.lastUsed) }).toString()

    /** Null when nothing was ever saved (use [DEFAULTS]); a broken value reads as an empty list. */
    fun decode(json: String?): List<QuickTemplate>? {
        if (json == null) return null
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                o.optString("text").takeIf { it.isNotBlank() }
                    ?.let { QuickTemplate(normalize(it), o.optInt("uses"), o.optLong("lastUsed")) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Lines worth saving, from what the user keeps adding: a title used [MIN_REPEATS]+ times in the last
     * [WINDOW_DAYS] days. An expense keeps its usual amount when most entries share it ("cà phê 45k"),
     * else leaves it blank ("đổ xăng …k"); a task or appointment keeps its usual hour.
     */
    fun suggest(history: List<Item>, saved: List<QuickTemplate>, now: Long, zone: ZoneId, limit: Int = 5): List<String> {
        val since = now - WINDOW_DAYS * 86_400_000L
        val recent = history.filter {
            it.status != ItemStatus.DRAFT && it.createdAt >= since && it.title.isNotBlank() &&
                (it.type == ItemType.TASK || it.type == ItemType.EVENT || it.type == ItemType.EXPENSE)
        }
        return recent.groupBy { TextSearch.fold(it.title) }
            .values.filter { it.size >= MIN_REPEATS }
            .sortedByDescending { it.size }
            .mapNotNull { group ->
                val latest = group.maxBy { it.createdAt }
                val title = latest.title.trim()
                val line = when (latest.type) {
                    ItemType.EXPENSE -> {
                        val usual = usual(group.mapNotNull { it.amount })
                        if (usual != null) "$title ${short(usual)}" else "$title ${BLANK}k"
                    }
                    else -> {
                        val hours = group.filter { it.whenAt != null && !it.allDay }
                            .map { Instant.ofEpochMilli(it.whenAt!!).atZone(zone).toLocalTime() }
                            .map { if (it.minute == 0) "${it.hour}h" else "${it.hour}h${"%02d".format(it.minute)}" }
                        usual(hours)?.let { "$title $it" } ?: title
                    }
                }
                line.takeIf { l -> saved.none { same(it.text, l) || same(it.text.substringBefore(BLANK).trim(), title) } }
            }
            .take(limit)
    }

    /** "45k", "5tr", "1.25tr"; the quick-add parser reads each back to the same amount. */
    fun short(amount: Long): String = when {
        amount >= 1_000_000 && amount % 10_000 == 0L -> {
            val millions = amount / 1_000_000
            val rest = (amount % 1_000_000 / 10_000).toString().padStart(2, '0').trimEnd('0')
            if (rest.isEmpty()) "${millions}tr" else "$millions.${rest}tr"
        }
        amount % 1_000 == 0L -> "${amount / 1_000}k"
        else -> "${amount}đ"
    }

    private const val MIN_REPEATS = 3
    private const val WINDOW_DAYS = 60

    /** The value at least half of [values] share, if any. */
    private fun <T> usual(values: List<T>): T? {
        if (values.isEmpty()) return null
        val (value, count) = values.groupingBy { it }.eachCount().maxBy { it.value }
        return value.takeIf { count * 2 >= values.size }
    }

    private fun same(a: String, b: String) = TextSearch.fold(normalize(a)) == TextSearch.fold(normalize(b))
}
