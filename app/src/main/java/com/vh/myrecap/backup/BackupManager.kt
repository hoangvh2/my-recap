package com.vh.myrecap.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vh.myrecap.BuildConfig
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.core.BackupArchive
import com.vh.myrecap.core.BackupException
import com.vh.myrecap.core.Item
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Session
import com.vh.myrecap.reminder.Reminders
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BackupResult(val items: Int, val sessions: Int, val bytes: Long)

data class RestoreResult(
    val itemsAdded: Int,
    val itemsSkipped: Int,
    val sessionsAdded: Int,
    val sessionsSkipped: Int,
    val createdAt: Long,
)

/**
 * Backup and restore of everything the user made: items, quick captures and folders (metadata,
 * transcripts, summaries; audio optional). API keys are not included: they are encrypted with a key
 * that never leaves this phone, and belong to the user's account rather than their data.
 *
 * Restore merges: anything already on the phone (same id) is kept as is, so restoring an old backup
 * never overwrites newer edits.
 */
class BackupManager(private val context: Context) {
    private val app = MyRecapApp.from(context)

    fun export(out: OutputStream, includeAudio: Boolean): BackupResult {
        val items = app.items.list()
        val sessions = app.store.list().filterNot { it.isRecording }
        val files = mutableListOf<Pair<String, File>>()
        for (s in sessions) {
            app.store.dir(s.id).listFiles()?.sortedBy { it.name }?.forEach { f ->
                val skip = !f.isFile || f.name.endsWith(".tmp") || (!includeAudio && f.name.endsWith(".aac"))
                if (!skip) files += "${BackupArchive.SESSIONS}${s.id}/${f.name}" to f
            }
        }
        val itemsJson = JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString().toByteArray()
        val manifest = BackupArchive.Manifest(
            format = BackupArchive.FORMAT,
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            items = items.size,
            sessions = sessions.size,
            includesAudio = includeAudio,
        )
        val counting = CountingOutputStream(out)
        BackupArchive.write(counting, manifest, mapOf(BackupArchive.ITEMS to itemsJson), files)
        app.settings.update { it.copy(lastBackupAt = manifest.createdAt) }
        return BackupResult(items.size, sessions.size, counting.count)
    }

    fun restore(input: InputStream): RestoreResult {
        val work = File(context.cacheDir, "restore-${System.currentTimeMillis()}")
        try {
            val manifest = BackupArchive.extract(input, work)

            // Items: add what is missing, keep what the phone already has.
            var itemsAdded = 0
            var itemsSkipped = 0
            File(work, BackupArchive.ITEMS).takeIf { it.isFile }?.let { f ->
                val arr = try {
                    JSONArray(f.readText())
                } catch (_: Exception) {
                    throw BackupException("Danh sách mục trong bản sao lưu bị hỏng")
                }
                val existing = app.items.list().map { it.id }.toHashSet()
                for (i in 0 until arr.length()) {
                    val item = runCatching { Item.fromJson(arr.getJSONObject(i)) }.getOrNull() ?: continue
                    if (item.id in existing) {
                        itemsSkipped++
                    } else {
                        app.items.upsert(item)
                        itemsAdded++
                    }
                }
            }

            // Folders and captures: move each missing one into place, then make its metadata match the files.
            var sessionsAdded = 0
            var sessionsSkipped = 0
            File(work, BackupArchive.SESSIONS).listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val meta = File(dir, "session.json")
                val session = runCatching { Session.fromJson(JSONObject(meta.readText())) }.getOrNull()
                if (session == null || session.id != dir.name) return@forEach
                val target = app.store.dir(session.id)
                if (target.exists()) {
                    sessionsSkipped++
                    return@forEach
                }
                if (!dir.renameTo(target)) {
                    dir.copyRecursively(target, overwrite = false)
                }
                app.store.update(session.id) { s ->
                    s.copy(
                        state = if (s.isRecording) RecState.STOPPED else s.state,
                        segments = s.segments.map { seg -> seg.copy(hasAudio = seg.hasAudio && app.store.audioFile(s.id, seg).exists()) },
                    )
                }
                sessionsAdded++
            }
            app.store.refresh()
            Reminders.syncAll(context)
            return RestoreResult(itemsAdded, itemsSkipped, sessionsAdded, sessionsSkipped, manifest.createdAt)
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Weekly automatic backup into a folder the user picked (e.g. Google Drive): text only, the
     * newest [KEEP_AUTO] kept. Returns the file name written.
     */
    fun autoBackup(): String {
        val uri = app.settings.current.autoBackupUri.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: throw BackupException("Chưa chọn thư mục sao lưu")
        val folder = DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() }
            ?: throw BackupException("Không ghi được vào thư mục sao lưu; hãy chọn lại thư mục")
        val name = AUTO_PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip"
        val file = folder.createFile("application/zip", name) ?: throw BackupException("Không tạo được tệp sao lưu")
        try {
            context.contentResolver.openOutputStream(file.uri)?.use { export(it, includeAudio = false) }
                ?: throw BackupException("Không mở được tệp sao lưu")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        folder.listFiles()
            .filter { it.name?.startsWith(AUTO_PREFIX) == true }
            .sortedByDescending { it.name }
            .drop(KEEP_AUTO)
            .forEach { it.delete() }
        return name
    }

    companion object {
        const val AUTO_PREFIX = "myrecap-tu-dong-"
        const val KEEP_AUTO = 4
        private const val WORK = "auto-backup"

        fun suggestedName(): String = "myrecap-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip"

        /** Remembers the folder the user picked and starts the weekly backup. */
        fun enableAuto(context: Context, treeUri: Uri) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            MyRecapApp.from(context).settings.update { it.copy(autoBackupUri = treeUri.toString(), autoBackupError = "") }
            schedule(context)
        }

        fun disableAuto(context: Context) {
            val app = MyRecapApp.from(context)
            app.settings.current.autoBackupUri.takeIf { it.isNotBlank() }?.let { uri ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            app.settings.update { it.copy(autoBackupUri = "", autoBackupError = "") }
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = MyRecapApp.from(applicationContext)
        if (app.settings.current.autoBackupUri.isBlank()) return Result.success()
        return try {
            BackupManager(applicationContext).autoBackup()
            app.settings.update { it.copy(autoBackupError = "") }
            Result.success()
        } catch (e: Exception) {
            app.settings.update { it.copy(autoBackupError = e.message ?: "Sao lưu tự động thất bại") }
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()
    override fun close() = out.close()
}
