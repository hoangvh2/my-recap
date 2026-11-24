package com.vh.myrecap.pin

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.PinnedChecklist
import com.vh.myrecap.reminder.Reminders
import com.vh.myrecap.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Checklists pinned to the notification shade ("ra khỏi nhà", "đi siêu thị"), so they can be ticked without opening
 * the app. Which items are pinned is kept only on this phone (a notification belongs to one device), in preferences;
 * the checklist itself is the item's own, so ticks sync like any other change. Unpinning only hides the notification.
 */
object PinnedChecklists {
    private const val PREFS = "pinned_checklists"
    private const val KEY_IDS = "ids"
    private const val NOTIFICATION_ID = 0x5300_0001
    private const val DONE_ID = 0x5300_0002
    private const val DONE_TIMEOUT_MS = 2 * 60_000L
    private const val MAX_PINS = 5

    const val ACTION_TICK = "com.vh.myrecap.pin.TICK"
    const val ACTION_ALL = "com.vh.myrecap.pin.ALL"
    const val ACTION_OFF = "com.vh.myrecap.pin.OFF"
    const val ACTION_UNDO = "com.vh.myrecap.pin.UNDO"
    const val EXTRA_ITEM = "item"
    const val EXTRA_ENTRY = "entry"

    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var loaded = false

    /** The pinned item ids; what the checklist card shows as "Ghim" or "Bỏ ghim". */
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context): Set<String> {
        if (!loaded) {
            _ids.value = prefs(context).getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
            loaded = true
        }
        return _ids.value
    }

    @Synchronized
    private fun save(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_IDS, ids).apply()
        _ids.value = ids
        loaded = true
    }

    fun isPinned(context: Context, id: String): Boolean = id in load(context)

    /** Result of a pin request, for the message under the button. */
    enum class PinResult { PINNED, NOT_ELIGIBLE, NOTIFICATIONS_OFF, TOO_MANY }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun pin(context: Context, item: Item): PinResult {
        if (!PinnedChecklist.eligible(item)) return PinResult.NOT_ELIGIBLE
        if (!canNotify(context)) return PinResult.NOTIFICATIONS_OFF
        val ids = load(context)
        if (item.id !in ids && ids.size >= MAX_PINS) return PinResult.TOO_MANY
        save(context, ids + item.id)
        show(context, item)
        return PinResult.PINNED
    }

    fun unpin(context: Context, id: String) {
        save(context, load(context) - id)
        cancel(context, id)
    }

    fun message(result: PinResult): String = when (result) {
        PinResult.PINNED -> "Đã ghim lên thanh thông báo"
        PinResult.NOT_ELIGIBLE -> "Chỉ ghim được việc còn mở có checklist"
        PinResult.NOTIFICATIONS_OFF -> "Hãy bật thông báo cho app để ghim checklist"
        PinResult.TOO_MANY -> "Ghim tối đa $MAX_PINS checklist, bỏ ghim một cái trước"
    }

    /** Draws every pinned checklist from the stored items; one that cannot stay pinned any more (done, deleted) is dropped. */
    fun refresh(context: Context) {
        val app = MyRecapApp.from(context)
        for (id in load(context)) {
            val item = app.items.get(id)
            if (PinnedChecklist.eligible(item)) show(context, item!!) else unpin(context, id)
        }
    }

    private fun broadcast(context: Context, action: String, itemId: String, entryId: String? = null): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + itemId + entryId).hashCode(),
        Intent(context, PinReceiver::class.java).setAction(action).putExtra(EXTRA_ITEM, itemId).apply { if (entryId != null) putExtra(EXTRA_ENTRY, entryId) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun open(context: Context, itemId: String): PendingIntent = PendingIntent.getActivity(
        context,
        itemId.hashCode(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ITEM_ID, itemId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun build(context: Context, item: Item): android.app.Notification? {
        val v = PinnedChecklist.view(item) ?: return null
        // On the lock screen only the count shows, not what is on the list.
        val hidden = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_PINNED)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Checklist")
            .setContentText(v.summary)
            .build()
        val b = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_PINNED)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("${v.title} · ${v.summary}")
            .setContentText(v.lines.firstOrNull() ?: "Đã có đủ")
            .setStyle(NotificationCompat.InboxStyle().also { s -> v.lines.forEach { s.addLine("☐ $it") } })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setContentIntent(open(context, item.id))
        v.tickId?.let { b.addAction(0, v.tickLabel.orEmpty(), broadcast(context, ACTION_TICK, item.id, it)) }
        if (v.remaining >= 2) b.addAction(0, "Đủ rồi", broadcast(context, ACTION_ALL, item.id))
        b.addAction(0, "Bỏ ghim", broadcast(context, ACTION_OFF, item.id))
        return b.build()
    }

    @SuppressLint("MissingPermission") // Without the notification permission nothing is pinned (pin() says so).
    fun show(context: Context, item: Item) {
        val n = build(context, item) ?: return
        try {
            NotificationManagerCompat.from(context).notify(item.id, NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(id, NOTIFICATION_ID)

    /** The last line was ticked from the notification: the pin turns into a short "done" note with an Undo. */
    @SuppressLint("MissingPermission")
    fun showFinished(context: Context, item: Item, lastEntryId: String, canUndo: Boolean) {
        val n = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_PINNED)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Đã đủ “${item.title.take(50)}”")
            .setContentText("Việc đã được đánh dấu xong")
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(DONE_TIMEOUT_MS)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open(context, item.id))
            .apply { if (canUndo) addAction(0, "Hoàn tác", broadcast(context, ACTION_UNDO, item.id, lastEntryId)) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(item.id, DONE_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun clearFinished(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(id, DONE_ID)
}

/** The buttons of a pinned checklist. Each acts on the stored item, so it works with the app closed. */
class PinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        MyRecapApp.from(context).appScope.launch {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, intent: Intent) {
        val items = MyRecapApp.from(context).items
        val id = intent.getStringExtra(PinnedChecklists.EXTRA_ITEM) ?: return
        val entry = intent.getStringExtra(PinnedChecklists.EXTRA_ENTRY)
        when (intent.action) {
            PinnedChecklists.ACTION_OFF -> PinnedChecklists.unpin(context, id)
            PinnedChecklists.ACTION_TICK -> {
                val before = items.get(id)?.takeIf { PinnedChecklist.eligible(it) } ?: return PinnedChecklists.unpin(context, id)
                val (next, finished) = PinnedChecklist.tick(before, entry ?: return)
                if (next == before) return PinnedChecklists.refresh(context)
                items.upsert(next)
                if (finished) finish(context, next, entry) else PinnedChecklists.show(context, next)
            }
            PinnedChecklists.ACTION_ALL -> {
                val before = items.get(id)?.takeIf { PinnedChecklist.eligible(it) } ?: return PinnedChecklists.unpin(context, id)
                val last = com.vh.myrecap.core.Checklist.progress(before).remaining.lastOrNull() ?: return
                val all = com.vh.myrecap.core.Checklist.tickAll(before)
                items.upsert(all)
                finish(context, all, last.id)
            }
            PinnedChecklists.ACTION_UNDO -> {
                val before = items.get(id) ?: return
                val reopened = PinnedChecklist.reopen(before, entry ?: return)
                PinnedChecklists.clearFinished(context, id)
                if (reopened == before) return
                items.upsert(reopened)
                Reminders.sync(context, reopened)
                PinnedChecklists.pin(context, reopened)
            }
        }
    }

    /** Everything is ticked: the item is done, as when the last line is ticked in the app (a repeating one rolls on). */
    private fun finish(context: Context, ticked: Item, lastEntryId: String) {
        val items = MyRecapApp.from(context).items
        PinnedChecklists.unpin(context, ticked.id)
        val (finished, next) = items.complete(ticked.id) ?: return
        Reminders.cancel(context, ticked.id)
        if (finished.status == com.vh.myrecap.core.ItemStatus.OPEN) Reminders.sync(context, finished)
        next?.let { Reminders.sync(context, it) }
        PinnedChecklists.showFinished(context, ticked, lastEntryId, canUndo = finished.status == com.vh.myrecap.core.ItemStatus.DONE)
    }
}
