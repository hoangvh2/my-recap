package com.vh.myrecap.core

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdtsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun headerMatchesKnownAacLc16kMonoLayout() {
        // 100-byte payload -> frame length 107 (0x6B)
        val h = Adts.header(100, 16000, 1)
        val expected = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x0D, 0x7F, 0xFC.toByte())
        assertEquals(expected.toList(), h.toList())
    }

    @Test
    fun rejectsUnsupportedRate() {
        assertFailsWith<IllegalArgumentException> { Adts.header(10, 12345, 1) }
    }

    @Test
    fun scanCountsFramesAndRepairDropsPartialTail() {
        val f = tmp.newFile("seg.aac")
        f.outputStream().use { out ->
            repeat(10) { i ->
                val payload = ByteArray(50 + i) { 0x11 }
                out.write(Adts.header(payload.size, 16000, 1))
                out.write(payload)
            }
            // Truncated 11th frame, as if the process died mid-write.
            out.write(Adts.header(80, 16000, 1))
            out.write(ByteArray(20))
        }
        val before = f.length()
        val scan = Adts.repair(f)
        assertEquals(10, scan.frames)
        assertEquals(16000, scan.sampleRate)
        assertEquals(scan.validBytes, f.length())
        assertEquals(before - 27, f.length())
        assertEquals(640L, scan.durationMs(16000)) // 10 * 1024 / 16 kHz
    }

    @Test
    fun scanOfGarbageIsEmpty() {
        val f = tmp.newFile("bad.aac")
        f.writeBytes(ByteArray(100) { 1 })
        assertEquals(0, Adts.scan(f).frames)
    }
}
