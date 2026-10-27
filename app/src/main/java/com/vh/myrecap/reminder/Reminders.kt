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
import com.vh.myrecap.core.Checklist
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemText
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.core.StudyPlan
import com.vh.myrecap.study.StudyEngine
import com.vh.myrecap.study.StudyService
import com.vh.myrecap.ui.MainActivity
import com.vh.myrecap.core.ReminderPolicy
import com.vh.myrecap.core.ReminderPolicy.Snooze
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
        if (item.status != ItemStatus.OPEN) clearFollowUp(context, item.id)
        val at = triggerAt(item) ?: return
        if (at <= System.currentTimeMillis()) return
        setAlarm(context, am, at, pending)
    }

    internal fun setAlarm(context: Context, am: AlarmManager, at: Long, pending: PendingIntent) {
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
        clearFollowUp(context, itemId)
        NotificationManagerCompat.from(context).cancel(notificationId(itemId))
    }

    // A follow-up is a second alarm per item: a snoozed reminder, or the next repeat of an important
    // one nobody acknowledged. Kept in preferences ("itemId" -> "atMillis:repeatsSoFar") so it survives
    // a reboot; the item itself is untouched.
    private fun prefs(context: Context) = context.getSharedPreferences("reminder_followups", Context.MODE_PRIVATE)

    private fun followUpIntent(context: Context, itemId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        itemId.hashCode() + 2,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FOLLOW_UP).putExtra(EXTRA_ITEM, itemId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal fun scheduleFollowUp(context: Context, itemId: String, at: Long, repeats: Int) {
        prefs(context).edit().putString(itemId, "$at:$repeats").apply()
        setAlarm(context, context.getSystemService(AlarmManager::class.java), at, followUpIntent(context, itemId))
    }

    internal fun clearFollowUp(context: Context, itemId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(followUpIntent(context, itemId))
        prefs(context).edit().remove(itemId).apply()
    }

    /** Repeats so far for a pending follow-up, 0 when there is none. */
    internal fun repeatsSoFar(context: Context, itemId: String): Int =
        prefs(context).getString(itemId, null)?.substringAfter(':')?.toIntOrNull() ?: 0

    /** The "Hoãn" button: hides the notification and brings it back later. */
    internal fun snooze(context: Context, item: Item, choice: Snooze, now: Long = System.currentTimeMillis()) {
        NotificationManagerCompat.from(context).cancel(notificationId(item.id))
        scheduleFollowUp(context, item.id, ReminderPolicy.snoozeAt(choice, now, ZoneId.systemDefault()), repeats = 0)
    }

    /** Sets the alarms again after a reboot, for follow-ups that were still pending. */
    private fun restoreFollowUps(context: Context, items: List<Item>) {
        val open = items.filter { it.status == ItemStatus.OPEN }.associateBy { it.id }
        val saved = prefs(context).all.keys.toList()
        val now = System.currentTimeMillis()
        for (id in saved) {
            val state = prefs(context).getString(id, null)
            val at = state?.substringBefore(':')?.toLongOrNull()
            val repeats = state?.substringAfter(':')?.toIntOrNull() ?: 0
            if (open[id] == null || at == null) clearFollowUp(context, id)
            else scheduleFollowUp(context, id, maxOf(at, now + 5_000), repeats)
        }
    }

    /** After boot, a time change or an app update, alarms are gone: set them again. */
    fun syncAll(context: Context) {
        val items = MyRecapApp.from(context).items
        items.rollRecurring()
        val all = items.list()
        all.forEach { sync(context, it) }
        restoreFollowUps(context, all)
        LunarReminders.sync(context)
        // A study session that was running: bring its alarm and notification back (or play what came due).
        StudyEngine.act(context, StudyService.ACTION_TICK)
    }

    private fun alarmIntent(context: Context, itemId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        itemId.hashCode(),
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ITEM, itemId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun snoozeIntent(context: Context, itemId: String, choice: Snooze): PendingIntent = PendingIntent.getBroadcast(
        context,
        itemId.hashCode() + 10 + choice.ordinal,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_ITEM, itemId).putExtra(EXTRA_SNOOZE, choice.name),
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
            Checklist.remainingLabel(item, show = 4).ifEmpty { null },
        )
            .joinToString(" · ")
        val builder = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle((if (item.important) "❗ " else "") + if (item.type == ItemType.EVENT) "Sắp tới: ${item.title}" else item.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (item.type == ItemType.EVENT) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (item.important) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
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
        // Android shows three actions at most: Xong (tasks) plus two snoozes, or two snoozes for appointments.
        // A checklist with timed lines ("10 phút: ...") swaps the last one for "Bắt đầu học".
        val study = StudyPlan.canStart(item)
        var snoozes = if (item.type == ItemType.TASK) listOf(Snooze.HOUR_1, Snooze.TOMORROW) else listOf(Snooze.MINUTES_10, Snooze.HOUR_1)
        if (study) snoozes = snoozes.take(1)
        for (choice in snoozes) {
            builder.addAction(0, "Hoãn ${choice.label.replaceFirstChar { it.lowercase() }}", snoozeIntent(context, item.id, choice))
        }
        if (study) builder.addAction(0, "Bắt đầu học", StudyService.startPending(context, item.id))
        try {
            NotificationManagerCompat.from(context).notify(notificationId(item.id), builder.build())
        } catch (_: SecurityException) {
            // Notifications disabled: the item still shows as due in the app.
        }
    }

    const val ACTION_FIRE = "com.vh.myrecap.REMINDER"
    const val ACTION_DONE = "com.vh.myrecap.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.vh.myrecap.REMINDER_SNOOZE"
    const val ACTION_FOLLOW_UP = "com.vh.myrecap.REMINDER_FOLLOW_UP"
    const val EXTRA_ITEM = "itemId"
    const val EXTRA_SNOOZE = "snooze"
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

    private fun nag(context: Context, item: Item, repeats: Int) {
        val quiet = MyRecapApp.from(context).settings.current.quietNags
        val at = ReminderPolicy.nextNagAt(item, repeats, System.currentTimeMillis(), quiet) ?: return
        Reminders.scheduleFollowUp(context, item.id, at, repeats)
    }

    private fun handle(context: Context, intent: Intent) {
        val items = MyRecapApp.from(context).items
        if (LunarReminders.isLunarNotice(intent)) {
            LunarReminders.show(context, intent)
            LunarReminders.sync(context) // plan again so the next notice is armed even if the app is never opened
            return
        }
        when (intent.action) {
            Reminders.ACTION_FIRE -> {
                val item = intent.getStringExtra(Reminders.EXTRA_ITEM)?.let { items.get(it) } ?: return
                if (item.status == ItemStatus.OPEN) {
                    Reminders.notify(context, item)
                    // A repeating appointment chains to its next occurrence.
                    if (item.recurrence != null) Reminders.sync(context, item)
                    // An important item comes back until it is done or snoozed.
                    nag(context, item, repeats = 0)
                }
            }
            Reminders.ACTION_FOLLOW_UP -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ITEM) ?: return
                val repeats = Reminders.repeatsSoFar(context, id)
                Reminders.clearFollowUp(context, id)
                val item = items.get(id) ?: return
                if (item.status == ItemStatus.OPEN) {
                    Reminders.notify(context, item)
                    nag(context, item, repeats + 1)
                }
            }
            Reminders.ACTION_SNOOZE -> {
                val id = intent.getStringExtra(Reminders.EXTRA_ITEM) ?: return
                val item = items.get(id) ?: return
                val choice = runCatching { Snooze.valueOf(intent.getStringExtra(Reminders.EXTRA_SNOOZE).orEmpty()) }.getOrNull() ?: return
                if (item.status == ItemStatus.OPEN) Reminders.snooze(context, item, choice)
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
