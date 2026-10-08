package com.vh.myrecap.recorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

object AudioFiles {
    /**
     * Re-wraps an ADTS .aac file into an .m4a container without re-encoding. Whisper-style APIs
     * document m4a but not raw ADTS.
     */
    fun adtsToM4a(input: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Không tìm thấy track âm thanh trong ${input.name}")
            extractor.selectTrack(track)
            output.delete()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocate(64 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(outTrack, buffer, info)
                samples++
                extractor.advance()
            }
            if (samples == 0) throw IOException("File âm thanh rỗng: ${input.name}")
            muxer.stop()
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
