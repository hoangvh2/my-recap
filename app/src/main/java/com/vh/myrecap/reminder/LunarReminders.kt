package com.vh.myrecap.reminder

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.Holidays
import com.vh.myrecap.core.LunarNotice
import com.vh.myrecap.core.LunarNotices
import com.vh.myrecap.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Local notices for "Sắp nghỉ" and (when chosen) the evening before mùng 1 and rằm. [LunarNotices] decides what and
 * when; this arms one alarm per notice and replaces the previous set, so changing a setting takes effect at once.
 */
object LunarReminders {
    private const val BASE_CODE = 0x4C55_4E00
    private const val ACTION = "com.vh.myrecap.LUNAR_NOTICE"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BODY = "body"
    private const val EXTRA_KEY = "key"

    private fun prefs(context: Context) = context.getSharedPreferences("lunar_notices", Context.MODE_PRIVATE)

    private fun intent(context: Context, index: Int, notice: LunarNotice? = null): PendingIntent = PendingIntent.getBroadcast(
        context,
        BASE_CODE + index,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION).apply {
            if (notice != null) putExtra(EXTRA_TITLE, notice.title).putExtra(EXTRA_BODY, notice.body).putExtra(EXTRA_KEY, notice.key)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun isLunarNotice(intent: Intent) = intent.action == ACTION

    /** Re-plans from the current settings: cancels the old alarms and arms the new ones. */
    fun sync(context: Context, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val settings = MyRecapApp.from(context).settings.current
        val plan = LunarNotices.plan(now, settings.lunar, Holidays.newest(settings.holidayJson).overrides)
        val am = context.getSystemService(AlarmManager::class.java)
        repeat(prefs(context).getInt("count", 0)) { am.cancel(intent(context, it)) }
        plan.forEachIndexed { i, n -> Reminders.setAlarm(context, am, LunarNotices.epochMs(n, zone), intent(context, i, n)) }
        prefs(context).edit().putInt("count", plan.size).apply()
    }

    @SuppressLint("MissingPermission") // SecurityException handled below.
    fun show(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val open = PendingIntent.getActivity(
            context,
            key.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(0x4C00_0000 xor key.hashCode(), n)
        } catch (_: SecurityException) {
            // Notifications disabled: the banner in the app still shows.
        }
    }
}
