package com.vh.myrecap.cloud

import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.vh.myrecap.core.CloudItem
import com.vh.myrecap.core.Item
import com.vh.myrecap.data.ItemStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Keeps the phone's items (Room, what every screen reads) and users/{uid}/items in Firestore (what the web app
 * and the sharing trigger read) the same.
 *
 * - Saved here: written to Room, then to Firestore (queued by Firestore while offline, sent when back online).
 * - Changed elsewhere: the snapshot listener writes it to Room. Our own pending writes are skipped.
 * - First sign-in on this phone: once the server's list has arrived, items that exist only here are uploaded.
 *   For an item on both sides, the cloud copy wins.
 */
class ItemSync(
    private val db: FirebaseFirestore,
    private val store: ItemStore,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val onRemoteChange: (List<Item>, Collection<String>) -> Unit,
    private val onDenied: () -> Unit,
) : ItemStore.Sink {
    @Volatile
    private var uid: String? = null
    private var listener: ListenerRegistration? = null
    private val applying = Mutex()

    private fun items(uid: String) = db.collection("users").document(uid).collection("items")

    @Synchronized
    fun start(uid: String) {
        stop()
        this.uid = uid
        store.sink = this
        listener = items(uid).limit(2000).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "Item listener stopped", err)
                if (err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) onDenied()
                return@addSnapshotListener
            }
            if (snap != null) scope.launch { applying.withLock { apply(uid, snap) } }
        }
    }

    @Synchronized
    fun stop() {
        listener?.remove()
        listener = null
        uid = null
        store.sink = null
    }

    private fun apply(uid: String, snap: QuerySnapshot) {
        if (this.uid != uid) return
        val local = store.list().associateBy { it.id }
        val upserts = mutableListOf<Item>()
        val removed = mutableListOf<String>()
        for (change in snap.documentChanges) {
            val doc = change.document
            if (doc.metadata.hasPendingWrites()) continue // our own write: Room already has it
            if (change.type == DocumentChange.Type.REMOVED) {
                if (doc.id in local) removed += doc.id
                continue
            }
            val remote = CloudItem.fromMap(doc.id, doc.data) ?: continue
            if (!CloudItem.sameInCloud(local[doc.id], remote)) upserts += remote
        }
        if (upserts.isNotEmpty() || removed.isNotEmpty()) {
            store.applyRemote(upserts, removed)
            onRemoteChange(upserts, removed)
        }
        if (!snap.metadata.isFromCache && !prefs.getBoolean(mergedKey(uid), false)) {
            val inCloud = snap.documents.mapTo(HashSet()) { it.id }
            val onlyHere = store.list().filter { it.id !in inCloud && CloudItem.canSync(it) }
            onlyHere.forEach(::put)
            prefs.edit().putBoolean(mergedKey(uid), true).apply()
            Log.i(TAG, "First sync: uploaded ${onlyHere.size} items")
        }
    }

    override fun put(item: Item) {
        val uid = uid ?: return
        if (!CloudItem.canSync(item)) return
        items(uid).document(item.id).set(fields(item), SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "Item upload refused", it) }
    }

    override fun remove(id: String) {
        val uid = uid ?: return
        items(uid).document(id).delete().addOnFailureListener { Log.w(TAG, "Item delete refused", it) }
    }

    /** Waits until the server has the item (sharing reads it there). False when offline or refused. */
    suspend fun putAndWait(item: Item): Boolean {
        val uid = uid ?: return false
        if (!CloudItem.canSync(item)) return false
        return try {
            withTimeout(15_000) { items(uid).document(item.id).set(fields(item), SetOptions.merge()).await() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Item upload did not finish", e)
            false
        }
    }

    private fun fields(item: Item): Map<String, Any> = CloudItem.toMap(item).mapValues { (_, v) -> v ?: FieldValue.delete() }

    companion object {
        private const val TAG = "MyRecapSync"
        fun mergedKey(uid: String) = "merged-$uid"
    }
}
