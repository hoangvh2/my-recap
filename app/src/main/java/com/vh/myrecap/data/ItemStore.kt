package com.vh.myrecap.data

import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The secretary's items in one small JSON file. A personal list stays in the hundreds to low
 * thousands of short records (~300 bytes each), so a full rewrite per change is cheap and keeps
 * the file readable and crash-safe (atomic rename). Kept in memory after the first read.
 */
class ItemStore(private val file: File) {
    private val lock = Any()
    private var cache: List<Item>? = null
    private val _items = MutableStateFlow<List<Item>>(emptyList())

    /** All items, newest first; updated on every write. */
    val items: StateFlow<List<Item>> = _items

    init {
        file.parentFile?.mkdirs()
        synchronized(lock) { _items.value = load() }
    }

    fun list(): List<Item> = synchronized(lock) { load() }

    fun get(id: String): Item? = list().firstOrNull { it.id == id }

    fun bySource(sourceId: String): List<Item> = list().filter { it.sourceId == sourceId }

    fun upsert(item: Item) = mutate { all ->
        if (all.any { it.id == item.id }) all.map { if (it.id == item.id) item else it } else listOf(item) + all
    }

    fun update(id: String, transform: (Item) -> Item): Item? {
        var result: Item? = null
        mutate { all ->
            all.map {
                if (it.id == id) transform(it).also { n -> result = n } else it
            }
        }
        return result
    }

    fun delete(id: String) = mutate { all -> all.filterNot { it.id == id } }

    /** Replaces the AI proposals of one capture (a re-analysis must not stack duplicates). */
    fun replaceDrafts(sourceId: String, drafts: List<Item>) = mutate { all ->
        drafts + all.filterNot { it.sourceId == sourceId && it.status == ItemStatus.DRAFT }
    }

    fun deleteDrafts(sourceId: String) = mutate { all ->
        all.filterNot { it.sourceId == sourceId && it.status == ItemStatus.DRAFT }
    }

    private fun mutate(transform: (List<Item>) -> List<Item>) {
        synchronized(lock) {
            val next = transform(load())
            save(next)
            cache = next
            _items.value = next
        }
    }

    private fun load(): List<Item> {
        cache?.let { return it }
        val loaded = try {
            if (!file.exists()) {
                emptyList()
            } else {
                val arr = JSONObject(file.readText()).optJSONArray("items") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> runCatching { Item.fromJson(arr.getJSONObject(i)) }.getOrNull() }
            }
        } catch (_: Exception) {
            // Keep the unreadable file for inspection instead of overwriting it with an empty list.
            file.renameTo(File(file.parentFile, file.name + ".broken-" + System.currentTimeMillis()))
            emptyList()
        }
        cache = loaded
        return loaded
    }

    private fun save(items: List<Item>) {
        val json = JSONObject().put("version", 1).put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        fun newId(): String = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6)
    }
}
