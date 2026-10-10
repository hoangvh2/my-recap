package com.vh.myrecap.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.Item
import com.vh.myrecap.core.ItemStatus
import com.vh.myrecap.core.ItemType
import com.vh.myrecap.ui.MainActivity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home-screen widget: one tap to capture, plus today's agenda in one line. */
class CaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        MyRecapApp.from(context).appScope.launch {
            try {
                refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm")

        /** Redraws every placed widget from the current items. Call off the main thread. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CaptureWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(context, MyRecapApp.from(context).items.list())
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun render(context: Context, items: List<Item>): RemoteViews {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val now = System.currentTimeMillis()
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val open = items.filter { it.status == ItemStatus.OPEN && (it.type == ItemType.TASK || it.type == ItemType.EVENT) }
            val dueToday = open.count { it.whenAt != null && !day(it.whenAt!!).isAfter(today) }
            val drafts = items.count { it.status == ItemStatus.DRAFT }
            val next = open.filter { it.whenAt != null && !it.allDay && it.whenAt!! >= now && day(it.whenAt!!) == today }
                .minByOrNull { it.whenAt!! }

            val summary = listOfNotNull(
                if (dueToday > 0) "$dueToday việc hôm nay" else "Không có việc hôm nay",
                drafts.takeIf { it > 0 }?.let { "$it chờ xác nhận" },
            ).joinToString(" · ")

            return RemoteViews(context.packageName, R.layout.widget_capture).apply {
                setTextViewText(R.id.widget_summary, summary)
                if (next != null) {
                    setTextViewText(R.id.widget_next, "Tiếp theo ${Instant.ofEpochMilli(next.whenAt!!).atZone(zone).format(HM)} · ${next.title}")
                    setViewVisibility(R.id.widget_next, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.widget_next, View.GONE)
                }
                setOnClickPendingIntent(R.id.widget_mic, captureIntent(context, 10))
                setOnClickPendingIntent(R.id.widget_info, openIntent(context))
            }
        }

        fun captureIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_QUICK_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            11,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Quick Settings tile: swipe down, tap "Ghi nhanh" from any app (unlocks first if needed). */
class QuickCaptureTile : TileService() {
    override fun onClick() {
        super.onClick()
        val launch = Runnable {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(CaptureWidget.captureIntent(this, 12))
            } else {
                launchBeforeApi34()
            }
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }

    /** Android 13 and older only accept an Intent here; the PendingIntent variant exists from 34. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBeforeApi34() {
        startActivityAndCollapse(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_QUICK_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
