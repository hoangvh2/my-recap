package com.vh.myrecap.data

import com.vh.myrecap.core.SessionMode
import org.json.JSONArray
import org.json.JSONObject

enum class RecState { RECORDING, PAUSED, STOPPED, INTERRUPTED }

enum class TaskStatus { PENDING, RUNNING, DONE, ERROR }

data class Segment(
    val index: Int,
    val fileName: String,
    val startMs: Long,
    val durationMs: Long,
    val stt: TaskStatus = TaskStatus.PENDING,
    val error: String? = null,
)

data class Session(
    val id: String,
    val title: String,
    val mode: SessionMode,
    val createdAt: Long,
    val state: RecState,
    val durationMs: Long = 0,
    val segments: List<Segment> = emptyList(),
    val bookmarksMs: List<Long> = emptyList(),
    /** Null until a summary is requested. */
    val summary: TaskStatus? = null,
    /** Last processing error shown to the user (STT or summary). */
    val error: String? = null,
) {
    val isRecording: Boolean get() = state == RecState.RECORDING || state == RecState.PAUSED
    val transcribedCount: Int get() = segments.count { it.stt == TaskStatus.DONE }
    val allTranscribed: Boolean get() = segments.isNotEmpty() && segments.all { it.stt == TaskStatus.DONE }
    val hasPendingWork: Boolean
        get() = segments.any { it.stt == TaskStatus.PENDING || it.stt == TaskStatus.RUNNING } ||
            summary == TaskStatus.PENDING || summary == TaskStatus.RUNNING

    fun toJson(): JSONObject = JSONObject()
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
                        .put("stt", s.stt.name)
                        .putOpt("error", s.error),
                )
            }
        })
        .put("bookmarks", JSONArray().also { arr -> bookmarksMs.forEach { arr.put(it) } })
        .putOpt("summary", summary?.name)
        .putOpt("error", error)

    companion object {
        fun fromJson(o: JSONObject): Session {
            val segs = o.optJSONArray("segments") ?: JSONArray()
            val marks = o.optJSONArray("bookmarks") ?: JSONArray()
            return Session(
                id = o.getString("id"),
                title = o.optString("title", ""),
                mode = enumOr(o.optString("mode"), SessionMode.MEETING),
                createdAt = o.optLong("createdAt"),
                state = enumOr(o.optString("state"), RecState.INTERRUPTED),
                durationMs = o.optLong("durationMs"),
                segments = (0 until segs.length()).map { i ->
                    val s = segs.getJSONObject(i)
                    Segment(
                        index = s.getInt("index"),
                        fileName = s.getString("file"),
                        startMs = s.optLong("startMs"),
                        durationMs = s.optLong("durationMs"),
                        stt = enumOr(s.optString("stt"), TaskStatus.PENDING),
                        error = s.optStringOrNull("error"),
                    )
                },
                bookmarksMs = (0 until marks.length()).map { marks.getLong(it) },
                summary = o.optStringOrNull("summary")?.let { enumOr(it, TaskStatus.PENDING) },
                error = o.optStringOrNull("error"),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
