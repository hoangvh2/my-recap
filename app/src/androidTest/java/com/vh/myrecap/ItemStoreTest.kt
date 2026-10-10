package com.vh.myrecap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.data.AppDatabase
import com.vh.myrecap.data.ItemStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Room-backed item store: legacy import, atomic draft replacement, accent-insensitive search. */
@RunWith(AndroidJUnit4::class)
class ItemStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var legacy: File

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        legacy = File(context.cacheDir, "legacy-test/items.json").apply { parentFile!!.mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        legacy.parentFile!!.deleteRecursively()
    }

    private fun item(id: String, title: String, status: ItemStatus = ItemStatus.OPEN, source: String? = null, type: ItemType = ItemType.TASK) =
        Item(id, type, status, title, sourceId = source, createdAt = id.hashCode().toLong())

    @Test
    fun importsLegacyJsonOnce() {
        val old = listOf(item("a", "Gửi báo giá"), item("b", "Ăn trưa", type = ItemType.EXPENSE).copy(amount = 65_000))
        legacy.writeText(JSONObject().put("version", 1).put("items", JSONArray().also { a -> old.forEach { a.put(it.toJson()) } }).toString())
        val store = ItemStore(db, scope, legacy)
        store.migrateLegacy()
        assertEquals(2, store.list().size)
        assertEquals(65_000L, store.get("b")?.amount)
        assertFalse("legacy file renamed", legacy.exists())
        assertTrue(File(legacy.parentFile, "items.json.imported").exists())
        store.migrateLegacy() // no file any more: nothing happens
        assertEquals(2, store.list().size)
    }

    @Test
    fun replaceDraftsKeepsConfirmedItemsAndOtherCaptures() {
        val store = ItemStore(db, scope)
        store.upsert(item("kept", "Đã lưu", source = "m1"))
        store.upsert(item("d1", "Đề xuất cũ", ItemStatus.DRAFT, "m1"))
        store.upsert(item("other", "Đề xuất khác", ItemStatus.DRAFT, "m2"))
        store.replaceDrafts("m1", listOf(item("d2", "Đề xuất mới", ItemStatus.DRAFT, "m1")))
        assertEquals(setOf("kept", "d2"), store.bySource("m1").map { it.id }.toSet())
        assertEquals(listOf("other"), store.bySource("m2").map { it.id })
        store.deleteDrafts("m1")
        assertEquals(listOf("kept"), store.bySource("m1").map { it.id })
    }

    @Test
    fun updateIsAtomicAndReportsMissingItems() {
        val store = ItemStore(db, scope)
        store.upsert(item("t", "Việc"))
        val done = store.update("t") { it.copy(status = ItemStatus.DONE, doneAt = 5) }
        assertEquals(ItemStatus.DONE, done?.status)
        assertEquals(ItemStatus.DONE, store.get("t")?.status)
        assertNull(store.update("missing") { it })
    }

    @Test
    fun searchIgnoresAccentsAndWordOrder() {
        val store = ItemStore(db, scope)
        store.upsert(item("1", "Gửi báo giá cho khách").copy(details = "Công ty Đông Á"))
        store.upsert(item("2", "Họp với anh Nam").copy(place = "Văn phòng"))
        assertEquals(listOf("1"), store.search("bao gia").map { it.id })
        assertEquals(listOf("1"), store.search("DONG a khach").map { it.id })
        assertEquals(listOf("2"), store.search("van phong nam").map { it.id })
        assertTrue(store.search("không có").isEmpty())
        assertTrue(store.search("   ").isEmpty())
    }

    @Test
    fun observedListFollowsWrites() {
        runBlocking {
            val store = ItemStore(db, scope)
            store.upsert(item("x", "Một"))
            withTimeout(5_000) { store.items.first { list -> list.any { it.id == "x" } } }
            assertTrue(store.ready.value)
            store.delete("x")
            withTimeout(5_000) { store.items.first { list -> list.none { it.id == "x" } } }
        }
    }
}
