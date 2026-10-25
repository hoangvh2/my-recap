package com.vh.myrecap.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sharing tasks, appointments and notes with linked people. Same rules as the web app and the server
 * (shared/share.ts): an item is shared one by one with chosen contacts, they get a read-only copy, and only
 * the owner edits. This file holds the pure parts: codes, ids, the cloud shape of an item, and what a
 * recipient sees.
 */
object Share {
    /** Expenses stay private: money is never shared. */
    val SHAREABLE = setOf(ItemType.TASK, ItemType.EVENT, ItemType.NOTE)

    const val CODE_LENGTH = 8
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val ALIAS_CHARS = 40

    fun isShareable(item: Item) = item.type in SHAREABLE && item.status != ItemStatus.DRAFT

    /** What a person typed ("abcd efgh", "ABCD-EFGH") as the 8 code characters, or null when it cannot be one. */
    fun normalizeCode(input: String): String? {
        val s = input.uppercase().filter { !it.isWhitespace() && it != '-' }
        return s.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
    }

    /** "ABCD EFGH": easier to read aloud. */
    fun formatCode(code: String): String = if (code.length == CODE_LENGTH) code.take(4) + " " + code.drop(4) else code

    fun cleanAlias(input: String): String = input.trim().replace(Regex("\\s+"), " ").take(ALIAS_CHARS)

    /** Read-only copy of `itemId` owned by `ownerUid`. */
    fun shareId(ownerUid: String, itemId: String) = "${ownerUid}_$itemId"

    /** Series id of a repeating task's occurrence (`a_n123` → `a`; older chained `a_n1_n2` → `a`), or null. */
    fun parentOfRepeat(itemId: String): String? = Regex("^(.+?)(?:_n\\d+)+$").find(itemId)?.groupValues?.get(1)

    /** Id of the occurrence at [whenAt]: the same on every device (no duplicates) and it never grows. */
    fun repeatId(itemId: String, whenAt: Long): String = "${parentOfRepeat(itemId) ?: itemId}_n$whenAt"

    fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** Someone I linked with. [alias] is what I call them, only I see it. */
data class Contact(
    val uid: String,
    val alias: String,
    val googleName: String,
    val maskedEmail: String,
    val linkedAt: Long,
    val notify: Boolean,
) {
    companion object {
        fun fromMap(m: Map<String, Any?>): Contact? {
            val uid = m["uid"] as? String ?: return null
            val alias = (m["alias"] as? String)?.takeIf { it.isNotBlank() } ?: (m["googleName"] as? String) ?: return null
            return Contact(
                uid = uid,
                alias = alias,
                googleName = m["googleName"] as? String ?: alias,
                maskedEmail = m["maskedEmail"] as? String ?: "",
                linkedAt = (m["linkedAt"] as? Number)?.toLong() ?: 0,
                notify = m["notify"] as? Boolean ?: true,
            )
        }
    }
}

/** The read-only copy of an item someone shared with me (or that I shared: then I am [ownerUid]). */
data class SharedItem(
    val id: String,
    val ownerUid: String,
    val ownerName: String,
    val itemId: String,
    val audience: List<String>,
    val type: ItemType,
    val status: ItemStatus,
    val title: String,
    val details: String,
    val whenAt: Long?,
    val allDay: Boolean,
    val place: String?,
    val recurrence: Recurrence?,
    val important: Boolean,
    val doneAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** As an item for the lists: same rows and sections, keyed by the share id so it never collides with mine. */
    fun asItem() = Item(
        id = id,
        type = type,
        status = status,
        title = title,
        details = details,
        whenAt = whenAt,
        allDay = allDay,
        place = place,
        createdAt = createdAt,
        doneAt = doneAt,
        recurrence = recurrence,
        important = important,
    )

    /** Who shared it, as I named them; their Google name until my contact list has loaded. */
    fun ownerLabel(contacts: List<Contact>): String = contacts.firstOrNull { it.uid == ownerUid }?.alias ?: ownerName

    companion object {
        fun fromMap(m: Map<String, Any?>): SharedItem? {
            val id = m["id"] as? String ?: return null
            val owner = m["ownerUid"] as? String ?: return null
            val type = ItemType.entries.firstOrNull { it.name == m["type"] } ?: return null
            return SharedItem(
                id = id,
                ownerUid = owner,
                ownerName = m["ownerName"] as? String ?: "",
                itemId = m["itemId"] as? String ?: "",
                audience = (m["audience"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                type = type,
                status = if (m["status"] == "DONE") ItemStatus.DONE else ItemStatus.OPEN,
                title = m["title"] as? String ?: "",
                details = m["details"] as? String ?: "",
                whenAt = (m["whenAt"] as? Number)?.toLong(),
                allDay = m["allDay"] as? Boolean ?: false,
                place = m["place"] as? String,
                recurrence = (m["recurrence"] as? String)?.let { r -> Recurrence.entries.firstOrNull { it.name == r } },
                important = m["important"] as? Boolean ?: false,
                doneAt = (m["doneAt"] as? Number)?.toLong(),
                createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0,
                updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: 0,
            )
        }

        /**
         * Which shared items belong in my lists: open tasks and appointments from today on (someone else's late
         * task is not mine to chase, so it never shows as overdue), and notes. Done ones drop out.
         */
        fun visible(list: List<SharedItem>, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): List<SharedItem> {
            return list.filter { s ->
                when {
                    s.status != ItemStatus.OPEN -> false
                    s.type == ItemType.NOTE || s.whenAt == null -> true
                    s.allDay -> !Instant.ofEpochMilli(s.whenAt).atZone(zone).toLocalDate().isBefore(today)
                    else -> s.whenAt >= today.atStartOfDay(zone).toInstant().toEpochMilli()
                }
            }
        }
    }
}

/** "Tất cả / Của tôi / Được chia sẻ" above the lists. */
enum class OwnerFilter(val label: String) { ALL("Tất cả"), MINE("Của tôi"), SHARED("Được chia sẻ") }

/** "X đã liên kết với bạn" / "X đã huỷ liên kết". */
data class ShareNotice(val id: String, val kind: String, val fromUid: String, val fromName: String, val at: Long) {
    companion object {
        fun fromMap(m: Map<String, Any?>): ShareNotice? {
            val id = m["id"] as? String ?: return null
            val kind = (m["kind"] as? String)?.takeIf { it == "linked" || it == "unlinked" } ?: return null
            return ShareNotice(id, kind, m["fromUid"] as? String ?: "", m["fromName"] as? String ?: "", (m["at"] as? Number)?.toLong() ?: 0)
        }
    }
}

/**
 * The shape of an item in Firestore (users/{uid}/items/{id}), within the limits the security rules accept.
 * Android keeps the full text locally; the cloud copy is trimmed to fit, and [sameInCloud] stops the trimmed
 * copy from coming back and overwriting the local one.
 */
object CloudItem {
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    const val MAX_TITLE = 120
    const val MAX_DETAILS = 2000

    fun canSync(item: Item): Boolean = item.status != ItemStatus.DRAFT && ID.matches(item.id)

    /** The item as the cloud stores it. */
    fun clamp(item: Item): Item = item.copy(
        title = item.title.trim().take(MAX_TITLE).ifEmpty { "(không tiêu đề)" },
        details = item.details.take(MAX_DETAILS),
        amount = item.amount?.takeIf { item.type == ItemType.EXPENSE && it in 1..1_000_000_000_000 },
        category = item.category?.take(40),
        place = item.place?.take(120),
        person = item.person?.take(120),
        sourceId = item.sourceId?.takeIf { ID.matches(it) },
        quote = item.quote?.take(400),
        recurrence = item.recurrence.takeIf { item.type == ItemType.TASK || item.type == ItemType.EVENT },
        tag = item.itemTag?.key.takeIf { item.type.canHaveTag() },
        checklist = if (item.type.canChecklist()) {
            item.checklist.take(Checklist.MAX_ENTRIES)
                .map { it.copy(text = it.text.trim().take(Checklist.MAX_TEXT)) }.filter { it.text.isNotEmpty() }
        } else {
            emptyList()
        },
    ).let { NoteKinds.clean(it) }

    /**
     * Fields to write, merged into the stored document so fields this app does not know (the web's customer
     * and licence links) survive. A null value means "remove the field".
     */
    fun toMap(item: Item): Map<String, Any?> {
        val c = clamp(item)
        return linkedMapOf(
            "id" to c.id,
            "type" to c.type.name,
            "status" to c.status.name,
            "title" to c.title,
            "details" to c.details,
            "whenAt" to c.whenAt,
            "allDay" to c.allDay,
            "amount" to c.amount,
            "category" to c.category,
            "place" to c.place,
            "person" to c.person,
            "sourceId" to c.sourceId,
            "quote" to c.quote,
            "createdAt" to c.createdAt,
            "doneAt" to c.doneAt,
            "recurrence" to c.recurrence?.name,
            "important" to if (c.important) true else null,
            "tag" to c.tag,
            "checklist" to c.checklist.takeIf { it.isNotEmpty() }?.let { Checklist.toCloud(it) },
            "kind" to c.kind,
            "url" to c.url,
            "attrs" to c.attrs.takeIf { it.isNotEmpty() },
        )
    }

    fun fromMap(id: String, m: Map<String, Any?>): Item? {
        val type = ItemType.entries.firstOrNull { it.name == m["type"] } ?: return null
        val status = ItemStatus.entries.firstOrNull { it.name == m["status"] } ?: return null
        return Item(
            id = id,
            type = type,
            status = status,
            title = m["title"] as? String ?: return null,
            details = m["details"] as? String ?: "",
            whenAt = (m["whenAt"] as? Number)?.toLong(),
            allDay = m["allDay"] as? Boolean ?: false,
            amount = (m["amount"] as? Number)?.toLong(),
            category = m["category"] as? String,
            place = m["place"] as? String,
            person = m["person"] as? String,
            sourceId = m["sourceId"] as? String,
            quote = m["quote"] as? String,
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: 0,
            doneAt = (m["doneAt"] as? Number)?.toLong(),
            recurrence = (m["recurrence"] as? String)?.let { r -> Recurrence.entries.firstOrNull { it.name == r } },
            important = m["important"] as? Boolean ?: false,
            tag = ItemTag.fromKey(m["tag"] as? String)?.key?.takeIf { type.canHaveTag() },
            checklist = if (type.canChecklist()) Checklist.fromCloud(m["checklist"]) else emptyList(),
            kind = NoteKind.fromKey(m["kind"] as? String)?.key?.takeIf { type == ItemType.NOTE },
            url = (m["url"] as? String)?.takeIf { type == ItemType.NOTE },
            attrs = if (type == ItemType.NOTE) (m["attrs"] as? Map<*, *>)?.entries
                ?.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }?.toMap().orEmpty() else emptyMap(),
        )
    }

    /** True when [remote] is just the cloud copy of [local] (nothing to apply). */
    fun sameInCloud(local: Item?, remote: Item): Boolean = local != null && (local == remote || clamp(local) == remote)
}
