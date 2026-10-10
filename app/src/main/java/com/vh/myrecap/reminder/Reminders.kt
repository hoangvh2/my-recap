package com.vh.myrecap.reminder

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemText
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.ui.MainActivity
import com.vh.myrecap.core.ReminderPolicy
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Reminders for confirmed tasks and appointments, via AlarmManager so they fire with the app closed.
 * Exact when the user allowed "Alarms & reminders", otherwise inexact (Android may delay a few minutes).
 */
object Reminders {
    /** When the reminder for [item] should fire, or null when it needs none. */
    fun triggerAt(item: Item, zone: ZoneId = ZoneId.systemDefault()): Long? = ReminderPolicy.triggerAt(item, zone)

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Sets, moves or cancels the reminder to match the item's current state. */
    fun sync(context: Context, item: Item) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, item.id)
        am.cancel(pending)
        val at = triggerAt(item) ?: return
        if (at <= System.currentTimeMillis()) return
        try {
            if (canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, itemId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, itemId))
        NotificationManagerCompat.from(context).cancel(notificationId(itemId))
    }

    /** After boot, a time change or an app update, alarms are gone: set them again. */
    fun syncAll(context: Context) {
        val items = MyRecapApp.from(context).items
        items.rollRecurring()
        items.list().forEach { sync(context, it) }
    }

    private fun alarmIntent(context: Context, itemId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        itemId.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ITEM, itemId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun notificationId(itemId: String) = 0x5000_0000 xor itemId.hashCode()

    @SuppressLint("MissingPermission") // SecurityException handled below.
    internal fun notify(context: Context, item: Item) {
        val open = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ITEM_ID, item.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = listOfNotNull(
            ItemText.whenText(item, ZoneId.systemDefault()),
            item.recurrence?.label,
            item.place,
            item.details.ifBlank { null },
        )
            .joinToString(" · ")
        val builder = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (item.type == ItemType.EVENT) "Sắp tới: ${item.title}" else item.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (item.type == ItemType.EVENT) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (item.type == ItemType.TASK) {
            val done = PendingIntent.getBroadcast(
                context,
                item.id.hashCode() + 1,
                Intent(context, ReminderReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_ITEM, item.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Xong", done)
        }
        try {
            NotificationManagerCompat.from(context).notify(notificationId(item.id), builder.build())
        } catch (_: SecurityException) {
            // Notifications disabled: the item still shows as due in the app.
        }
    }

    const val ACTION_FIRE = "com.vh.myrecap.REMINDER"
    const val ACTION_DONE = "com.vh.myrecap.REMINDER_DONE"
    const val EXTRA_ITEM = "itemId"
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Database work must leave the main thread; goAsync keeps the process alive meanwhile.
        val pending = goAsync()
        val app = MyRecapApp.from(context)
        app.appScope.launch {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val items = MyRecapApp.from(context).items
        when (intent.action) {
            Reminders.ACTION_FIRE -> {
                val item = intent.getStringExtra(Reminders.EXTRA_ITEM)?.let { items.get(it) } ?: return
                if (item.status == ItemStatus.OPEN) {
                    Reminders.notify(context, item)
                    // A repeating appointment chains to its next occurrence.
                    if (item.recurrence != null) Reminders.sync(context, item)
                }
            }
            Reminders.ACTION_DONE -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ITEM) ?: return
                val next = items.complete(id)?.second
                Reminders.cancel(context, id)
                next?.let { Reminders.sync(context, it) } // a repeating task comes back for its next date
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> Reminders.syncAll(context)
        }
    }
}
