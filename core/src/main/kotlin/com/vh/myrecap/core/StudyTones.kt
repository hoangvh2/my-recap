package com.vh.myrecap.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The sounds of a study session, made from sine waves so the app ships no audio files. Every sound is scaled to the
 * same digital peak, and the notes sit between 400 Hz and 2.5 kHz where a phone speaker is loudest: a low "dong"
 * (200 Hz) came out far quieter than the other signals on the same volume setting.
 */
object StudyTones {
    const val SAMPLE_RATE = 22_050
    const val PEAK = 0.95

    private class Note(val atSec: Double, val hz: Double, val lengthSec: Double, val tau: Double, val bell: Boolean = true, val level: Double = 1.0)

    // A bell: the note plus partials that are not exact multiples, so it rings like metal and is heard through a small speaker.
    private val BELL = listOf(1.0 to 1.0, 2.0 to 0.55, 2.76 to 0.35, 4.07 to 0.2)

    private fun notes(signal: StudySignal): List<Note> = when (signal) {
        StudySignal.START -> listOf(Note(0.0, 523.25, 0.5, 0.12), Note(0.2, 783.99, 0.7, 0.16))
        StudySignal.WARN -> listOf(Note(0.0, 2000.0, 0.12, 0.018, bell = false), Note(0.25, 2000.0, 0.12, 0.018, bell = false))
        StudySignal.END -> listOf(Note(0.0, 587.33, 1.1, 0.30), Note(0.65, 440.0, 1.4, 0.34))
        StudySignal.NEXT -> listOf(Note(0.0, 523.25, 0.4, 0.09), Note(0.13, 659.25, 0.4, 0.09), Note(0.26, 783.99, 0.7, 0.16))
        StudySignal.OVER -> listOf(Note(0.0, 587.33, 0.9, 0.24), Note(0.6, 587.33, 0.9, 0.24), Note(1.2, 587.33, 1.2, 0.30))
        StudySignal.DONE -> listOf(
            Note(0.0, 523.25, 1.6, 0.35), Note(0.14, 659.25, 1.5, 0.33),
            Note(0.28, 783.99, 1.4, 0.31), Note(0.42, 1046.5, 1.4, 0.40),
        )
    }

    /** The signal as 16-bit mono samples at [SAMPLE_RATE]. */
    fun render(signal: StudySignal): ShortArray {
        val list = notes(signal)
        val total = list.maxOf { it.atSec + it.lengthSec }
        val buf = DoubleArray((total * SAMPLE_RATE).toInt() + 1)
        for (n in list) {
            val from = (n.atSec * SAMPLE_RATE).toInt()
            val count = (n.lengthSec * SAMPLE_RATE).toInt()
            val partials = if (n.bell) BELL else listOf(1.0 to 1.0)
            for (i in 0 until count) {
                if (from + i >= buf.size) break
                val t = i.toDouble() / SAMPLE_RATE
                val attack = if (t < 0.004) t / 0.004 else 1.0
                val tail = if (n.lengthSec - t < 0.03) (n.lengthSec - t) / 0.03 else 1.0 // no click at the end
                val env = attack * tail * exp(-t / n.tau)
                var v = 0.0
                for ((ratio, amp) in partials) v += amp * sin(2 * PI * n.hz * ratio * t)
                buf[from + i] += v * env * n.level
            }
        }
        val top = buf.maxOf { abs(it) }
        val gain = if (top > 0) PEAK / top else 0.0
        return ShortArray(buf.size) { (buf[it] * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    fun durationMs(signal: StudySignal): Long = render(signal).size * 1000L / SAMPLE_RATE

    /** Vibration for the same moment: off, on, off, on… in milliseconds, so it can be told apart with the sound off. */
    fun vibration(signal: StudySignal): LongArray = when (signal) {
        StudySignal.START -> longArrayOf(0, 120)
        StudySignal.WARN -> longArrayOf(0, 40, 90, 40)
        StudySignal.END -> longArrayOf(0, 450, 150, 450)
        StudySignal.NEXT -> longArrayOf(0, 80, 80, 80, 80, 200)
        StudySignal.OVER -> longArrayOf(0, 450, 150, 450, 150, 450)
        StudySignal.DONE -> longArrayOf(0, 100, 80, 100, 80, 100, 80, 600)
    }
}
