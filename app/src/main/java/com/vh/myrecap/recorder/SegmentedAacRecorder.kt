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

    /** Blocking read; returns samples read, or a negative AudioRecord error code. */
    fun read(buffer: ShortArray, size: Int): Int
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

    override fun read(buffer: ShortArray, size: Int): Int = record?.read(buffer, 0, size) ?: -1

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

/**
 * Records PCM → AAC-LC (32 kbps mono) and writes ADTS files that roll over every
 * [segmentDurationMs] of recorded audio. Rolling happens on encoded frame boundaries, so segments
 * are gapless. Pausing drops input, so the timeline ([Callback.onProgress]) excludes paused time.
 *
 * Runs on its own thread; callbacks are invoked from that thread.
 */
class SegmentedAacRecorder(
    private val source: PcmSource,
    private val outDir: File,
    private val segmentDurationMs: Long,
    private val callback: Callback,
    private val fileName: (Int) -> String,
    private val bitRate: Int = 32_000,
) {
    interface Callback {
        fun onProgress(recordedMs: Long, level: Float)
        fun onSegmentClosed(index: Int, file: File, startMs: Long, durationMs: Long)

        /** Fatal error; the recorder has already stopped and closed the current segment. */
        fun onError(error: Throwable)
    }

    @Volatile
    var paused: Boolean = false

    @Volatile
    private var stopRequested = false

    @Volatile
    var recordedMs: Long = 0
        private set

    private var thread: Thread? = null
    private val sampleRate get() = source.sampleRate

    // Segment state, touched only by the recorder thread.
    private var out: OutputStream? = null
    private var segIndex = 0
    private var segFrames = 0
    private var segStartMs = 0L
    private var segFile: File? = null

    fun start() {
        check(thread == null) { "already started" }
        thread = Thread(::recordLoop, "aac-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Stops recording and blocks until the final segment is closed. */
    fun stop() {
        stopRequested = true
        thread?.join(10_000)
    }

    private fun recordLoop() {
        var codec: MediaCodec? = null
        var failure: Throwable? = null
        try {
            codec = createEncoder()
            source.start()
            val buf = ShortArray(1024)
            var samplesFed = 0L
            var lastProgress = -1L
            while (!stopRequested) {
                val n = source.read(buf, buf.size)
                if (n < 0) throw IOException("Lỗi đọc micro (mã $n)")
                if (n == 0) continue
                val level = rmsLevel(buf, n)
                if (!paused) {
                    feed(codec, buf, n, samplesFed)
                    samplesFed += n
                }
                drain(codec, endOfStream = false)
                recordedMs = samplesFed * 1000 / sampleRate
                // ~5 updates per second is plenty for a timer and level meter.
                if (lastProgress < 0 || recordedMs - lastProgress >= 200 || paused) {
                    lastProgress = recordedMs
                    callback.onProgress(recordedMs, level)
                }
            }
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, samplesFed * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(codec, endOfStream = true)
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            try {
                closeSegment()
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            try {
                source.close()
            } catch (_: Exception) {
            }
        }
        failure?.let { callback.onError(it) }
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
        val codec = preferred?.let { MediaCodec.createByCodecName(it.name) }
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        return codec
    }

    private fun feed(codec: MediaCodec, buf: ShortArray, n: Int, samplesBefore: Long) {
        var off = 0
        while (off < n) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(codec, endOfStream = false)
                continue
            }
            val input: ByteBuffer = codec.getInputBuffer(idx) ?: throw IOException("Encoder input buffer null")
            input.clear()
            val count = minOf(n - off, input.remaining() / 2)
            input.order(ByteOrder.nativeOrder()).asShortBuffer().put(buf, off, count)
            val ptsUs = (samplesBefore + off) * 1_000_000 / sampleRate
            codec.queueInputBuffer(idx, 0, count * 2, ptsUs, 0)
            off += count
        }
    }

    private fun drain(codec: MediaCodec, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idleTries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleTries > 100) return
                }
                idx < 0 -> Unit // format/buffers changed: nothing to write
                else -> {
                    val output = codec.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (output != null && !isConfig && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        writeFrame(output, info.size)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun writeFrame(frame: ByteBuffer, size: Int) {
        val stream = out ?: openSegment()
        val bytes = ByteArray(size)
        frame.get(bytes)
        stream.write(Adts.header(size, sampleRate, 1))
        stream.write(bytes)
        segFrames++
        if (segFrames.toLong() * Adts.SAMPLES_PER_FRAME * 1000 / sampleRate >= segmentDurationMs) closeSegment()
    }

    private fun openSegment(): OutputStream {
        outDir.mkdirs()
        val f = File(outDir, fileName(segIndex))
        segFile = f
        segFrames = 0
        return BufferedOutputStream(f.outputStream(), 16 * 1024).also { out = it }
    }

    private fun closeSegment() {
        val stream = out ?: return
        out = null
        stream.flush()
        stream.close()
        val file = segFile ?: return
        val durationMs = segFrames.toLong() * Adts.SAMPLES_PER_FRAME * 1000 / sampleRate
        if (segFrames == 0) {
            file.delete()
            return
        }
        val index = segIndex
        val start = segStartMs
        segIndex++
        segStartMs += durationMs
        callback.onSegmentClosed(index, file, start, durationMs)
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
