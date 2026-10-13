package com.vh.myrecap.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.vh.myrecap.BuildConfig
import com.vh.myrecap.core.Item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.vh.myrecap.data.ItemStore

/** Who is signed in to sync and sharing. [Off]: this build has no Firebase settings (phone-only, as before). */
sealed interface Account {
    data object Off : Account
    data object SignedOut : Account
    data class SignedIn(val uid: String, val email: String, val name: String) : Account
}

/**
 * The optional cloud side: Google sign-in, the two-way copy of items in Firestore (shared with the web app on the
 * same account), and sharing with linked people. Without the Firebase build settings nothing here starts and the
 * app keeps working on the phone alone.
 */
class Cloud(
    private val context: Context,
    private val items: ItemStore,
    private val scope: CoroutineScope,
    /** Called with items another device added or changed, and ids it removed (reminders follow). */
    onRemoteChange: (List<Item>, Collection<String>) -> Unit,
) {
    val enabled: Boolean = listOf(
        BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_SENDER_ID, BuildConfig.FIREBASE_WEB_CLIENT_ID,
    ).all { it.isNotBlank() }

    private val firebase: FirebaseApp? = if (!enabled) null else try {
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME } ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .setStorageBucket("${BuildConfig.FIREBASE_PROJECT_ID}.appspot.com")
                .build(),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Firebase did not start", e)
        null
    }

    private val auth: FirebaseAuth? = firebase?.let { FirebaseAuth.getInstance(it) }
    private val db: FirebaseFirestore? = firebase?.let { FirebaseFirestore.getInstance(it) }
    private val functions: FirebaseFunctions? = firebase?.let { FirebaseFunctions.getInstance(it, BuildConfig.FUNCTIONS_REGION) }
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow<Account>(if (firebase == null) Account.Off else Account.SignedOut)
    val account: StateFlow<Account> = _account

    /** Something the person must know about the account (not allowed in, sign-in failed). */
    val problem = MutableStateFlow<String?>(null)

    val sync: ItemSync? = db?.let { ItemSync(it, items, prefs, scope, onRemoteChange, onDenied = ::denied) }
    val sharing: ShareRepo? = if (db != null && functions != null) ShareRepo(db, functions) else null

    init {
        auth?.addAuthStateListener { a ->
            val user = a.currentUser
            if (user == null) {
                sync?.stop()
                sharing?.stop()
                _account.value = Account.SignedOut
            } else if ((account.value as? Account.SignedIn)?.uid != user.uid) {
                _account.value = Account.SignedIn(user.uid, user.email.orEmpty(), user.displayName ?: user.email.orEmpty())
                sync?.start(user.uid)
                sharing?.start(user.uid)
                scope.launch { registerPush(user.uid) }
            }
        }
    }

    val uid: String? get() = (account.value as? Account.SignedIn)?.uid

    /** Google sign-in with the phone's account picker. Returns a message for the person, or null (done or cancelled). */
    suspend fun signIn(activity: Activity): String? {
        val auth = auth ?: return "Bản cài này chưa cấu hình đồng bộ"
        return try {
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.FIREBASE_WEB_CLIENT_ID).build()
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val credential = result.credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return "Không nhận được tài khoản Google"
            }
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
            problem.value = null
            null
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (_: NoCredentialException) {
            "Máy chưa có tài khoản Google. Thêm tài khoản trong Cài đặt của điện thoại rồi thử lại"
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager failed", e)
            "Đăng nhập Google không thành công (${e.type.substringAfterLast('.')})"
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed", e)
            "Đăng nhập không thành công, kiểm tra mạng rồi thử lại"
        }
    }

    /** Stops syncing; the items stay on this phone. */
    suspend fun signOut() {
        val uid = uid
        if (uid != null) {
            runCatching { sharing?.forgetDevice(uid, deviceId(FirebaseMessaging.getInstance().token.await())) }
            runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
            prefs.edit().remove(ItemSync.mergedKey(uid)).apply()
        }
        runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
        auth?.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    /** A new push token (the system rotates them): remember it for the signed-in account. */
    fun onNewToken(token: String) {
        val uid = uid ?: return
        scope.launch { runCatching { sharing?.saveDevice(uid, deviceId(token), token) } }
    }

    private suspend fun registerPush(uid: String) {
        try {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            val token = messaging.token.await()
            sharing?.saveDevice(uid, deviceId(token), token)
        } catch (e: Exception) {
            Log.w(TAG, "Push registration failed", e)
        }
    }

    /** The account is not on the allowlist: the server refuses everything, so sign out and say why. */
    private fun denied() {
        problem.value = "Tài khoản này chưa được cấp quyền. Nhờ người quản trị thêm Gmail của bạn trong Cài đặt của bản web"
        scope.launch { signOut() }
    }

    companion object {
        private const val TAG = "MyRecapCloud"

        /** One document per token, named by a hash so the token itself is not the id (same as the web). */
        fun deviceId(token: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            return digest.take(20).joinToString("") { "%02x".format(it) }
        }
    }
}
