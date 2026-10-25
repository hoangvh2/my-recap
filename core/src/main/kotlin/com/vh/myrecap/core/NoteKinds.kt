package com.vh.myrecap.core

import org.json.JSONObject

/**
 * What a note is about, so it can be shown and filtered by what it is: a place to eat, an article to read later,
 * a video. The key is what is stored; an unknown key is ignored (the note then shows as a plain note), so a kind
 * added later does not break older data. Each kind lists the few [NoteField]s it keeps in [Item.attrs].
 */
enum class NoteKind(val key: String, val label: String, val glyph: String, val fields: List<NoteField>) {
    PLACE(
        "place", "Địa điểm", "📍",
        listOf(NoteField("dish", "Món / dịch vụ"), NoteField("price", "Giá"), NoteField("by", "Ai giới thiệu"), NoteField.flag("visited", "Đã đến")),
    ),
    READ(
        "read", "Đọc sau", "📖",
        listOf(NoteField("topic", "Chủ đề"), NoteField("source", "Nguồn"), NoteField.flag("read", "Đã đọc")),
    ),
    VIDEO(
        "video", "Video", "▶",
        listOf(NoteField("channel", "Kênh"), NoteField("type", "Loại (review, hướng dẫn…)"), NoteField.flag("watched", "Đã xem")),
    );

    /** The flag that says this note is finished with (read, visited, watched), if the kind has one. */
    val doneFlag: String? get() = fields.firstOrNull { it.flag }?.key

    companion object {
        fun fromKey(key: String?): NoteKind? = entries.firstOrNull { it.key == key }
    }
}

/** One value a [NoteKind] keeps. A [flag] is stored as "1" when set and left out otherwise. */
data class NoteField(val key: String, val label: String, val flag: Boolean = false) {
    companion object {
        fun flag(key: String, label: String) = NoteField(key, label, flag = true)
    }
}

/** Same limits as the Firestore rules. */
object NoteKinds {
    const val MAX_URL = 500
    const val MAX_ATTR_VALUE = 200
    const val MAX_ATTRS = 8

    /** Only notes carry a kind. */
    fun ItemType.canHaveKind(): Boolean = this == ItemType.NOTE

    /**
     * [attrs] cleaned for storage: only keys the kind knows (values of a kind the note used to have are dropped
     * by [clean], not here), trimmed, clipped, and flags reduced to "1".
     */
    fun cleanAttrs(kind: NoteKind?, attrs: Map<String, String>): Map<String, String> {
        if (kind == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (f in kind.fields) {
            val v = attrs[f.key]?.trim().orEmpty()
            if (v.isEmpty()) continue
            if (f.flag) out[f.key] = "1" else out[f.key] = v.take(MAX_ATTR_VALUE)
        }
        return out
    }

    /** The note with its kind, link and values made consistent; anything but a note loses all three. */
    fun clean(item: Item): Item {
        val kind = item.noteKind.takeIf { item.type.canHaveKind() }
        return item.copy(
            kind = kind?.key,
            url = item.url?.trim()?.takeIf { it.isNotEmpty() && item.type.canHaveKind() }?.take(MAX_URL),
            attrs = cleanAttrs(kind, item.attrs),
        )
    }

    fun encode(attrs: Map<String, String>): JSONObject = JSONObject().also { o -> attrs.forEach { (k, v) -> o.put(k, v) } }

    fun decode(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) o.optString(k, "").takeIf { it.isNotEmpty() }?.let { out[k] = it }
        return out
    }
}

/** The kind of a note, or null for a plain note (or a key this version does not know). */
val Item.noteKind: NoteKind? get() = NoteKind.fromKey(kind)

/** Whether the note's "finished with" flag (read, visited, watched) is set. */
val Item.noteDone: Boolean get() = noteKind?.doneFlag?.let { attrs[it] == "1" } == true
