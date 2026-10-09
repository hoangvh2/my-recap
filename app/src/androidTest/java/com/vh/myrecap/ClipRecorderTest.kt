package com.vh.myrecap

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vh.myrecap.core.Adts
import com.vh.myrecap.core.SegmenterConfig
import com.vh.myrecap.core.VadSensitivity
import com.vh.myrecap.recorder.AudioFiles
import com.vh.myrecap.recorder.ClipRecorder
import com.vh.myrecap.recorder.PcmSource
import com.vh.myrecap.recorder.RecProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Runs the real encoder pipeline on scripted audio: tone = someone talking, zeros = silence. */
@RunWith(AndroidJUnit4::class)
class ClipRecorderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class ScriptSource(vararg parts: Pair<Boolean, Int>) : PcmSource {
        override val sampleRate = 16_000
        private val pcm: ShortArray
        private var pos = 0
        val exhausted = CountDownLatch(1)

        init {
            val total = parts.sumOf { it.second } * sampleRate / 1000
            pcm = ShortArray(total)
            var i = 0
            for ((talk, ms) in parts) {
                repeat(ms * sampleRate / 1000) {
                    pcm[i] = if (talk) (sin(2 * PI * 220 * i / sampleRate) * 6000).toInt().toShort() else 0
                    i++
                }
            }
        }

        override fun start() {}

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            if (pos >= pcm.size) {
                exhausted.countDown()
                Thread.sleep(5)
                return 0
            }
            val n = minOf(size, pcm.size - pos)
            System.arraycopy(pcm, pos, buffer, offset, n)
            pos += n
            return n
        }

        override fun close() {}
    }

    private class Clip(val index: Int, val file: File, val startMs: Long, val endMs: Long, val durationMs: Long)

    private class Collector : ClipRecorder.Callback {
        val clips = mutableListOf<Clip>()
        var error: Throwable? = null
        var last: RecProgress? = null
        override fun onProgress(progress: RecProgress) {
            last = progress
        }

        override fun onClipClosed(index: Int, file: File, startMs: Long, endMs: Long, durationMs: Long, recordedAt: Long) {
            clips += Clip(index, file, startMs, endMs, durationMs)
        }

        override fun onError(error: Throwable) {
            this.error = error
        }
    }

    private fun record(
        source: ScriptSource,
        config: SegmenterConfig,
        useVad: Boolean = true,
        paused: Boolean = false,
        firstIndex: Int = 0,
        offsetMs: Long = 0,
    ): Collector {
        val dir = File(context.cacheDir, "clips-${System.nanoTime()}").apply { mkdirs() }
        val collector = Collector()
        val recorder = ClipRecorder(
            source, dir, config, useVad, VadSensitivity.NORMAL, firstIndex, offsetMs, collector, { "seg_$it.aac" },
        )
        recorder.paused = paused
        recorder.start()
        assertTrue("source not consumed", source.exhausted.await(60, TimeUnit.SECONDS))
        recorder.stop()
        assertNull("recorder error: ${collector.error}", collector.error)
        collector.clips.forEach {
            val scan = Adts.scan(it.file)
            assertEquals("clip ${it.index} must be valid ADTS", it.file.length(), scan.validBytes)
            assertEquals(it.durationMs, scan.durationMs(16_000))
        }
        return collector
    }

    @Test
    fun pausesSplitTurnsAndSilenceIsNotStored() {
        val c = record(
            ScriptSource(false to 1_000, true to 3_000, false to 8_000, true to 4_000, false to 1_000),
            SegmenterConfig(splitPauseMs = 5_000),
            firstIndex = 7,
            offsetMs = 60_000,
        )
        assertEquals("two turns -> two clips", 2, c.clips.size)
        val (a, b) = c.clips
        assertEquals(listOf(7, 8), c.clips.map { it.index })
        // ~3 s speech + ~0.4 s pre-roll + ≤0.8 s kept silence, never the 8 s gap.
        assertTrue("clip A ${a.durationMs} ms", a.durationMs in 3_000L..4_600L)
        assertTrue("clip B ${b.durationMs} ms", b.durationMs in 4_000L..5_600L)
        // Timeline continues from the folder offset and keeps the real gap between turns.
        assertTrue("A starts ${a.startMs}", a.startMs in 60_500L..61_100L)
        assertTrue("gap kept on timeline", b.startMs - a.endMs >= 7_000)
        val skipped = c.last!!.skippedMs
        // 10 s of silence in total; pre-roll and ≤0.8 s per clip are kept, progress is reported every ~200 ms.
        assertTrue("silence skipped $skipped ms", skipped >= 6_500)

        val m4a = File(context.cacheDir, "a.m4a")
        AudioFiles.adtsToM4a(a.file, m4a)
        val extractor = MediaExtractor()
        extractor.setDataSource(m4a.absolutePath)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
        extractor.release()
    }

    @Test
    fun shortNoiseIsDiscarded() {
        val c = record(ScriptSource(false to 1_000, true to 300, false to 7_000), SegmenterConfig(splitPauseMs = 5_000))
        assertTrue("a 300 ms click must not become a clip", c.clips.isEmpty())
    }

    @Test
    fun withoutVadClipsAreFixedLengthAndContiguous() {
        val c = record(
            ScriptSource(true to 2_500, false to 2_500),
            SegmenterConfig(splitPauseMs = Int.MAX_VALUE, trimSilence = false, maxClipMs = 2_000, minSpeechMs = 0),
            useVad = false,
        )
        assertEquals(3, c.clips.size)
        for (i in 1 until c.clips.size) assertEquals(c.clips[i - 1].endMs, c.clips[i].startMs)
        val total = c.clips.sumOf { it.durationMs }
        assertTrue("total $total ms", total in 4_800L..5_400L)
    }

    @Test
    fun pausedRecorderWritesNothing() {
        val c = record(ScriptSource(true to 3_000), SegmenterConfig(), paused = true)
        assertTrue(c.clips.isEmpty())
    }
}
