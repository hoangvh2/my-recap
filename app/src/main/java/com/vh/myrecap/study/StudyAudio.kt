package com.vh.myrecap.study

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.vh.myrecap.core.StudySignal
import com.vh.myrecap.core.StudyTones
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Plays the study signals so they are heard whatever the phone is set to: on the alarm stream (the ringer switch,
 * silent mode and Do Not Disturb's default "alarms" exception do not mute it), raised to a floor volume for the
 * session when the person asked for that, ducking (not pausing) the lesson audio that is playing, and always with a
 * vibration of its own.
 */
object StudyAudio {
    private const val PREFS = "study_audio"
    private const val KEY_RESTORE = "restoreVolume"

    /** The alarm volume is raised to at least this share of its maximum while a session runs. */
    const val FLOOR_SHARE = 0.8

    private val ATTRS = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val cache = ConcurrentHashMap<StudySignal, ShortArray>()
    private val main = Handler(Looper.getMainLooper())

    /** Raises the alarm volume to the floor, remembering the person's own level so [restoreVolume] can put it back. */
    fun raiseVolume(context: Context) {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            val floor = ceil(am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * FLOOR_SHARE).toInt()
            val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (now >= floor) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_RESTORE)) prefs.edit().putInt(KEY_RESTORE, now).apply()
            am.setStreamVolume(AudioManager.STREAM_ALARM, floor, 0)
        } catch (_: Exception) {
            // The volume is a courtesy; never let it stop the signal.
        }
    }

    /** Puts the alarm volume back after [afterMs], unless the person changed it meanwhile. */
    fun restoreVolume(context: Context, afterMs: Long = 0) {
        if (afterMs > 0) {
            main.postDelayed({ if (StudyEngine.active.value == null) restoreVolume(context, 0) }, afterMs)
            return
        }
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_RESTORE)) return
            val before = prefs.getInt(KEY_RESTORE, 0)
            prefs.edit().remove(KEY_RESTORE).apply()
            val am = context.getSystemService(AudioManager::class.java)
            val floor = ceil(am.getStreamMaxVolume(AudioManager.STREAM_ALARM) * FLOOR_SHARE).toInt()
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == floor) am.setStreamVolume(AudioManager.STREAM_ALARM, before, 0)
        } catch (_: Exception) {
        }
    }

    /** Sound plus vibration for [signal]; returns how long the sound lasts so a caller that must stay alive can wait. */
    fun play(context: Context, signal: StudySignal, loud: Boolean): Long {
        if (loud) raiseVolume(context)
        vibrate(context, signal)
        val samples = cache.getOrPut(signal) { StudyTones.render(signal) }
        val durationMs = samples.size * 1000L / StudyTones.SAMPLE_RATE
        try {
            val am = context.getSystemService(AudioManager::class.java)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(focus) // a "no" still plays: the signal matters more than politeness
            val track = AudioTrack.Builder()
                .setAudioAttributes(ATTRS)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(StudyTones.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            main.postDelayed({
                try {
                    track.release()
                    am.abandonAudioFocusRequest(focus)
                } catch (_: Exception) {
                }
            }, durationMs + 300)
        } catch (_: Exception) {
            // No audio output right now: the vibration and the notification still tell the person.
        }
        return durationMs
    }

    @Suppress("DEPRECATION") // The AudioAttributes overload is what keeps it going when touch vibration is switched off.
    private fun vibrate(context: Context, signal: StudySignal) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                context.getSystemService(Vibrator::class.java)
            } ?: return
            if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(StudyTones.vibration(signal), -1), ATTRS)
        } catch (_: Exception) {
        }
    }
}
