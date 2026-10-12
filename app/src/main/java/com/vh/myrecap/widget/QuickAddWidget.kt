package com.vh.myrecap.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.QuickTemplate
import com.vh.myrecap.core.QuickTemplates
import com.vh.myrecap.ui.QuickAddActivity
import kotlinx.coroutines.launch

/**
 * Home-screen quick add: a "type" button that opens the quick-add sheet, plus the first three saved
 * lines (pinned first). A line without a blank is added in one tap (the sheet shows it with Undo); a
 * line with a blank opens the sheet with the cursor on the blank. Widgets cannot take typing themselves.
 */
class QuickAddWidget : AppWidgetProvider() {
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
        private const val SLOTS = 3
        private val SLOT_IDS = intArrayOf(R.id.widget_template_0, R.id.widget_template_1, R.id.widget_template_2)

        /** Redraws every placed quick-add widget from the saved lines. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickAddWidget::class.java))
            if (ids.isEmpty()) return
            val templates = QuickTemplates.ordered(MyRecapApp.from(context).settings.current.quickTemplates).take(SLOTS)
            val views = render(context, templates)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun render(context: Context, templates: List<QuickTemplate>): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
                setOnClickPendingIntent(R.id.widget_type, open(context, 30, null, addNow = false))
                SLOT_IDS.forEachIndexed { i, id ->
                    val t = templates.getOrNull(i)
                    if (t == null) {
                        setViewVisibility(id, View.GONE)
                    } else {
                        val text = QuickTemplates.normalize(t.text)
                        setTextViewText(id, text)
                        setViewVisibility(id, View.VISIBLE)
                        setOnClickPendingIntent(id, open(context, 31 + i, text, addNow = !QuickTemplates.hasBlank(text)))
                    }
                }
                setViewVisibility(R.id.widget_template_hint, if (templates.isEmpty()) View.VISIBLE else View.GONE)
            }

        private fun open(context: Context, requestCode: Int, text: String?, addNow: Boolean): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                QuickAddActivity.intent(context, text, addNow),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
