package com.vh.myrecap.core

import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VadTest {
    private val sr = 16_000
    private val frameMs = 30
    private val frameLen = sr * frameMs / 1000
    private val rnd = Random(42)

    /** Background noise at [noiseDb] dBFS, plus a "voice" (tone with syllable gaps) when [voiceDb] != null. */
    private fun frame(noiseDb: Double, voiceDb: Double?, t: Int): ShortArray {
        val noiseAmp = 32768 * 10.0.pow(noiseDb / 20) * 1.7
        val voiceAmp = voiceDb?.let { 32768 * 10.0.pow(it / 20) * 1.41 } ?: 0.0
        return ShortArray(frameLen) { i ->
            val n = (rnd.nextDouble() * 2 - 1) * noiseAmp
            val v = voiceAmp * sin(2 * PI * 220 * (t * frameLen + i) / sr)
            (n + v).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Speech with a 90 ms dip every 450 ms, like gaps between words. */
    private fun speechFrames(ms: Int, noiseDb: Double, voiceDb: Double, start: Int): List<ShortArray> =
        (0 until ms / frameMs).map { k -> frame(noiseDb, if (k % 15 < 12) voiceDb else null, start + k) }

    private fun silenceFrames(ms: Int, noiseDb: Double, start: Int) = (0 until ms / frameMs).map { frame(noiseDb, null, start + it) }

    @Test
    fun levelOfFullScaleSineIsAboutMinus3dB() {
        val f = ShortArray(frameLen) { (32767 * sin(2 * PI * 440 * it / sr)).toInt().toShort() }
        val db = EnergyVad.levelDb(f, f.size)
        assertTrue(db in -3.5..-2.5, "level $db")
    }

    @Test
    fun detectsSpeechOverQuietRoomAndAdaptsToLouderNoise() {
        val vad = EnergyVad(frameMs)
        var t = 0
        silenceFrames(2_000, -65.0, t).forEach { assertFalse(vad.isSpeech(it, it.size)); t++ }
        assertTrue(vad.isSpeech(frame(-65.0, -30.0, t), frameLen))
        // Air-con starts: 20 dB louder steady noise. Within the floor window it stops being "speech".
        val noisy = silenceFrames(8_000, -45.0, t).map { vad.isSpeech(it, it.size) }
        assertTrue(noisy.takeLast(50).none { it }, "steady noise must not stay classified as speech")
        assertTrue(vad.isSpeech(frame(-45.0, -25.0, t), frameLen), "voice above the new floor is speech")
    }

    @Test
    fun digitalSilenceAndVeryQuietNoiseAreNeverSpeech() {
        val vad = EnergyVad(frameMs)
        repeat(100) { assertFalse(vad.isSpeech(ShortArray(frameLen), frameLen)) }
        repeat(100) { assertFalse(vad.isSpeech(frame(-58.0, null, it), frameLen)) }
    }

    private fun run(cfg: SegmenterConfig, speech: List<Boolean>): List<Pair<ClipSegmenter.Action, Int>> {
        val seg = ClipSegmenter(cfg)
        val out = mutableListOf<Pair<ClipSegmenter.Action, Int>>()
        speech.forEachIndexed { i, s ->
            val a = seg.next(s)
            if (a != ClipSegmenter.Action.WRITE && a != ClipSegmenter.Action.IDLE && a != ClipSegmenter.Action.SKIP) out += a to i
        }
        seg.finish()?.let { out += it to speech.size }
        return out
    }

    private fun pattern(vararg parts: Pair<Boolean, Int>): List<Boolean> =
        parts.flatMap { (s, ms) -> List(ms / frameMs) { s } }

    @Test
    fun interviewTurnsBecomeSeparateClipsAndCoughsAreDropped() {
        val cfg = SegmenterConfig(splitPauseMs = 6_000)
        val audio = pattern(
            false to 3_000,
            true to 20_000, // question + answer A
            false to 3_000, // thinking pause inside the answer: same clip
            true to 10_000,
            false to 9_000, // interviewer prepares question B: split
            true to 210, // cough: too short to open?  (onset 90 ms) -> opens, then discarded
            false to 7_000,
            true to 15_000, // answer B
        )
        val events = run(cfg, audio)
        val kinds = events.map { it.first }
        assertEquals(
            listOf(
                ClipSegmenter.Action.OPEN, ClipSegmenter.Action.CLOSE,
                ClipSegmenter.Action.OPEN, ClipSegmenter.Action.DISCARD,
                ClipSegmenter.Action.OPEN, ClipSegmenter.Action.CLOSE,
            ),
            kinds,
        )
    }

    @Test
    fun longSilenceInsideClipIsTrimmed() {
        val cfg = SegmenterConfig(splitPauseMs = 6_000, keepSilenceMs = 600)
        val seg = ClipSegmenter(cfg)
        var written = 0
        var skipped = 0
        for (s in pattern(true to 3_000, false to 4_000, true to 3_000)) {
            when (seg.next(s)) {
                ClipSegmenter.Action.WRITE, ClipSegmenter.Action.OPEN -> written++
                ClipSegmenter.Action.SKIP -> skipped++
                else -> Unit
            }
        }
        // 4 s pause: 600 ms kept, ~3.4 s trimmed.
        assertEquals(3_400 / frameMs, skipped)
        assertTrue(seg.clipWrittenMs in 6_500L..6_800L, "written ${seg.clipWrittenMs}")
    }

    @Test
    fun trimmingCanBeDisabled() {
        val seg = ClipSegmenter(SegmenterConfig(trimSilence = false))
        val actions = pattern(true to 1_000, false to 4_000).map { seg.next(it) }
        assertFalse(ClipSegmenter.Action.SKIP in actions)
    }

    @Test
    fun maxLengthSplitsWithoutLosingFrames() {
        val cfg = SegmenterConfig(maxClipMs = 60_000)
        val seg = ClipSegmenter(cfg)
        var splits = 0
        var written = 0
        for (s in pattern(true to 150_000)) {
            when (seg.next(s)) {
                ClipSegmenter.Action.SPLIT -> { splits++; written++ }
                ClipSegmenter.Action.WRITE, ClipSegmenter.Action.OPEN -> written++
                else -> Unit
            }
        }
        assertEquals(2, splits)
        // The frames before OPEN are in the pre-roll buffer, which the recorder writes on OPEN.
        val preRoll = cfg.onsetMs / frameMs - 1
        assertEquals(150_000 / frameMs, written + preRoll)
        assertEquals(ClipSegmenter.Action.CLOSE, seg.finish())
        assertNull(seg.finish())
    }

    @Test
    fun endToEndPcmThroughVadAndSegmenter() {
        val vad = EnergyVad(frameMs)
        val seg = ClipSegmenter(SegmenterConfig(splitPauseMs = 5_000))
        var t = 0
        val frames = silenceFrames(2_000, -60.0, t).also { t += it.size } +
            speechFrames(12_000, -60.0, -28.0, t).also { t += it.size } +
            silenceFrames(8_000, -60.0, t).also { t += it.size } +
            speechFrames(9_000, -60.0, -32.0, t).also { t += it.size } +
            silenceFrames(1_000, -60.0, t)
        val closes = mutableListOf<ClipSegmenter.Action>()
        var opens = 0
        frames.forEach { f ->
            when (val a = seg.next(vad.isSpeech(f, f.size))) {
                ClipSegmenter.Action.OPEN -> opens++
                ClipSegmenter.Action.CLOSE, ClipSegmenter.Action.DISCARD -> closes += a
                else -> Unit
            }
        }
        seg.finish()?.let { closes += it }
        assertEquals(2, opens, "two speaker turns")
        assertEquals(listOf(ClipSegmenter.Action.CLOSE, ClipSegmenter.Action.CLOSE), closes)
    }
}
