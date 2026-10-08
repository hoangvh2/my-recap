package com.vh.myrecap.core

import kotlin.math.log10
import kotlin.math.max

enum class VadSensitivity(val label: String, val marginDb: Double) {
    LOW("Thấp (phòng ồn)", 12.0),
    NORMAL("Vừa", 9.0),
    HIGH("Cao (giọng nhỏ/xa)", 6.0),
}

/**
 * Energy-based voice activity detector with an adaptive noise floor. Frames louder than
 * `noise floor + margin` count as speech. The floor is the quietest frame energy of the last few
 * seconds, so it follows steady background noise (fan, air-con) and recovers between words.
 *
 * Cheap enough to run on every 30 ms frame on the phone, and free: nothing leaves the device.
 * It detects *sound*, not specifically voice, so loud non-speech noise is kept (never lost).
 */
class EnergyVad(
    frameMs: Int,
    private val sensitivity: VadSensitivity = VadSensitivity.NORMAL,
    floorWindowMs: Int = 5_000,
    /** Below this level nothing is speech, even in a perfectly silent room. */
    private val absoluteMinDb: Double = -50.0,
) {
    private val window = DoubleArray(maxOf(1, floorWindowMs / frameMs))
    private var filled = 0
    private var pos = 0

    /** Current noise floor estimate in dBFS (for diagnostics). */
    var floorDb: Double = -90.0
        private set

    /** Returns true when [n] samples of [frame] look like speech. */
    fun isSpeech(frame: ShortArray, n: Int): Boolean {
        val db = levelDb(frame, n)
        window[pos] = db
        pos = (pos + 1) % window.size
        if (filled < window.size) filled++
        var min = Double.MAX_VALUE
        for (i in 0 until filled) if (window[i] < min) min = window[i]
        floorDb = min
        return db > max(floorDb + sensitivity.marginDb, absoluteMinDb)
    }

    companion object {
        /** RMS level in dB relative to full scale; digital silence is about -96. */
        fun levelDb(frame: ShortArray, n: Int): Double {
            if (n <= 0) return -96.0
            var sum = 0.0
            for (i in 0 until n) {
                val v = frame[i] / 32768.0
                sum += v * v
            }
            return 10 * log10(sum / n + 1e-10)
        }
    }
}

data class SegmenterConfig(
    val frameMs: Int = 30,
    /** Continuous speech needed before a clip opens; filters clicks and coughs. */
    val onsetMs: Int = 90,
    /** Silence that ends a conversational turn and closes the clip (user setting). */
    val splitPauseMs: Int = 6_000,
    /** Drop silence beyond this inside a clip (keeps natural short pauses). 0 = keep all. */
    val keepSilenceMs: Int = 800,
    val trimSilence: Boolean = true,
    /** Clips with less speech than this are discarded as noise. */
    val minSpeechMs: Int = 1_500,
    /** Hard cap per clip so each upload stays small; speech continues in the next clip. */
    val maxClipMs: Int = 10 * 60_000,
)

/**
 * Decides, frame by frame, which audio becomes a "clip" (one conversational turn): opens on speech
 * onset, trims long silences inside a clip, closes after a long pause or at the size cap, and
 * discards clips that are mostly noise. Pure logic; the recorder executes the decisions.
 */
class ClipSegmenter(private val cfg: SegmenterConfig) {
    enum class Action {
        /** Not in a clip: keep the frame in the pre-roll buffer only. */
        IDLE,
        /** Open a clip and write the pre-roll buffer (which ends with this frame). */
        OPEN,
        WRITE,
        /** In a clip, but this silent frame is trimmed. */
        SKIP,
        /** Close the clip and keep it (this frame is not written). */
        CLOSE,
        /** Close the clip and delete it: too little speech. */
        DISCARD,
        /** Size cap reached: keep the current clip and start a new one with this frame. */
        SPLIT,
    }

    var inClip = false
        private set

    /** Audio written to the current clip, in ms. */
    var clipWrittenMs = 0L
        private set

    private var speechRunMs = 0
    private var silenceRunMs = 0
    private var clipSpeechMs = 0L

    fun next(isSpeech: Boolean, preRollMs: Int = cfg.frameMs): Action {
        val f = cfg.frameMs
        if (!inClip) {
            speechRunMs = if (isSpeech) speechRunMs + f else 0
            if (speechRunMs < cfg.onsetMs) return Action.IDLE
            inClip = true
            silenceRunMs = 0
            clipSpeechMs = speechRunMs.toLong()
            clipWrittenMs = preRollMs.toLong().coerceAtLeast(f.toLong())
            speechRunMs = 0
            return Action.OPEN
        }

        if (isSpeech) {
            silenceRunMs = 0
        } else {
            silenceRunMs += f
            if (silenceRunMs >= cfg.splitPauseMs) return close()
            if (cfg.trimSilence && silenceRunMs > cfg.keepSilenceMs) return Action.SKIP
        }
        if (clipWrittenMs + f > cfg.maxClipMs) {
            clipWrittenMs = f.toLong()
            clipSpeechMs = if (isSpeech) f.toLong() else 0L
            return Action.SPLIT
        }
        if (isSpeech) clipSpeechMs += f
        clipWrittenMs += f
        return Action.WRITE
    }

    /** Ends the current clip (pause/stop). Returns null when no clip is open. */
    fun finish(): Action? = if (inClip) close() else null

    private fun close(): Action {
        inClip = false
        val keep = clipSpeechMs >= cfg.minSpeechMs
        clipSpeechMs = 0
        silenceRunMs = 0
        speechRunMs = 0
        return if (keep) Action.CLOSE else Action.DISCARD
    }
}
