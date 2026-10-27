package com.vh.myrecap.study

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vh.myrecap.MyRecapApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps a study session alive with the screen off. The rules and the saved state live in
 * [StudyEngine]; this holds the notification, runs the buttons and ticks at the right second while the process is up.
 */
class StudyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { dispatch(ACTION_TICK) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand ${intent?.action}")
        // Android requires the foreground notification within seconds of startForegroundService, whatever the action is.
        try {
            ServiceCompat.startForeground(
                this,
                StudyNotifications.ID,
                StudyNotifications.build(this, StudyEngine.peek(this), System.currentTimeMillis()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now: the engine and its alarm still carry the session.
            Log.w(TAG, "startForeground refused", e)
        }
        dispatch(intent?.action ?: ACTION_TICK, intent?.getStringExtra(EXTRA_ITEM))
        return START_NOT_STICKY
    }

    private fun dispatch(action: String, itemId: String? = null) {
        main.removeCallbacks(tick)
        MyRecapApp.from(this).appScope.launch {
            Log.i(TAG, "dispatch $action $itemId")
            when (action) {
                ACTION_START -> if (itemId != null) Log.i(TAG, "start -> ${StudyEngine.start(this@StudyService, itemId)}")
                ACTION_STOP -> StudyEngine.stop(this@StudyService)
                else -> StudyEngine.act(this@StudyService, action)
            }
            val wake = StudyEngine.nextWakeAt(this@StudyService)
            withContext(Dispatchers.Main) {
                if (StudyEngine.active.value == null) {
                    ServiceCompat.stopForeground(this@StudyService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (wake != null) {
                    // Exact to the second while the process is up; the alarm in the engine covers the rest.
                    main.postDelayed(tick, (wake - System.currentTimeMillis()).coerceAtLeast(50))
                }
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.vh.myrecap.STUDY_START"
        const val ACTION_STOP = "com.vh.myrecap.STUDY_STOP"
        const val ACTION_TICK = "com.vh.myrecap.STUDY_TICK"
        const val ACTION_NEXT = "com.vh.myrecap.STUDY_NEXT"
        const val ACTION_EXTEND = "com.vh.myrecap.STUDY_EXTEND"
        const val ACTION_PAUSE = "com.vh.myrecap.STUDY_PAUSE"
        const val ACTION_RESUME = "com.vh.myrecap.STUDY_RESUME"
        private const val EXTRA_ITEM = "itemId"
        private const val TAG = "Study"

        /** Starts a session; from the app, or from a notification button (both count as the person asking). */
        fun start(context: Context, itemId: String) {
            Log.i(TAG, "start requested for $itemId")
            ContextCompat.startForegroundService(context, startIntent(context, itemId))
        }

        fun startIntent(context: Context, itemId: String): Intent =
            Intent(context, StudyService::class.java).setAction(ACTION_START).putExtra(EXTRA_ITEM, itemId)

        /** The "Bắt đầu học" button of a reminder notification. */
        fun startPending(context: Context, itemId: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            itemId.hashCode() + 20,
            startIntent(context, itemId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The alarm that backs up the service: starts it again if Android stopped it, or does the step itself when that is not allowed. */
class StudyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = MyRecapApp.from(context)
        app.appScope.launch {
            try {
                if (StudyEngine.peek(context) == null) return@launch
                val started = try {
                    ContextCompat.startForegroundService(context, Intent(context, StudyService::class.java).setAction(StudyService.ACTION_TICK))
                    true
                } catch (_: Exception) {
                    false
                }
                if (!started) delay(StudyEngine.act(context, StudyService.ACTION_TICK) + 300)
            } finally {
                pending.finish()
            }
        }
    }
}
