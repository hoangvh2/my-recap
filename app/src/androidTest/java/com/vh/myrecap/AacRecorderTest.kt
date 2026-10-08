package com.vh.myrecap

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vh.myrecap.core.Adts
import com.vh.myrecap.recorder.AudioFiles
import com.vh.myrecap.recorder.PcmSource
import com.vh.myrecap.recorder.SegmentedAacRecorder
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

@RunWith(AndroidJUnit4::class)
class AacRecorderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 440 Hz tone, delivered as fast as the recorder reads it; returns 0 once exhausted. */
    private class ToneSource(private val totalSamples: Int) : PcmSource {
        override val sampleRate = 16_000
        private var produced = 0
        val exhausted = CountDownLatch(1)

        override fun start() {}

        override fun read(buffer: ShortArray, size: Int): Int {
            if (produced >= totalSamples) {
                exhausted.countDown()
                Thread.sleep(5)
                return 0
            }
            val n = minOf(size, totalSamples - produced)
            for (i in 0 until n) {
                buffer[i] = (sin(2 * PI * 440 * (produced + i) / sampleRate) * 8000).toInt().toShort()
            }
            produced += n
            return n
        }

        override fun close() {}
    }

    private class Collector : SegmentedAacRecorder.Callback {
        val segments = mutableListOf<Triple<File, Long, Long>>()
        var error: Throwable? = null
        var lastProgressMs = 0L

        override fun onProgress(recordedMs: Long, level: Float) {
            lastProgressMs = recordedMs
        }

        override fun onSegmentClosed(index: Int, file: File, startMs: Long, durationMs: Long) {
            assertEquals(segments.size, index)
            segments += Triple(file, startMs, durationMs)
        }

        override fun onError(error: Throwable) {
            this.error = error
        }
    }

    @Test
    fun recordsGaplessSegmentsThatRemuxToM4a() {
        val dir = File(context.cacheDir, "rec-test").apply { deleteRecursively(); mkdirs() }
        val source = ToneSource(totalSamples = 16_000 * 5) // 5 s
        val collector = Collector()
        val recorder = SegmentedAacRecorder(source, dir, segmentDurationMs = 2_000, callback = collector, fileName = { "seg_$it.aac" })

        recorder.start()
        assertTrue("source not consumed", source.exhausted.await(30, TimeUnit.SECONDS))
        recorder.stop()

        assertNull("recorder error: ${collector.error}", collector.error)
        assertEquals(3, collector.segments.size) // 2 s + 2 s + remainder
        var expectedStart = 0L
        for ((file, start, duration) in collector.segments) {
            assertEquals("segments must be contiguous", expectedStart, start)
            val scan = Adts.scan(file)
            assertEquals("whole file must be valid ADTS", file.length(), scan.validBytes)
            assertEquals(16_000, scan.sampleRate)
            assertEquals(duration, scan.durationMs(16_000))
            expectedStart += duration
        }
        val total = collector.segments.sumOf { it.third }
        assertTrue("total duration $total ms should be ~5 s", total in 4_800..5_300)
        assertEquals(5_000L, recorder.recordedMs)
        assertTrue(collector.lastProgressMs > 0)

        // The first segment converts to an m4a that Android itself can parse.
        val m4a = File(dir, "out.m4a")
        AudioFiles.adtsToM4a(collector.segments[0].first, m4a)
        val extractor = MediaExtractor()
        extractor.setDataSource(m4a.absolutePath)
        val format = extractor.getTrackFormat(0)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        extractor.release()
        assertTrue("m4a duration $durationUs us", durationUs in 1_800_000L..2_200_000L)
    }

    @Test
    fun pausedRecorderWritesNothing() {
        val dir = File(context.cacheDir, "rec-paused").apply { deleteRecursively(); mkdirs() }
        val source = ToneSource(totalSamples = 16_000)
        val collector = Collector()
        val recorder = SegmentedAacRecorder(source, dir, 2_000, collector, { "seg_$it.aac" })
        recorder.paused = true
        recorder.start()
        assertTrue(source.exhausted.await(30, TimeUnit.SECONDS))
        recorder.stop()
        assertNull(collector.error)
        assertTrue(collector.segments.isEmpty())
        assertEquals(0L, recorder.recordedMs)
    }
}
