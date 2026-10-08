package com.vh.myrecap.data

import com.vh.myrecap.core.SegmentText
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
 * File-based session storage: one directory per session holding `session.json`, the audio
 * segments, one transcript file per segment and `summary.md`. Plain files keep recordings
 * recoverable even if metadata is lost, and avoid a database for a handful of records.
 *
 * All metadata writes go through [update] under one lock: the recording service and the
 * background worker both modify sessions.
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

    fun audioFile(id: String, segment: Segment) = File(dir(id), segment.fileName)

    fun transcriptFile(id: String, index: Int) = File(dir(id), String.format(Locale.ROOT, "seg_%03d.txt", index))

    fun summaryFile(id: String) = File(dir(id), "summary.md")

    fun readTranscript(id: String, index: Int): String? = transcriptFile(id, index).takeIf { it.exists() }?.readText()

    fun writeTranscript(id: String, index: Int, text: String) {
        writeAtomic(transcriptFile(id, index), text)
        _version.value++
    }

    fun readSummary(id: String): String? = summaryFile(id).takeIf { it.exists() }?.readText()

    fun writeSummary(id: String, text: String) {
        writeAtomic(summaryFile(id), text)
        _version.value++
    }

    fun fullTranscript(session: Session): String = TranscriptAssembler.assemble(
        session.segments.map { SegmentText(it.startMs, it.durationMs, readTranscript(session.id, it.index)) },
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
    }
}
