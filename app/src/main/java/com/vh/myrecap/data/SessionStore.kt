package com.vh.myrecap.data

import com.vh.myrecap.core.ClipText
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.TranscriptAssembler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * File-based folder storage: one directory per folder holding `session.json`, the audio clips,
 * one transcript file per clip and one Markdown file per summary. Plain files keep recordings
 * recoverable even if metadata is lost, and avoid a database for a handful of records.
 *
 * All metadata writes go through [update] under one lock: the recording service, the background
 * worker and the UI all modify folders.
 */
class SessionStore(private val root: File) {
    private val lock = Any()
    private val _version = MutableStateFlow(0L)

    /** Bumped on every write so the UI can reload. */
    val version: StateFlow<Long> = _version

    init {
        root.mkdirs()
    }

    fun dir(id: String) = File(root, id)

    fun create(mode: SessionMode, title: String): Session {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now)) + "-" +
            UUID.randomUUID().toString().take(4)
        val session = Session(
            id = id,
            title = title.ifBlank { defaultTitle(mode, now) },
            mode = mode,
            createdAt = now,
            state = RecState.RECORDING,
        )
        synchronized(lock) {
            dir(id).mkdirs()
            write(session)
        }
        return session
    }

    fun get(id: String): Session? = synchronized(lock) { read(id) }

    fun list(): List<Session> = synchronized(lock) {
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { read(it.name) }.orEmpty()
    }.sortedByDescending { it.createdAt }

    fun update(id: String, transform: (Session) -> Session): Session? = synchronized(lock) {
        val current = read(id) ?: return null
        val next = transform(current)
        if (next != current) write(next)
        next
    }

    fun delete(id: String) {
        synchronized(lock) { dir(id).deleteRecursively() }
        _version.value++
    }

    fun deleteClip(id: String, index: Int) {
        synchronized(lock) {
            val session = read(id) ?: return
            val clip = session.segments.firstOrNull { it.index == index } ?: return
            write(session.copy(segments = session.segments - clip))
            audioFile(id, clip).delete()
            transcriptFile(id, index).delete()
        }
    }

    fun audioFile(id: String, segment: Segment) = File(dir(id), segment.fileName)

    fun transcriptFile(id: String, index: Int) = File(dir(id), String.format(Locale.ROOT, "seg_%03d.txt", index))

    fun summaryFile(id: String, jobId: String) =
        File(dir(id), if (jobId == Session.LEGACY_SUMMARY_ID) "summary.md" else "summary_$jobId.md")

    fun readTranscript(id: String, index: Int): String? = transcriptFile(id, index).takeIf { it.exists() }?.readText()

    fun writeTranscript(id: String, index: Int, text: String) {
        writeAtomic(transcriptFile(id, index), text)
        _version.value++
    }

    fun readSummary(id: String, jobId: String): String? = summaryFile(id, jobId).takeIf { it.exists() }?.readText()

    fun writeSummary(id: String, jobId: String, text: String) {
        writeAtomic(summaryFile(id, jobId), text)
        _version.value++
    }

    fun deleteSummary(id: String, jobId: String) {
        synchronized(lock) {
            val session = read(id) ?: return
            write(session.copy(summaries = session.summaries.filterNot { it.id == jobId }))
            summaryFile(id, jobId).delete()
        }
    }

    /** Transcript of the given clips (all clips when [indexes] is null), in timeline order. */
    fun transcript(session: Session, indexes: Collection<Int>? = null): String = TranscriptAssembler.assemble(
        session.segments
            .filter { indexes == null || it.index in indexes }
            .map { ClipText(it.number, it.startMs, it.endMs, it.title, readTranscript(session.id, it.index)) },
        session.bookmarksMs,
    )

    private fun metaFile(id: String) = File(dir(id), "session.json")

    private fun read(id: String): Session? {
        val f = metaFile(id)
        if (!f.exists()) return null
        return try {
            Session.fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            null
        }
    }

    private fun write(session: Session) {
        writeAtomic(metaFile(session.id), session.toJson().toString())
        _version.value++
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        fun defaultTitle(mode: SessionMode, at: Long): String =
            mode.label + " " + SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date(at))

        fun segmentFileName(index: Int) = String.format(Locale.ROOT, "seg_%03d.aac", index)

        fun newJobId(): String = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(4)
    }
}
