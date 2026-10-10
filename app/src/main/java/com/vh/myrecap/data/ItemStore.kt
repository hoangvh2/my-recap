package com.vh.myrecap.data

import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.Schedule
import com.vh.myrecap.core.TextSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.UUID

/**
 * The secretary's items, stored in SQLite (Room). Blocking calls: use them from a background
 * thread (Room refuses the main thread). The UI observes [items], which updates after every write.
 *
 * [legacyJson] is the v0.2 JSON file; its items are imported once, then it is renamed so the
 * import never runs twice.
 */
class ItemStore(
    private val db: AppDatabase,
    scope: CoroutineScope,
    private val legacyJson: File? = null,
) {
    private val dao = db.items()
    private val _ready = MutableStateFlow(false)

    /** True once the first list was read, so screens can tell "not loaded yet" from "not found". */
    val ready: StateFlow<Boolean> = _ready

    /** All items, newest first. */
    val items: StateFlow<List<Item>> = dao.observeAll()
        .map { rows -> rows.map { it.toItem() } }
        .onEach { _ready.value = true }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Imports the old JSON list once; safe to call on every start. */
    fun migrateLegacy() {
        val file = legacyJson?.takeIf { it.exists() } ?: return
        val imported = try {
            val arr = JSONObject(file.readText()).optJSONArray("items")
            (0 until (arr?.length() ?: 0)).mapNotNull { i -> runCatching { Item.fromJson(arr!!.getJSONObject(i)) }.getOrNull() }
        } catch (_: Exception) {
            emptyList()
        }
        dao.upsertAll(imported.map(ItemEntity::of))
        file.renameTo(File(file.parentFile, file.name + ".imported"))
    }

    fun list(): List<Item> = dao.all().map { it.toItem() }

    fun get(id: String): Item? = dao.get(id)?.toItem()

    fun bySource(sourceId: String): List<Item> = dao.bySource(sourceId).map { it.toItem() }

    fun upsert(item: Item) = dao.upsert(ItemEntity.of(item))

    /** Applies [transform] atomically; returns the new item, or null when it does not exist. */
    fun update(id: String, transform: (Item) -> Item): Item? = db.runInTransaction<Item?> {
        val current = dao.get(id)?.toItem() ?: return@runInTransaction null
        transform(current).also { dao.upsert(ItemEntity.of(it)) }
    }

    fun delete(id: String) = dao.delete(id)

    /**
     * Marks an item done; a repeating task gets its next instance in the same transaction.
     * Returns the done item and the new one (if any), or null when the item does not exist.
     */
    fun complete(id: String, now: Long = System.currentTimeMillis()): Pair<Item, Item?>? = db.runInTransaction<Pair<Item, Item?>?> {
        val current = dao.get(id)?.toItem() ?: return@runInTransaction null
        if (current.status == ItemStatus.DONE) return@runInTransaction current to null
        val done = current.copy(status = ItemStatus.DONE, doneAt = now)
        dao.upsert(ItemEntity.of(done))
        val next = Schedule.nextInstance(done, newId(), ZoneId.systemDefault(), now)
        next?.let { dao.upsert(ItemEntity.of(it)) }
        done to next
    }

    /** Moves repeating appointments whose occurrence is over to their next one. Returns the moved items. */
    fun rollRecurring(now: Long = System.currentTimeMillis()): List<Item> = db.runInTransaction<List<Item>> {
        dao.openRecurring().mapNotNull { Schedule.rollForward(it.toItem(), ZoneId.systemDefault(), now) }
            .onEach { dao.upsert(ItemEntity.of(it)) }
    }

    /** Replaces the AI proposals of one capture (a re-analysis must not stack duplicates). */
    fun replaceDrafts(sourceId: String, drafts: List<Item>) = dao.replaceDrafts(sourceId, drafts.map(ItemEntity::of))

    fun deleteDrafts(sourceId: String) = dao.deleteDrafts(sourceId)

    /** Accent-insensitive: every word of [query] must appear in the item. */
    fun search(query: String): List<Item> {
        val tokens = TextSearch.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        return dao.searchCandidates(tokens.maxBy { it.length })
            .filter { TextSearch.matches(it.search, tokens) }
            .map { it.toItem() }
    }

    companion object {
        fun newId(): String = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6)
    }
}
