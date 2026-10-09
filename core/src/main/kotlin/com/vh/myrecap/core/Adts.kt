package com.vh.myrecap.core

import java.io.File
import java.io.RandomAccessFile

/**
 * ADTS framing for raw AAC-LC frames. Recordings are stored as ADTS (.aac) because every frame
 * carries its own header: a file cut off by a crash or a killed process stays playable and can be
 * sent to STT as is (Gemini accepts `audio/aac`).
 */
object Adts {
    const val HEADER_SIZE = 7
    const val SAMPLES_PER_FRAME = 1024
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun sampleRateIndex(sampleRate: Int): Int {
        val i = SAMPLE_RATES.indexOf(sampleRate)
        require(i >= 0) { "Unsupported AAC sample rate $sampleRate" }
        return i
    }

    /** Header for one AAC-LC frame whose raw payload is [payloadSize] bytes. */
    fun header(payloadSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val frameLen = payloadSize + HEADER_SIZE
        require(frameLen < (1 shl 13)) { "AAC frame too large: $frameLen" }
        val profile = 1 // AAC LC (audio object type 2) minus 1
        val freq = sampleRateIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((profile shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (frameLen shr 11)).toByte(),
            ((frameLen and 0x7FF) shr 3).toByte(),
            (((frameLen and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    class ScanResult(val frames: Int, val validBytes: Long, val sampleRate: Int?) {
        fun durationMs(fallbackSampleRate: Int): Long {
            val sr = sampleRate ?: fallbackSampleRate
            return frames.toLong() * SAMPLES_PER_FRAME * 1000 / sr
        }
    }

    /** Counts complete frames; stops at the first truncated or corrupt frame. */
    fun scan(file: File): ScanResult {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val h = ByteArray(HEADER_SIZE)
            var pos = 0L
            var frames = 0
            var sampleRate: Int? = null
            while (pos + HEADER_SIZE <= len) {
                raf.seek(pos)
                raf.readFully(h)
                val sync = ((h[0].toInt() and 0xFF) shl 4) or ((h[1].toInt() and 0xF0) shr 4)
                if (sync != 0xFFF) break
                val frameLen = ((h[3].toInt() and 0x03) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xE0) shr 5)
                if (frameLen < HEADER_SIZE || pos + frameLen > len) break
                if (sampleRate == null) {
                    val idx = (h[2].toInt() and 0x3C) shr 2
                    sampleRate = SAMPLE_RATES.getOrNull(idx)
                }
                pos += frameLen
                frames++
            }
            return ScanResult(frames, pos, sampleRate)
        }
    }

    /** Drops a trailing partial frame (e.g. after a crash) so decoders do not choke on it. */
    fun repair(file: File): ScanResult {
        val result = scan(file)
        if (result.validBytes < file.length()) RandomAccessFile(file, "rw").use { it.setLength(result.validBytes) }
        return result
    }
}
