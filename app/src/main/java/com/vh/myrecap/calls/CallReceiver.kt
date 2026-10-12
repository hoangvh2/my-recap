package com.vh.myrecap.calls

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.CallNotes
import com.vh.myrecap.core.CallState
import com.vh.myrecap.core.CallTrack
import com.vh.myrecap.core.EndedCall
import com.vh.myrecap.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Watches phone-call state (only when the user turned the after-call prompt on) and, once a real
 * conversation ends, posts a notification offering to record a short recap. The call audio itself is
 * never available to apps; nothing is recorded or sent unless the user taps the notification.
 */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISS -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> onPhoneState(context, intent)
        }
    }

    private fun onPhoneState(context: Context, intent: Intent) {
        val app = MyRecapApp.from(context)
        if (!app.settings.current.callFollowUp) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallState.RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallState.OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> CallState.IDLE
            else -> return
        }
        val (track, ended) = CallNotes.step(load(context), state, System.currentTimeMillis())
        save(context, track)
        if (ended == null) return
        // The call log entry is written just after the call ends; wait briefly so the prompt can name the caller.
        val pending = goAsync()
        app.appScope.launch {
            try {
                delay(LOOKUP_DELAY_MS)
                val caller = lookUpCaller(context, ended) ?: run {
                    delay(LOOKUP_DELAY_MS)
                    lookUpCaller(context, ended)
                }
                notify(context, ended, caller?.first, caller?.second)
            } catch (e: Exception) {
                Log.e("MyRecap", "After-call prompt failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    /** Name and number of the call that started around [call]'s start; null without call-log access. */
    private fun lookUpCaller(context: Context, call: EndedCall): Pair<String?, String?>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null to null
        }
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} >= ?",
                // The log's start time is when the call was placed or rang, slightly before pick-up.
                arrayOf((call.startedAt - CALL_LOG_WINDOW_MS).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
        } catch (_: SecurityException) {
            null to null
        }
    }

    private fun notify(context: Context, call: EndedCall, name: String?, number: String?) {
        val zone = ZoneId.systemDefault()
        val title = CallNotes.title(call, name, number, zone)
        val record = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CALL_NOTE)
            .putExtra(MainActivity.EXTRA_NOTE_TITLE, title)
            .putExtra(MainActivity.EXTRA_NOTE_CONTEXT, CallNotes.context(call, name, number, zone))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, record, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, CallReceiver::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(CallNotes.prompt(call))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .addAction(0, "Ghi tóm tắt", open)
            .addAction(0, "Bỏ qua", dismiss)
            .setAutoCancel(true)
            // A recap is only useful while the call is fresh.
            .setTimeoutAfter(PROMPT_TIMEOUT_MS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications disabled: nothing to offer.
        }
    }

    private fun load(context: Context): CallTrack {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CallTrack(
            state = CallState.entries.firstOrNull { it.name == p.getString("state", null) } ?: CallState.IDLE,
            offhookAt = p.getLong("offhookAt", 0),
            incoming = p.getBoolean("incoming", false),
        )
    }

    private fun save(context: Context, track: CallTrack) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", track.state.name)
            .putLong("offhookAt", track.offhookAt)
            .putBoolean("incoming", track.incoming)
            .apply()
    }

    companion object {
        const val NOTIFICATION_ID = 4_201
        private const val ACTION_DISMISS = "com.vh.myrecap.DISMISS_CALL_NOTE"
        private const val PREFS = "calls"
        private const val LOOKUP_DELAY_MS = 1_500L
        private const val CALL_LOG_WINDOW_MS = 2 * 60_000L
        private const val PROMPT_TIMEOUT_MS = 2 * 60 * 60_000L

        /** Permissions the prompt needs; only the phone state one is required, the call log adds the caller's name. */
        val PERMISSIONS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)

        fun canWatchCalls(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
}
