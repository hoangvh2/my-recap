package com.vh.myrecap.cloud

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.vh.myrecap.core.Contact
import com.vh.myrecap.core.ShareNotice
import com.vh.myrecap.core.SharedItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** What a link code would connect me to, shown before I confirm. */
data class CodePreview(val name: String, val maskedEmail: String, val already: Boolean)

/** A refusal or failure worded for the person. */
class ShareException(message: String) : Exception(message)

/**
 * Sharing, seen from this phone (same model as the web app): my contacts, what others share with me, who each of
 * my items is shared with, and the "linked / unlinked" notices. Read live from Firestore; every change goes
 * through the `share` function, except renaming a contact and muting it, which the rules allow directly.
 */
class ShareRepo(private val db: FirebaseFirestore, private val functions: FirebaseFunctions) {
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    private val _received = MutableStateFlow<List<SharedItem>>(emptyList())
    private val _audiences = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val _notices = MutableStateFlow<List<ShareNotice>>(emptyList())

    val contacts: StateFlow<List<Contact>> = _contacts
    /** Read-only copies others shared with me. */
    val received: StateFlow<List<SharedItem>> = _received
    /** My item id → the uids it is shared with. */
    val audiences: StateFlow<Map<String, List<String>>> = _audiences
    val notices: StateFlow<List<ShareNotice>> = _notices

    private val listeners = mutableListOf<ListenerRegistration>()
    private var me: String? = null

    @Synchronized
    fun start(uid: String) {
        stop()
        me = uid
        val user = db.collection("users").document(uid)
        listeners += user.collection("contacts").limit(200).addSnapshotListener { s, e ->
            if (e != null) return@addSnapshotListener log(e)
            _contacts.value = s?.documents?.mapNotNull { Contact.fromMap(it.data.orEmpty()) }?.sortedBy { it.alias.lowercase() }.orEmpty()
        }
        listeners += db.collection("shares").whereArrayContains("audience", uid).limit(500).addSnapshotListener { s, e ->
            if (e != null) return@addSnapshotListener log(e)
            _received.value = s?.documents?.mapNotNull { SharedItem.fromMap(it.data.orEmpty()) }.orEmpty()
        }
        listeners += db.collection("shares").whereEqualTo("ownerUid", uid).limit(500).addSnapshotListener { s, e ->
            if (e != null) return@addSnapshotListener log(e)
            _audiences.value = s?.documents?.associate { d ->
                (d.getString("itemId") ?: "") to ((d.get("audience") as? List<*>)?.filterIsInstance<String>().orEmpty())
            }.orEmpty()
        }
        listeners += user.collection("notices").limit(50).addSnapshotListener { s, e ->
            if (e != null) return@addSnapshotListener log(e)
            _notices.value = s?.documents?.mapNotNull { ShareNotice.fromMap(it.data.orEmpty() + ("id" to it.id)) }?.sortedByDescending { it.at }.orEmpty()
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach { it.remove() }
        listeners.clear()
        me = null
        _contacts.value = emptyList()
        _received.value = emptyList()
        _audiences.value = emptyMap()
        _notices.value = emptyList()
    }

    // ------------------------------------------------------------ the function

    private suspend fun call(data: Map<String, Any>): Map<*, *> {
        try {
            val callable = functions.getHttpsCallable("share").apply { setTimeout(60, TimeUnit.SECONDS) }
            return callable.call(data).await().getData() as? Map<*, *> ?: emptyMap<String, Any>()
        } catch (e: FirebaseFunctionsException) {
            val msg = e.message.orEmpty()
            // The function's own refusals carry a Vietnamese sentence; bare codes ("internal") do not.
            if (e.code != FirebaseFunctionsException.Code.INTERNAL && e.code != FirebaseFunctionsException.Code.UNAVAILABLE &&
                msg.isNotBlank() && !msg.matches(Regex("^[A-Za-z _-]+$"))
            ) {
                throw ShareException(msg)
            }
            throw ShareException(
                when (e.code) {
                    FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Phiên đăng nhập đã hết hạn, hãy đăng nhập lại"
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> "Tài khoản này chưa được cấp quyền"
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> "Xử lý quá lâu, thử lại"
                    else -> "Không kết nối được, kiểm tra mạng rồi thử lại"
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "share call failed", e)
            throw ShareException("Không kết nối được, kiểm tra mạng rồi thử lại")
        }
    }

    /** A new 8-character code (the previous one stops working) and when it expires. */
    suspend fun createCode(): Pair<String, Long> {
        val r = call(mapOf("action" to "linkCreate"))
        return (r["code"] as? String ?: throw ShareException("Không tạo được mã")) to ((r["expiresAt"] as? Number)?.toLong() ?: 0L)
    }

    suspend fun previewCode(code: String): CodePreview {
        val r = call(mapOf("action" to "linkPreview", "code" to code))
        return CodePreview(r["name"] as? String ?: "", r["maskedEmail"] as? String ?: "", r["already"] as? Boolean ?: false)
    }

    suspend fun joinCode(code: String, alias: String) {
        call(mapOf("action" to "linkJoin", "code" to code, "alias" to alias))
    }

    /** Unlinks both ways at once; the other person is told. */
    suspend fun unlink(uid: String) {
        call(mapOf("action" to "linkRemove", "uid" to uid))
    }

    /** Who sees my item from now on (empty = nobody). The item must already be on the server. */
    suspend fun setAudience(itemId: String, audience: List<String>): List<String> {
        val r = call(mapOf("action" to "shareSet", "itemId" to itemId, "audience" to audience))
        return (r["audience"] as? List<*>)?.filterIsInstance<String>() ?: audience
    }

    // ------------------------------------------------------------ direct writes the rules allow

    private fun contactRef(c: Contact) = db.collection("users").document(me ?: throw ShareException("Chưa đăng nhập"))
        .collection("contacts").document(c.uid)

    suspend fun rename(c: Contact, alias: String) = write { contactRef(c).update(mapOf("alias" to alias, "notify" to c.notify)).await() }

    suspend fun setNotify(c: Contact, notify: Boolean) = write { contactRef(c).update(mapOf("alias" to c.alias, "notify" to notify)).await() }

    suspend fun dismiss(id: String) = write {
        db.collection("users").document(me ?: return@write).collection("notices").document(id).delete().await()
    }

    suspend fun saveDevice(uid: String, id: String, token: String) {
        db.collection("users").document(uid).collection("devices").document(id)
            .set(mapOf("token" to token, "platform" to "android", "updatedAt" to System.currentTimeMillis())).await()
    }

    suspend fun forgetDevice(uid: String, id: String) {
        db.collection("users").document(uid).collection("devices").document(id).delete().await()
    }

    private suspend fun write(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ShareException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
            throw ShareException("Chưa lưu được, kiểm tra mạng rồi thử lại")
        }
    }

    private fun log(e: Exception) {
        Log.w(TAG, "listener stopped", e)
    }

    companion object {
        private const val TAG = "MyRecapShare"
    }
}
