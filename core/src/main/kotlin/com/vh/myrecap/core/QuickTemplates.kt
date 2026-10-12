package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Saved quick-add lines ("cà phê 45k", "đổ xăng …k"). A line without a blank is added in one tap; a
 * line with a blank ("…") is put in the quick-add box with the cursor on the blank. Pinned lines come
 * first and are the ones the home-screen widget shows.
 */
data class QuickTemplate(val text: String, val pinned: Boolean = false)

object QuickTemplates {
    /** What the user types for "fill this in"; "..." is accepted too. */
    const val BLANK = "…"

    /** Shown until the user edits the list, so the feature explains itself on first use. */
    val DEFAULTS = listOf(
        QuickTemplate("cà phê 30k", pinned = true),
        QuickTemplate("ăn trưa …k", pinned = true),
        QuickTemplate("gọi … mai 9h", pinned = true),
        QuickTemplate("đổ xăng …k"),
        QuickTemplate("họp … thứ 2 9h"),
    )

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

    /** Adds [text] unless an equal line (ignoring accents and case) is already saved. */
    fun add(list: List<QuickTemplate>, text: String): List<QuickTemplate> {
        val t = normalize(text)
        if (t.isEmpty() || list.any { same(it.text, t) }) return list
        return list + QuickTemplate(t)
    }

    /** Pinned first, then the order they were added. */
    fun ordered(list: List<QuickTemplate>): List<QuickTemplate> = list.sortedByDescending { it.pinned }

    fun encode(list: List<QuickTemplate>): String =
        JSONArray(list.map { JSONObject().put("text", it.text).put("pinned", it.pinned) }).toString()

    /** Null when nothing was ever saved (use [DEFAULTS]); a broken value reads as an empty list. */
    fun decode(json: String?): List<QuickTemplate>? {
        if (json == null) return null
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                o.optString("text").takeIf { it.isNotBlank() }?.let { QuickTemplate(normalize(it), o.optBoolean("pinned")) }
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
