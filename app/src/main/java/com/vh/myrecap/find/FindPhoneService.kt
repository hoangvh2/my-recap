package com.vh.myrecap.find

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.ui.MainActivity

/**
 * "Tìm điện thoại": rings loudly (alarm stream, at full volume, with vibration) for at most [RING_MS] after a command
 * from the person's own web app, so a lost phone can be found even when it is on silent. A notification button stops
 * it, and so does a "stop" command from the web or the person's own volume keys. The volume is put back afterwards.
 */
class FindPhoneService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var restoreVolume: Int? = null
    private val autoStop = Runnable { stopRinging() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The foreground notification must come first whatever the action is (Android's rule for startForegroundService).
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground refused", e)
        }
        if (intent?.action == ACTION_STOP) stopRinging() else startRinging()
        return START_NOT_STICKY
    }

    private fun notification() = NotificationCompat.Builder(this, MyRecapApp.CHANNEL_FIND)
        .setSmallIcon(R.drawable.ic_stat_mic)
        .setContentTitle("Điện thoại đang đổ chuông")
        .setContentText("Bạn vừa yêu cầu tìm máy từ bản web")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE),
        )
        .addAction(0, "Dừng chuông", PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    @SuppressLint("MissingPermission") // VIBRATE is declared; the check is only for the vibrator being absent.
    private fun startRinging() {
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, RING_MS)
        active = true
        if (player != null) return // already ringing: the 30 seconds restart, nothing else changes
        try {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (now < max) {
                restoreVolume = now
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (_: Exception) {
            // Volume is a courtesy; the sound still plays at whatever level the alarm stream has.
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@FindPhoneService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "player error $what/$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not play the ring", e)
        }
        vibrate()
    }

    private fun vibrate() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
        } catch (_: Exception) {
        }
    }

    private fun stopRinging() {
        main.removeCallbacks(autoStop)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
            v.cancel()
        }
        restoreVolume?.let { before ->
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, before, 0) }
        }
        restoreVolume = null
        active = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed or stopped in any way: never leave the volume at full or the vibration on.
        if (player != null || restoreVolume != null) stopRinging()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FindPhone"
        private const val NOTIFICATION_ID = 0x5400_0001
        private const val RING_MS = 30_000L
        const val ACTION_STOP = "com.vh.myrecap.FIND_STOP"
        const val ACTION_RING = "com.vh.myrecap.FIND_RING"

        /** True while this process is ringing; a "stop" that arrives when nothing rings is ignored. */
        @Volatile
        private var active = false

        private fun stopIntent(context: Context) = Intent(context, FindPhoneService::class.java).setAction(ACTION_STOP)

        /** From a push (high priority, so starting a foreground service is allowed). Does nothing when the person switched the feature off. */
        fun ring(context: Context) {
            if (!MyRecapApp.from(context).settings.current.findPhone) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, FindPhoneService::class.java).setAction(ACTION_RING))
            } catch (e: Exception) {
                Log.w(TAG, "could not start the ring", e)
            }
        }

        fun stop(context: Context) {
            // Only a running ring needs this; asking the service to start just to stop it would show a notification for nothing.
            if (!active) return
            try {
                context.startService(stopIntent(context))
            } catch (_: Exception) {
            }
        }
    }
}
