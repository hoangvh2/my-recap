package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A colour tag on a task or appointment: only a visual cue, it changes no reminder (that is
 * [Item.important]). Each tag has its own glyph so it never depends on colour alone. The key is what
 * is stored; an unknown key is ignored, so a tag added later does not break older data.
 */
enum class ItemTag(val key: String, val label: String, val glyph: String) {
    RED("red", "Gấp", "!"),
    YELLOW("yellow", "Nổi bật", "★"),
    ORANGE("orange", "Lưu ý", "▲"),
    BLUE("blue", "Cần xác nhận", "?"),
    PURPLE("purple", "Gia đình", "♥"),
    GREEN("green", "Học", "✎");

    companion object {
        fun fromKey(key: String?): ItemTag? = entries.firstOrNull { it.key == key }
    }
}

/** The tag of an item, or null for none (or a key this version does not know). */
val Item.itemTag: ItemTag? get() = ItemTag.fromKey(tag)

/** Study routines (the "Học" tag) sit apart from the day's work: the screens list them last and fold them into one card. */
val Item.isStudy: Boolean get() = itemTag == ItemTag.GREEN

object StudyLane {
    /** [items] with the study ones moved after the others; the order inside each part is kept. */
    fun studyLast(items: List<Item>): List<Item> = items.filterNot { it.isStudy } + items.filter { it.isStudy }

    /** The blocks due by the end of [today] (missed ones included) or without a date; later ones are just counted. */
    fun today(items: List<Item>, today: java.time.LocalDate, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Item> =
        items.filter { it.whenAt == null || !java.time.Instant.ofEpochMilli(it.whenAt!!).atZone(zone).toLocalDate().isAfter(today) }

    /** What the folded card says: how many blocks and the minutes still planned in their checklists. */
    data class Summary(val blocks: Int, val minutes: Int)

    fun summary(items: List<Item>): Summary =
        Summary(items.size, items.sumOf { StudyPlan.totalMinutes(StudyPlan.phases(it)) })
}

/** Tags only make sense on tasks and appointments. */
fun ItemType.canHaveTag(): Boolean = this == ItemType.TASK || this == ItemType.EVENT

/** What the list screens show: everything, any tagged item, or one tag. */
data class TagFilter(val anyTag: Boolean = false, val tag: ItemTag? = null) {
    val active: Boolean get() = anyTag || tag != null

    fun matches(item: Item): Boolean = when {
        tag != null -> item.itemTag == tag
        anyTag -> item.itemTag != null
        else -> true
    }

    fun apply(items: List<Item>): List<Item> = if (active) items.filter(::matches) else items

    companion object {
        /** Tags in use on open tasks and appointments, in palette order; the filter row only offers these. */
        fun available(items: List<Item>): List<ItemTag> {
            val used = items.filter { it.status == ItemStatus.OPEN && it.type.canHaveTag() }.mapNotNull { it.itemTag }.toSet()
            return ItemTag.entries.filter { it in used }
        }
    }
}

/** "When the person is X, tag it" rules; the first match wins. */
data class TagRule(val field: Field, val text: String, val tag: ItemTag) {
    enum class Field(val label: String) { PERSON("Với ai"), PLACE("Địa điểm"), KEYWORD("Từ khoá") }
}

object TagRules {
    const val MAX = 20

    /** The tag the first matching rule gives [item], ignoring case and accents; null when none matches. */
    fun match(rules: List<TagRule>, item: Item): ItemTag? {
        if (!item.type.canHaveTag()) return null
        return rules.firstOrNull { rule ->
            val needle = TextSearch.fold(rule.text).trim()
            if (needle.isEmpty()) return@firstOrNull false
            val haystack = when (rule.field) {
                TagRule.Field.PERSON -> item.person.orEmpty()
                TagRule.Field.PLACE -> item.place.orEmpty()
                TagRule.Field.KEYWORD -> item.title + " " + item.details
            }
            needle in TextSearch.fold(haystack)
        }?.tag
    }

    /**
     * Gives a newly created item the tag its rules say. Never used on edits, so a tag the person
     * removed stays removed; an item that already has a tag keeps it.
     */
    fun applyToNew(rules: List<TagRule>, item: Item): Item {
        if (item.tag != null) return item
        val tag = match(rules, item) ?: return item
        return item.copy(tag = tag.key)
    }

    fun add(rules: List<TagRule>, rule: TagRule): List<TagRule> {
        val clean = rule.copy(text = rule.text.trim())
        if (clean.text.isEmpty() || rules.size >= MAX || rules.any { it.field == clean.field && TextSearch.fold(it.text) == TextSearch.fold(clean.text) }) return rules
        return rules + clean
    }

    fun encode(rules: List<TagRule>): String = JSONArray().apply {
        rules.forEach { put(JSONObject().put("field", it.field.name).put("text", it.text).put("tag", it.tag.key)) }
    }.toString()

    fun decode(raw: String?): List<TagRule>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val field = TagRule.Field.entries.firstOrNull { it.name == o.optString("field") } ?: return@mapNotNull null
                val tag = ItemTag.fromKey(o.optString("tag")) ?: return@mapNotNull null
                val text = o.optString("text").trim().ifEmpty { return@mapNotNull null }
                TagRule(field, text, tag)
            }.take(MAX)
        }.getOrNull()
    }
}
