package com.vh.myrecap.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.TextSearch
import kotlinx.coroutines.flow.Flow

/** Row of the secretary's items. [search] holds the accent-folded text that search matches against. */
@Entity(
    tableName = "items",
    indices = [Index("sourceId"), Index("status"), Index("whenAt"), Index("type")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val type: String,
    val status: String,
    val title: String,
    val details: String,
    val whenAt: Long?,
    val allDay: Boolean,
    val amount: Long?,
    val category: String?,
    val place: String?,
    val person: String?,
    val sourceId: String?,
    val quote: String?,
    val createdAt: Long,
    val doneAt: Long?,
    val search: String,
) {
    fun toItem() = Item(
        id = id,
        type = ItemType.entries.firstOrNull { it.name == type } ?: ItemType.NOTE,
        status = ItemStatus.entries.firstOrNull { it.name == status } ?: ItemStatus.OPEN,
        title = title,
        details = details,
        whenAt = whenAt,
        allDay = allDay,
        amount = amount,
        category = category,
        place = place,
        person = person,
        sourceId = sourceId,
        quote = quote,
        createdAt = createdAt,
        doneAt = doneAt,
    )

    companion object {
        fun of(item: Item) = ItemEntity(
            id = item.id,
            type = item.type.name,
            status = item.status.name,
            title = item.title,
            details = item.details,
            whenAt = item.whenAt,
            allDay = item.allDay,
            amount = item.amount,
            category = item.category,
            place = item.place,
            person = item.person,
            sourceId = item.sourceId,
            quote = item.quote,
            createdAt = item.createdAt,
            doneAt = item.doneAt,
            search = TextSearch.fold(listOfNotNull(item.title, item.details, item.category, item.place, item.person, item.quote).joinToString(" ")),
        )
    }
}

@Dao
abstract class ItemDao {
    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    abstract fun all(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    abstract fun get(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE sourceId = :sourceId ORDER BY createdAt DESC")
    abstract fun bySource(sourceId: String): List<ItemEntity>

    /** Rows containing [firstToken]; the caller checks the remaining words. */
    @Query("SELECT * FROM items WHERE search LIKE '%' || :firstToken || '%' ORDER BY createdAt DESC LIMIT 200")
    abstract fun searchCandidates(firstToken: String): List<ItemEntity>

    @Upsert
    abstract fun upsert(item: ItemEntity)

    @Upsert
    abstract fun upsertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id = :id")
    abstract fun delete(id: String)

    @Query("DELETE FROM items WHERE sourceId = :sourceId AND status = 'DRAFT'")
    abstract fun deleteDrafts(sourceId: String)

    @Query("SELECT COUNT(*) FROM items")
    abstract fun count(): Int

    @Transaction
    open fun replaceDrafts(sourceId: String, drafts: List<ItemEntity>) {
        deleteDrafts(sourceId)
        upsertAll(drafts)
    }
}

/**
 * Structured data (the secretary's items). Recordings and transcripts stay as files: they are
 * large, written by the recorder service, and easy to salvage after a crash.
 *
 * Schema changes must add a [androidx.room.migration.Migration]; there is deliberately no
 * destructive fallback, so a missing migration fails loudly instead of wiping the user's data.
 */
@Database(entities = [ItemEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    companion object {
        fun open(context: Context, name: String = "myrecap.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name).build()

        /** For tests. */
        fun inMemory(context: Context): AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }
}
