package com.vh.myrecap.core

/** What a pinned checklist shows in its notification. [tickId]/[tickLabel]: the first line still missing, one tap to tick it. */
data class PinView(
    val title: String,
    /** "Còn 3/5", or "Đủ 5/5" once everything is ticked (words, not only colour). */
    val summary: String,
    /** The lines still missing (at most [PinnedChecklist.MAX_LINES]) plus a "+N nữa" line when there are more. */
    val lines: List<String>,
    val tickId: String?,
    val tickLabel: String?,
    val remaining: Int,
    val complete: Boolean,
)

/**
 * A checklist pinned to the notification shade ("ra khỏi nhà: ví, chìa khoá, sạc"). Pure rules; the notification
 * itself lives in the app. A pinned item that is done, deleted or without a checklist is no longer pinned.
 */
object PinnedChecklist {
    const val MAX_LINES = 6
    private const val MAX_TITLE = 60
    private const val MAX_TICK_LABEL = 14

    /** Only an open task or appointment that still has a checklist can stay pinned. */
    fun eligible(item: Item?): Boolean = item != null && item.status == ItemStatus.OPEN && Checklist.has(item)

    fun view(item: Item): PinView? {
        if (!eligible(item)) return null
        val p = Checklist.progress(item)
        val shown = p.remaining.take(MAX_LINES).map { it.text }
        val more = p.remaining.size - shown.size
        val first = p.remaining.firstOrNull()
        return PinView(
            title = item.title.trim().ifEmpty { "Checklist" }.take(MAX_TITLE),
            summary = if (p.complete) "Đủ ${p.total}/${p.total}" else "Còn ${p.remaining.size}/${p.total}",
            lines = if (more > 0) shown + "+$more nữa" else shown,
            tickId = first?.id,
            tickLabel = first?.let { "✓ " + clip(it.text) },
            remaining = p.remaining.size,
            complete = p.complete,
        )
    }

    private fun clip(text: String): String = if (text.length <= MAX_TICK_LABEL) text else text.take(MAX_TICK_LABEL - 1).trimEnd() + "…"

    /** Ticks one line from the notification; true in [second] when that was the last missing line. */
    fun tick(item: Item, entryId: String): Pair<Item, Boolean> {
        val entry = Checklist.entries(item).firstOrNull { it.id == entryId && !it.done } ?: return item to false
        val next = Checklist.toggle(item, entry.id)
        return next to Checklist.progress(next).complete
    }

    /** Undo of the tick that finished the list: that one line is missing again and the item is open. */
    fun reopen(item: Item, entryId: String): Item {
        val entry = Checklist.entries(item).firstOrNull { it.id == entryId && it.done } ?: return item
        val unticked = Checklist.toggle(item, entry.id)
        return if (unticked.status == ItemStatus.DONE) unticked.copy(status = ItemStatus.OPEN, doneAt = null) else unticked
    }
}
