package com.vh.myrecap.recorder

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibration feedback so the user knows a lock-screen/notification action registered without
 * looking at the phone during a meeting.
 */
object Haptics {
    fun bookmark(context: Context) = vibrate(context, longArrayOf(0, 60))
    fun paused(context: Context) = vibrate(context, longArrayOf(0, 60, 120, 60))
    fun resumed(context: Context) = vibrate(context, longArrayOf(0, 200))
    fun stopped(context: Context) = vibrate(context, longArrayOf(0, 60, 100, 60, 100, 60))
    fun error(context: Context) = vibrate(context, longArrayOf(0, 500, 200, 500))

    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: return
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
            // Feedback only; never let it break recording.
        }
    }
}
