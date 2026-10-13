package com.vh.myrecap.cloud

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.ui.MainActivity

/**
 * Pushes about sharing ("Anh An chia sẻ: …", "… đã sửa: …", "X đã huỷ liên kết"). The server sends data only, so
 * the app picks the channel and what a tap opens: the shared item, or the contacts screen.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        MyRecapApp.from(this).cloud.onNewToken(token)
    }

    @SuppressLint("MissingPermission") // SecurityException handled below.
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: return
        val body = data["body"].orEmpty()
        val shareId = data["shareId"]?.takeIf { data["kind"] != "deleted" }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (shareId != null) putExtra(MainActivity.EXTRA_SHARE_ID, shareId) else action = MainActivity.ACTION_CONTACTS }
        val tag = shareId ?: data["kind"] ?: "share"
        val pending = PendingIntent.getActivity(this, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, MyRecapApp.CHANNEL_SHARED)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            // One notification per shared item: a second change replaces the first.
            NotificationManagerCompat.from(this).notify(tag, NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications not allowed: the change is still in the app.
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 7300
    }
}
