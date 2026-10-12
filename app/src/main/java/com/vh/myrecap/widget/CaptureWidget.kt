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
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.core.QuickTemplates
import com.vh.myrecap.ui.MainActivity
import com.vh.myrecap.ui.QuickAddActivity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one home-screen widget: record, type, today's agenda, and (when tall enough) the pinned quick lines.
 * [QuickAddWidget] stays registered only so widgets already placed keep working; it is hidden from the picker.
 */
class CaptureWidget : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        val pending = goAsync()
        MyRecapApp.from(context).appScope.launch {
            try {
                refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

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
            val items = MyRecapApp.from(context).items.list()
            val templates = QuickTemplates.featured(MyRecapApp.from(context).settings.current.quickTemplates, System.currentTimeMillis(), ZoneId.systemDefault())
                .take(SLOT_IDS.size)
            ids.forEach { id ->
                val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                manager.updateAppWidget(id, render(context, items, templates, full = isFull(height)))
            }
        }

        private val SLOT_IDS = intArrayOf(R.id.widget_template_0, R.id.widget_template_1, R.id.widget_template_2)

        /** Two rows (with the quick lines) only when the placed widget is at least about two cells tall; unknown height counts as compact. */
        internal fun isFull(minHeightDp: Int) = minHeightDp >= 110

        internal fun render(context: Context, items: List<Item>, templates: List<QuickTemplate>, full: Boolean): RemoteViews {
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

            return RemoteViews(context.packageName, if (full) R.layout.widget_capture_full else R.layout.widget_capture).apply {
                setTextViewText(R.id.widget_summary, summary)
                if (next != null) {
                    setTextViewText(R.id.widget_next, "Tiếp theo ${Instant.ofEpochMilli(next.whenAt!!).atZone(zone).format(HM)} · ${next.title}")
                    setViewVisibility(R.id.widget_next, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.widget_next, View.GONE)
                }
                setOnClickPendingIntent(R.id.widget_mic, captureIntent(context, 10))
                setOnClickPendingIntent(R.id.widget_info, openIntent(context))
                setOnClickPendingIntent(R.id.widget_type, quickAddIntent(context, 30, null, addNow = false))
                if (full) {
                    SLOT_IDS.forEachIndexed { i, id ->
                        val t = templates.getOrNull(i)
                        if (t == null) {
                            setViewVisibility(id, View.GONE)
                        } else {
                            val text = QuickTemplates.normalize(t.text)
                            setTextViewText(id, text)
                            setViewVisibility(id, View.VISIBLE)
                            setOnClickPendingIntent(id, quickAddIntent(context, 31 + i, text, addNow = !QuickTemplates.hasBlank(text)))
                        }
                    }
                    setViewVisibility(R.id.widget_template_hint, if (templates.isEmpty()) View.VISIBLE else View.GONE)
                }
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

        /** A line without a blank is added in one tap (the sheet shows Undo); one with a blank opens the sheet on it. */
        private fun quickAddIntent(context: Context, requestCode: Int, text: String?, addNow: Boolean): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                QuickAddActivity.intent(context, text, addNow),
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
