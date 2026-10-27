package com.vh.myrecap.study

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.StudyState
import com.vh.myrecap.reminder.Reminders
import com.vh.myrecap.reminder.ReminderReceiver
import com.vh.myrecap.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The notification of a study session: a countdown the system draws (so it keeps running with the screen off),
 * and three buttons that work from the lock screen. Ending a session is not one of them: that asks first, in the app.
 */
object StudyNotifications {
    const val ID = 0x5100_0001
    private const val ID_DONE = 0x5100_0002
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

    private fun service(context: Context, action: String, request: Int): PendingIntent = PendingIntent.getForegroundService(
        context,
        request,
        Intent(context, StudyService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun open(context: Context, itemId: String?): PendingIntent = PendingIntent.getActivity(
        context,
        ID,
        Intent(context, MainActivity::class.java)
            .apply { if (itemId != null) putExtra(MainActivity.EXTRA_ITEM_ID, itemId) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun clock(ms: Long): String = HOUR_MINUTE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun mmss(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }

    /** The ongoing notification; [state] null is the moment before a session is known (the service must show something at once). */
    fun build(context: Context, state: StudyState?, now: Long): Notification {
        val b = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_STUDY)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true) // the sounds are the app's own, played on the alarm stream
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context, state?.itemId))
        val phase = state?.phase
        if (state == null || phase == null) return b.setContentTitle("Đang chuẩn bị phiên học…").build()

        val step = "Học ${state.index + 1}/${state.phases.size}"
        b.setContentTitle("$step · ${phase.text}")
        val left = state.remainingMs(now)
        val late = if (state.extraMs > 0) " · trễ ${state.extraMs / 60_000}′" else ""
        val later = state.next?.let { "Tiếp theo: ${it.text} · " }.orEmpty() + "Dự kiến xong ${clock(state.expectedEndAt(now))}$late"
        val warn = if (Reminders.canExact(context)) "" else "\nChưa cho phép báo thức chính xác: tín hiệu có thể trễ khi tắt màn hình."
        val status = when {
            left == null -> "Không có giờ: bấm “Xong” khi học xong"
            state.paused -> "Tạm dừng · còn ${mmss(left)}"
            state.ended -> if (state.last) "Hết giờ" else "Hết giờ · đang chuyển sang bài tiếp"
            else -> later
        }
        b.setContentText(status)
        b.setStyle(NotificationCompat.BigTextStyle().bigText(if (status == later) later + warn else "$status\n$later$warn"))
        // The system draws the countdown, so it keeps running with the screen off and costs no wake-ups.
        if (left != null && !state.paused && !state.ended) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(state.endsAt).setShowWhen(true)
        b.addAction(0, "+5 phút", service(context, StudyService.ACTION_EXTEND, 1))
        b.addAction(0, if (state.last) "Xong" else "Xong → bài tiếp", service(context, StudyService.ACTION_NEXT, 2))
        b.addAction(0, if (state.paused) "Tiếp tục" else "Tạm dừng", service(context, if (state.paused) StudyService.ACTION_RESUME else StudyService.ACTION_PAUSE, 3))
        return b.build()
    }

    @SuppressLint("MissingPermission") // Without the notification permission the session still runs and still makes its sounds.
    fun show(context: Context, state: StudyState?, now: Long) {
        try {
            NotificationManagerCompat.from(context).notify(ID, build(context, state, now))
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /** Every step is done: the countdown goes away and this stays, with a button to mark the whole item done. */
    @SuppressLint("MissingPermission")
    fun showFinished(context: Context, state: StudyState, title: String) {
        val done = PendingIntent.getBroadcast(
            context,
            ID_DONE,
            Intent(context, ReminderReceiver::class.java).setAction(Reminders.ACTION_DONE).putExtra(Reminders.EXTRA_ITEM, state.itemId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val late = if (state.extraMs > 0) " · trễ ${state.extraMs / 60_000}′" else ""
        val n = NotificationCompat.Builder(context, MyRecapApp.CHANNEL_STUDY)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Học xong ${state.phases.size}/${state.phases.size} bước")
            .setContentText("$title$late")
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open(context, state.itemId))
            .addAction(0, "Xong việc này", done)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_DONE, n)
        } catch (_: SecurityException) {
        }
    }

    fun clearFinished(context: Context) = NotificationManagerCompat.from(context).cancel(ID_DONE)
}
