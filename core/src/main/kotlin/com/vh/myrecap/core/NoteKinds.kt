package com.vh.myrecap.core

import org.json.JSONObject
import java.net.URLEncoder

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

/** Where a note's buttons lead. Only web links are ever opened, whatever was stored. */
object NoteLinks {
    fun isWebLink(url: String?): Boolean = url != null && Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE).matches(url.trim())

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    /** The Google Maps search for a place name or address; works in the browser when no map app answers. */
    fun mapsSearchUrl(place: String): String = "https://www.google.com/maps/search/?api=1&query=" + enc(place)

    /** The `geo:` link map apps answer to. */
    fun geoUri(place: String): String = "geo:0,0?q=" + enc(place)

    /** The link behind a note's "Mở link" button, or null when there is none worth opening. */
    fun openUrl(item: Item): String? = item.url?.trim()?.takeIf { isWebLink(it) }

    /** The place a note's map button searches: its address, else its title when it is a place note. */
    fun mapsQuery(item: Item): String? =
        item.place?.trim()?.takeIf { it.isNotEmpty() } ?: item.title.trim().takeIf { it.isNotEmpty() && item.noteKind == NoteKind.PLACE }

    /** True when the stored link is itself a Google Maps link, which points at the exact spot. */
    fun isMapsLink(url: String?): Boolean = isWebLink(url) && ShareIntake.sourceOf(url!!.trim()) == "Google Maps"
}

/** The note with its "finished with" flag (read, visited, watched) set or cleared; other notes are unchanged. */
fun Item.withNoteDone(on: Boolean): Item {
    val flag = noteKind?.doneFlag ?: return this
    return copy(attrs = if (on) attrs + (flag to "1") else attrs - flag)
}

/** "Chưa đọc" for the flag "Đã đọc". */
val NoteField.offLabel: String get() = if (label.startsWith("Đã ")) "Chưa " + label.removePrefix("Đã ") else "Chưa: $label"

/**
 * The line a list shows under a note's title: its kind and the values it keeps, then where it stands.
 * "📍 Địa điểm · bún chả · 60k · Chưa đến". Null for a plain note.
 */
fun Item.noteKindLine(): String? {
    val kind = noteKind?.takeIf { type == ItemType.NOTE } ?: return null
    val values = kind.fields.filter { !it.flag }.mapNotNull { attrs[it.key]?.takeIf(String::isNotBlank) }
    val flag = kind.fields.firstOrNull { it.flag }?.let { if (attrs[it.key] == "1") it.label else it.offLabel }
    return (listOf("${kind.glyph} ${kind.label}") + values + listOfNotNull(flag)).joinToString(" · ")
}

/** What the notes list is narrowed to: one kind, and/or only the notes not yet read, visited or watched. */
data class NoteFilter(val kind: NoteKind? = null, val pending: Boolean = false) {
    val active: Boolean get() = kind != null || pending

    fun matches(item: Item): Boolean =
        (kind == null || item.noteKind == kind) && (!pending || (item.noteKind?.doneFlag != null && !item.noteDone))

    fun apply(items: List<Item>): List<Item> = if (active) items.filter(::matches) else items

    companion object {
        /** Kinds in use among [notes], in menu order; the filter row only offers these. */
        fun available(notes: List<Item>): List<NoteKind> = NoteKind.entries.filter { k -> notes.any { it.noteKind == k } }
    }
}

/** The words of a note that search should match beyond title and body: link, kind name and the kind's text values. */
fun Item.noteSearchText(): String {
    val kind = noteKind?.takeIf { type == ItemType.NOTE } ?: return ""
    val values = kind.fields.filter { !it.flag }.mapNotNull { attrs[it.key] }
    return (listOfNotNull(url, kind.label) + values).joinToString(" ")
}
