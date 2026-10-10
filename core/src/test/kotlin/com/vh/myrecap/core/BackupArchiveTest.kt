package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val manifest = BackupArchive.Manifest(BackupArchive.FORMAT, 1_000, "0.3.0", items = 2, sessions = 1, includesAudio = false)

    @Test
    fun roundTrip() {
        val src = tmp.newFolder("src")
        val transcript = java.io.File(src, "seg_000.txt").apply { writeText("Ứng viên: xin chào") }
        val out = ByteArrayOutputStream()
        BackupArchive.write(
            out, manifest,
            entries = mapOf(BackupArchive.ITEMS to "[1,2]".toByteArray()),
            files = listOf("sessions/abc/seg_000.txt" to transcript, "sessions/abc/missing.aac" to java.io.File(src, "nope")),
        )
        val dest = tmp.newFolder("dest")
        val read = BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), dest)
        assertEquals(manifest, read)
        assertEquals("[1,2]", java.io.File(dest, "items.json").readText())
        assertEquals("Ứng viên: xin chào", java.io.File(dest, "sessions/abc/seg_000.txt").readText())
        assertFalse("missing source files are skipped", java.io.File(dest, "sessions/abc/missing.aac").exists())
    }

    @Test
    fun unsafeNames() {
        for (bad in listOf("../evil", "sessions/../../evil", "/etc/passwd", "C:/x", "a\\b", "", "./x")) {
            assertFalse(bad, BackupArchive.isSafeName(bad))
        }
        assertTrue(BackupArchive.isSafeName("sessions/20261010-1200-ab12/session.json"))
    }

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertRefused(bytes: ByteArray, maxBytes: Long = BackupArchive.MAX_RESTORE_BYTES, messagePart: String) {
        val dest = tmp.newFolder()
        try {
            BackupArchive.extract(ByteArrayInputStream(bytes), dest, maxBytes)
            fail("expected refusal")
        } catch (e: BackupException) {
            assertTrue(e.message, e.message!!.contains(messagePart))
        }
    }

    @Test
    fun refusesZipSlip() {
        val m = manifest.toJson().toString().toByteArray()
        assertRefused(rawZip("manifest.json" to m, "../outside.txt" to "x".toByteArray()), messagePart = "không hợp lệ")
        assertFalse(java.io.File(tmp.root, "outside.txt").exists())
    }

    @Test
    fun refusesForeignNewerAndOversizedArchives() {
        assertRefused(rawZip("hello.txt" to "hi".toByteArray()), messagePart = "Không phải")
        val newer = manifest.copy(format = BackupArchive.FORMAT + 1).toJson().toString().toByteArray()
        assertRefused(rawZip("manifest.json" to newer), messagePart = "phiên bản mới hơn")
        val m = manifest.toJson().toString().toByteArray()
        assertRefused(rawZip("manifest.json" to m, "big.bin" to ByteArray(10_000)), maxBytes = 5_000, messagePart = "quá lớn")
    }
}
