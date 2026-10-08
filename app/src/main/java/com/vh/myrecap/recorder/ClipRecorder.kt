package com.vh.myrecap.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import com.vh.myrecap.core.Adts
import com.vh.myrecap.core.ClipSegmenter
import com.vh.myrecap.core.EnergyVad
import com.vh.myrecap.core.SegmenterConfig
import com.vh.myrecap.core.VadSensitivity
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Mono 16-bit PCM input. Abstracted so tests can feed synthetic audio instead of the microphone. */
interface PcmSource : Closeable {
    val sampleRate: Int
    fun start()

    /** Blocking read into [buffer] at [offset]; returns samples read, or a negative AudioRecord error code. */
    fun read(buffer: ShortArray, offset: Int, size: Int): Int
}

class MicPcmSource(override val sampleRate: Int = 16_000) : PcmSource {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // Checked by the UI before the service starts.
    override fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IOException("Thiết bị không hỗ trợ ghi âm $sampleRate Hz")
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, sampleRate), // ≥0.5 s of headroom against scheduling hiccups with the screen off
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IOException("Không mở được micro (đang bị ứng dụng khác dùng?)")
        }
        r.startRecording()
        record = r
    }

    override fun read(buffer: ShortArray, offset: Int, size: Int): Int = record?.read(buffer, offset, size) ?: -1

    override fun close() {
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
    }
}

/** Live recorder state for the UI and notification. */
data class RecProgress(
    /** Folder timeline position (pauses excluded). */
    val elapsedMs: Long,
    val level: Float,
    /** A clip is open: someone is talking (or the pause is still short). */
    val speaking: Boolean,
    val clipsSaved: Int,
    /** Time not uploaded thanks to silence filtering, this recording run. */
    val skippedMs: Long,
)

/**
 * Records PCM → AAC-LC (32 kbps mono) and writes one ADTS file per *clip*: a conversational turn
 * found by voice activity detection ([ClipSegmenter]). Silence between turns is never encoded,
 * long silences inside a turn are trimmed, short noises are discarded. With VAD disabled every
 * frame counts as speech, so clips become fixed-length chunks capped by [SegmenterConfig.maxClipMs].
 *
 * Each clip gets its own encoder instance so it ends cleanly. Pausing drops input, so the timeline
 * excludes paused time. Runs on its own thread; callbacks are invoked from that thread.
 */
class ClipRecorder(
    private val source: PcmSource,
    private val outDir: File,
    private val config: SegmenterConfig,
    private val useVad: Boolean,
    sensitivity: VadSensitivity,
    private val firstIndex: Int,
    private val timelineOffsetMs: Long,
    private val callback: Callback,
    private val fileName: (Int) -> String,
    private val bitRate: Int = 32_000,
    private val preRollMs: Int = 450,
) {
    interface Callback {
        fun onProgress(progress: RecProgress)
        fun onClipClosed(index: Int, file: File, startMs: Long, endMs: Long, durationMs: Long, recordedAt: Long)

        /** Fatal error; the recorder has already stopped and closed the current clip. */
        fun onError(error: Throwable)
    }

    @Volatile
    var paused: Boolean = false

    @Volatile
    private var stopRequested = false

    /** Folder timeline position, for bookmarks. */
    @Volatile
    var recordedMs: Long = timelineOffsetMs
        private set

    private val sampleRate get() = source.sampleRate
    private val frameMs = config.frameMs
    private val vad = EnergyVad(config.frameMs, sensitivity)
    private val segmenter = ClipSegmenter(config)
    private var thread: Thread? = null

    // Recorder-thread state.
    private var codec: MediaCodec? = null
    private var out: OutputStream? = null
    private var clipFile: File? = null
    private var clipIndex = firstIndex
    private var clipFrames = 0
    private var clipStartMs = 0L
    private var clipEndMs = 0L
    private var clipRecordedAt = 0L
    private var clipSamplesFed = 0L
    private var clipsSaved = 0
    private var framesSeen = 0L
    private var framesWritten = 0L

    fun start() {
        check(thread == null) { "already started" }
        thread = Thread(::recordLoop, "clip-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stops recording and blocks until the last clip is closed. */
    fun stop() {
        stopRequested = true
        thread?.join(10_000)
    }

    private fun recordLoop() {
        var failure: Throwable? = null
        val frameLen = sampleRate * frameMs / 1000
        val preRollFrames = maxOf(1, preRollMs / frameMs)
        val preRoll = ArrayDeque<ShortArray>()
        try {
            source.start()
            val buf = ShortArray(frameLen)
            var lastProgress = -1L
            var pausedFrames = 0
            while (!stopRequested) {
                if (!readFrame(buf)) continue
                val level = rmsLevel(buf, frameLen)
                if (paused) {
                    segmenter.finish()?.let { closeClip(keep = it == ClipSegmenter.Action.CLOSE) }
                    preRoll.clear()
                    if (++pausedFrames % 7 == 0) report(level)
                    continue
                }
                val frameStart = recordedMs
                recordedMs += frameMs
                framesSeen++
                val speech = !useVad || vad.isSpeech(buf, frameLen)
                val action = segmenter.next(speech, (preRoll.size + 1) * frameMs)
                when (action) {
                    ClipSegmenter.Action.IDLE -> {
                        preRoll.addLast(buf.copyOf())
                        while (preRoll.size >= preRollFrames) preRoll.removeFirst()
                    }
                    ClipSegmenter.Action.OPEN -> {
                        openClip(frameStart - preRoll.size * frameMs)
                        preRoll.forEach { encode(it) }
                        preRoll.clear()
                        encode(buf)
                        clipEndMs = recordedMs
                    }
                    ClipSegmenter.Action.WRITE -> {
                        encode(buf)
                        if (speech) clipEndMs = recordedMs
                    }
                    ClipSegmenter.Action.SKIP -> Unit
                    ClipSegmenter.Action.CLOSE, ClipSegmenter.Action.DISCARD -> {
                        closeClip(keep = action == ClipSegmenter.Action.CLOSE)
                        preRoll.addLast(buf.copyOf())
                    }
                    ClipSegmenter.Action.SPLIT -> {
                        closeClip(keep = true)
                        openClip(frameStart)
                        encode(buf)
                        clipEndMs = recordedMs
                    }
                }
                if (lastProgress < 0 || recordedMs - lastProgress >= 200) {
                    lastProgress = recordedMs
                    report(level)
                }
            }
            segmenter.finish()?.let { closeClip(keep = it == ClipSegmenter.Action.CLOSE) }
        } catch (t: Throwable) {
            failure = t
        } finally {
            try {
                if (codec != null) closeClip(keep = true)
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
            try {
                source.close()
            } catch (_: Exception) {
            }
        }
        failure?.let { callback.onError(it) }
    }

    /** Fills [buf] completely; false when the source has nothing (end of a test source) or on stop. */
    private fun readFrame(buf: ShortArray): Boolean {
        var got = 0
        while (got < buf.size) {
            if (stopRequested) return false
            val n = source.read(buf, got, buf.size - got)
            if (n < 0) throw IOException("Lỗi đọc micro (mã $n)")
            if (n == 0) return false
            got += n
        }
        return true
    }

    private fun report(level: Float) {
        val skipped = (framesSeen - framesWritten).coerceAtLeast(0) * frameMs
        callback.onProgress(RecProgress(recordedMs, level, segmenter.inClip, clipsSaved, skipped))
    }

    private fun openClip(startMs: Long) {
        outDir.mkdirs()
        val f = File(outDir, fileName(clipIndex))
        clipFile = f
        clipFrames = 0
        clipSamplesFed = 0
        clipStartMs = startMs.coerceAtLeast(timelineOffsetMs)
        clipEndMs = clipStartMs
        clipRecordedAt = System.currentTimeMillis() - (recordedMs - clipStartMs)
        out = BufferedOutputStream(f.outputStream(), 16 * 1024)
        codec = createEncoder()
    }

    private fun encode(frame: ShortArray) {
        val c = codec ?: return
        framesWritten++
        var off = 0
        while (off < frame.size) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(c, endOfStream = false)
                continue
            }
            val input: ByteBuffer = c.getInputBuffer(idx) ?: throw IOException("Encoder input buffer null")
            input.clear()
            val count = minOf(frame.size - off, input.remaining() / 2)
            input.order(ByteOrder.nativeOrder()).asShortBuffer().put(frame, off, count)
            c.queueInputBuffer(idx, 0, count * 2, (clipSamplesFed + off) * 1_000_000 / sampleRate, 0)
            off += count
        }
        clipSamplesFed += frame.size
        drain(c, endOfStream = false)
    }

    private fun closeClip(keep: Boolean) {
        val c = codec ?: return
        codec = null
        try {
            val idx = c.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, clipSamplesFed * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(c, endOfStream = true)
            }
        } finally {
            try {
                c.stop()
            } catch (_: Exception) {
            }
            c.release()
            out?.let {
                it.flush()
                it.close()
            }
            out = null
        }
        val file = clipFile ?: return
        clipFile = null
        if (!keep || clipFrames == 0) {
            file.delete()
            return
        }
        val durationMs = clipFrames.toLong() * Adts.SAMPLES_PER_FRAME * 1000 / sampleRate
        val index = clipIndex++
        clipsSaved++
        callback.onClipClosed(index, file, clipStartMs, maxOf(clipEndMs, clipStartMs + 1), durationMs, clipRecordedAt)
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        // Prefer the platform software encoder: identical behaviour on every device, unlike vendor
        // hardware encoders that may reject low sample rates.
        val preferred = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && it.name == "c2.android.aac.encoder"
        }
        val c = preferred?.let { MediaCodec.createByCodecName(it.name) }
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        return c
    }

    private fun drain(c: MediaCodec, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idleTries = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleTries > 100) return
                }
                idx < 0 -> Unit // format/buffers changed: nothing to write
                else -> {
                    val output = c.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (output != null && !isConfig && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        output.get(bytes)
                        out?.let {
                            it.write(Adts.header(info.size, sampleRate, 1))
                            it.write(bytes)
                            clipFrames++
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun rmsLevel(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / n) / Short.MAX_VALUE
        // Speech RMS is mostly 0.01–0.3; scale so a normal voice fills about half the meter.
        return (rms * 4).toFloat().coerceIn(0f, 1f)
    }
}
