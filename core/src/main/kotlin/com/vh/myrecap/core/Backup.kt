package com.vh.myrecap.core

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : Exception(message)

/**
 * The backup file: a plain zip that any computer can open. `manifest.json` describes it,
 * `items.json` holds the secretary's items and `sessions/<id>/…` each folder or capture exactly as
 * stored (metadata, transcripts, summaries, optionally audio).
 */
object BackupArchive {
    /** Bump when the layout changes in a way older apps cannot read. */
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val ITEMS = "items.json"
    const val SESSIONS = "sessions/"

    /** A backup bigger than this is refused when restoring (protects against zip bombs). */
    const val MAX_RESTORE_BYTES = 8L * 1024 * 1024 * 1024

    data class Manifest(
        val format: Int,
        val createdAt: Long,
        val appVersion: String,
        val items: Int,
        val sessions: Int,
        val includesAudio: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("format", format)
            .put("createdAt", createdAt)
            .put("appVersion", appVersion)
            .put("items", items)
            .put("sessions", sessions)
            .put("includesAudio", includesAudio)

        companion object {
            fun fromJson(o: JSONObject) = Manifest(
                format = o.optInt("format", 0),
                createdAt = o.optLong("createdAt", 0),
                appVersion = o.optString("appVersion", ""),
                items = o.optInt("items", 0),
                sessions = o.optInt("sessions", 0),
                includesAudio = o.optBoolean("includesAudio", false),
            )
        }
    }

    /** Writes the archive: manifest first, then in-memory entries, then files streamed from disk. */
    fun write(out: OutputStream, manifest: Manifest, entries: Map<String, ByteArray>, files: List<Pair<String, File>>) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                require(isSafeName(name)) { "unsafe entry $name" }
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, manifest.toJson().toString(2).toByteArray())
            entries.forEach { (name, bytes) -> put(name, bytes) }
            for ((name, file) in files) {
                require(isSafeName(name)) { "unsafe entry $name" }
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Relative, forward-slash names without `..`: an entry can never land outside the target folder. */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').none { it == ".." || it == "." } && !Regex("^[A-Za-z]:").containsMatchIn(name)

    /**
     * Extracts into [dest] (which should be empty) and returns the manifest. Refuses unsafe names,
     * archives without a manifest, newer formats and archives over [maxBytes] once unpacked.
     */
    fun extract(input: InputStream, dest: File, maxBytes: Long = MAX_RESTORE_BYTES): Manifest {
        dest.mkdirs()
        val root = dest.canonicalFile
        var total = 0L
        var manifest: Manifest? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!isSafeName(name)) throw BackupException("Tệp sao lưu chứa đường dẫn không hợp lệ: $name")
                val target = File(root, name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw BackupException("Tệp sao lưu chứa đường dẫn không hợp lệ: $name")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw BackupException("Tệp sao lưu quá lớn")
                        out.write(buf, 0, n)
                    }
                }
                if (name == MANIFEST) {
                    manifest = try {
                        Manifest.fromJson(JSONObject(target.readText()))
                    } catch (_: Exception) {
                        throw BackupException("Không đọc được thông tin bản sao lưu")
                    }
                }
            }
        }
        val m = manifest ?: throw BackupException("Không phải tệp sao lưu của My Recap")
        if (m.format < 1 || m.format > FORMAT) throw BackupException("Bản sao lưu được tạo bởi phiên bản mới hơn; hãy cập nhật app")
        return m
    }
}
