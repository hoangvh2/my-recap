package com.vh.myrecap.data

import com.vh.myrecap.core.SessionMode
import org.json.JSONArray
import org.json.JSONObject

enum class RecState { RECORDING, PAUSED, STOPPED, INTERRUPTED }

enum class TaskStatus { PENDING, RUNNING, DONE, ERROR }

/** One conversational turn (VAD clip), or a fixed-length chunk in recordings made before v0.2. */
data class Segment(
    val index: Int,
    val fileName: String,
    /** Position on the folder timeline (recorded time, pauses excluded). */
    val startMs: Long,
    /** Audio actually stored (long silences trimmed), used for cost and playback. */
    val durationMs: Long,
    val stt: TaskStatus = TaskStatus.PENDING,
    val error: String? = null,
    /** Short topic title written by the STT model. */
    val title: String? = null,
    /** Timeline position where the clip ended; differs from start+duration when silence was trimmed. */
    val endMs: Long = startMs + durationMs,
    /** Wall-clock time the clip started, for display. 0 for old data. */
    val recordedAt: Long = 0,
    /** False once the audio file was deleted to save space; the transcript stays. */
    val hasAudio: Boolean = true,
) {
    val number: Int get() = index + 1
}

/** A summary the user requested over a chosen set of clips. */
data class SummaryJob(
    val id: String,
    val createdAt: Long,
    val mode: SessionMode,
    val clipIndexes: List<Int>,
    val status: TaskStatus,
    val error: String? = null,
    /** Human-readable progress while running (long folders take several requests). */
    val progress: String? = null,
)

/**
 * A folder: everything recorded for one interview or meeting, possibly across several
 * recording runs, plus the summaries made from it.
 */
data class Session(
    val id: String,
    val title: String,
    val mode: SessionMode,
    val createdAt: Long,
    val state: RecState,
    /** Folder timeline length (recorded time, pauses excluded). */
    val durationMs: Long = 0,
    val segments: List<Segment> = emptyList(),
    val bookmarksMs: List<Long> = emptyList(),
    val summaries: List<SummaryJob> = emptyList(),
    /** Last processing error shown to the user. */
    val error: String? = null,
    /** Quick captures only: AI analysis into items (null for folders). */
    val extract: TaskStatus? = null,
) {
    val isMemo: Boolean get() = mode == SessionMode.MEMO
    val extractBusy: Boolean get() = extract == TaskStatus.PENDING || extract == TaskStatus.RUNNING
    val audioClips: List<Segment> get() = segments.filter { it.hasAudio }

    val isRecording: Boolean get() = state == RecState.RECORDING || state == RecState.PAUSED
    val transcribedCount: Int get() = segments.count { it.stt == TaskStatus.DONE }
    val allTranscribed: Boolean get() = segments.isNotEmpty() && segments.all { it.stt == TaskStatus.DONE }
    val audioMs: Long get() = segments.sumOf { it.durationMs }
    val sttBusy: Boolean get() = segments.any { it.stt == TaskStatus.PENDING || it.stt == TaskStatus.RUNNING }
    val summaryBusy: Boolean get() = summaries.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }
    val nextSegmentIndex: Int get() = (segments.maxOfOrNull { it.index } ?: -1) + 1

    fun toJson(): JSONObject = JSONObject()
        .put("version", 2)
        .put("id", id)
        .put("title", title)
        .put("mode", mode.name)
        .put("createdAt", createdAt)
        .put("state", state.name)
        .put("durationMs", durationMs)
        .put("segments", JSONArray().also { arr ->
            segments.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("index", s.index)
                        .put("file", s.fileName)
                        .put("startMs", s.startMs)
                        .put("durationMs", s.durationMs)
                        .put("endMs", s.endMs)
                        .put("recordedAt", s.recordedAt)
                        .put("stt", s.stt.name)
                        .putOpt("title", s.title)
                        .put("audio", s.hasAudio)
                        .putOpt("error", s.error),
                )
            }
        })
        .put("bookmarks", JSONArray().also { arr -> bookmarksMs.forEach { arr.put(it) } })
        .put("summaries", JSONArray().also { arr ->
            summaries.forEach { j ->
                arr.put(
                    JSONObject()
                        .put("id", j.id)
                        .put("createdAt", j.createdAt)
                        .put("mode", j.mode.name)
                        .put("clips", JSONArray().also { c -> j.clipIndexes.forEach { c.put(it) } })
                        .put("status", j.status.name)
                        .putOpt("error", j.error)
                        .putOpt("progress", j.progress),
                )
            }
        })
        .putOpt("error", error)
        .putOpt("extract", extract?.name)

    companion object {
        fun fromJson(o: JSONObject): Session {
            val segs = o.optJSONArray("segments") ?: JSONArray()
            val marks = o.optJSONArray("bookmarks") ?: JSONArray()
            val jobs = o.optJSONArray("summaries") ?: JSONArray()
            val mode = enumOr(o.optString("mode"), SessionMode.MEETING)
            val segments = (0 until segs.length()).map { i ->
                val s = segs.getJSONObject(i)
                val start = s.optLong("startMs")
                val duration = s.optLong("durationMs")
                Segment(
                    index = s.getInt("index"),
                    fileName = s.getString("file"),
                    startMs = start,
                    durationMs = duration,
                    stt = enumOr(s.optString("stt"), TaskStatus.PENDING),
                    error = s.optStringOrNull("error"),
                    title = s.optStringOrNull("title"),
                    endMs = if (s.has("endMs")) s.getLong("endMs") else start + duration,
                    recordedAt = s.optLong("recordedAt", 0),
                    hasAudio = s.optBoolean("audio", true),
                )
            }
            var summaries = (0 until jobs.length()).map { i ->
                val j = jobs.getJSONObject(i)
                val clips = j.optJSONArray("clips") ?: JSONArray()
                SummaryJob(
                    id = j.getString("id"),
                    createdAt = j.optLong("createdAt"),
                    mode = enumOr(j.optString("mode"), mode),
                    clipIndexes = (0 until clips.length()).map { clips.getInt(it) },
                    status = enumOr(j.optString("status"), TaskStatus.PENDING),
                    error = j.optStringOrNull("error"),
                    progress = j.optStringOrNull("progress"),
                )
            }
            // v0.1 kept one automatic summary per recording in "summary" + summary.md.
            val legacy = o.optStringOrNull("summary")
            if (summaries.isEmpty() && legacy != null) {
                summaries = listOf(
                    SummaryJob(
                        id = LEGACY_SUMMARY_ID,
                        createdAt = o.optLong("createdAt"),
                        mode = mode,
                        clipIndexes = segments.map { it.index },
                        status = enumOr(legacy, TaskStatus.PENDING).let { if (it == TaskStatus.DONE) it else TaskStatus.ERROR },
                        error = if (legacy == TaskStatus.DONE.name) null else "Tóm tắt cũ chưa hoàn tất — hãy tạo tóm tắt mới",
                    ),
                )
            }
            return Session(
                id = o.getString("id"),
                title = o.optString("title", ""),
                mode = mode,
                createdAt = o.optLong("createdAt"),
                state = enumOr(o.optString("state"), RecState.INTERRUPTED),
                durationMs = o.optLong("durationMs"),
                segments = segments,
                bookmarksMs = (0 until marks.length()).map { marks.getLong(it) },
                summaries = summaries,
                error = o.optStringOrNull("error"),
                extract = o.optStringOrNull("extract")?.let { enumOr(it, TaskStatus.PENDING) },
            )
        }

        const val LEGACY_SUMMARY_ID = "legacy"

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
