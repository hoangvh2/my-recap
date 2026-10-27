package com.vh.myrecap.core

import org.json.JSONArray
import org.json.JSONObject

/** What the phone should let the person hear (and feel) at a moment; each has its own sound and vibration. */
enum class StudySignal {
    /** The session started. */
    START,

    /** A minute left in this step. */
    WARN,

    /** This step is out of time. */
    END,

    /** The next step started. */
    NEXT,

    /** Out of time and still waiting to move on (only when moving on is not automatic). */
    OVER,

    /** Every step is done. */
    DONE,
}

/**
 * The running state of a study session. All times are epoch milliseconds so a session survives the app being
 * killed or the phone restarting; nothing here counts seconds down.
 */
data class StudyState(
    val itemId: String,
    val phases: List<StudyPhase>,
    val index: Int = 0,
    /** When the current step runs out; meaningless for a step without a time and while [paused]. */
    val endsAt: Long = 0,
    val paused: Boolean = false,
    /** Time left on the step at the moment it was paused. */
    val pausedLeftMs: Long = 0,
    val warned: Boolean = false,
    /** END already played for the current step. */
    val ended: Boolean = false,
    /** OVER reminders already played for the current step. */
    val overs: Int = 0,
    /** Minutes added with "+5 phút" over the whole session, shown as "trễ". */
    val extraMs: Long = 0,
    val startedAt: Long = 0,
    val finished: Boolean = false,
) {
    val phase: StudyPhase? get() = phases.getOrNull(index)
    val timed: Boolean get() = phase?.minutes != null
    val last: Boolean get() = index >= phases.lastIndex
    val next: StudyPhase? get() = phases.getOrNull(index + 1)

    /** Time left on the current step; negative once over. Null for a step without a time. */
    fun remainingMs(now: Long): Long? = when {
        finished || !timed -> null
        paused -> pausedLeftMs
        else -> endsAt - now
    }

    /** When the whole session is expected to end, assuming every later step takes its planned time. */
    fun expectedEndAt(now: Long): Long {
        if (finished) return now
        val here = remainingMs(now)?.coerceAtLeast(0) ?: 0
        val later = phases.drop(index + 1).sumOf { (it.minutes ?: 0) * 60_000L }
        return now + here + later
    }

    fun toJson(): JSONObject = JSONObject()
        .put("itemId", itemId)
        .put(
            "phases",
            JSONArray().also { a ->
                for (p in phases) a.put(JSONObject().put("id", p.entryId).put("text", p.text).putOpt("min", p.minutes))
            },
        )
        .put("index", index).put("endsAt", endsAt).put("paused", paused).put("pausedLeftMs", pausedLeftMs)
        .put("warned", warned).put("ended", ended).put("overs", overs).put("extraMs", extraMs)
        .put("startedAt", startedAt).put("finished", finished)

    companion object {
        fun fromJson(o: JSONObject): StudyState? = runCatching {
            val a = o.getJSONArray("phases")
            val phases = (0 until a.length()).map {
                val p = a.getJSONObject(it)
                StudyPhase(p.getString("id"), p.getString("text"), if (p.has("min")) p.getInt("min") else null)
            }
            if (phases.isEmpty()) return null
            StudyState(
                itemId = o.getString("itemId"),
                phases = phases,
                index = o.getInt("index").coerceIn(0, phases.lastIndex),
                endsAt = o.getLong("endsAt"),
                paused = o.getBoolean("paused"),
                pausedLeftMs = o.getLong("pausedLeftMs"),
                warned = o.getBoolean("warned"),
                ended = o.getBoolean("ended"),
                overs = o.getInt("overs"),
                extraMs = o.getLong("extraMs"),
                startedAt = o.getLong("startedAt"),
                finished = o.getBoolean("finished"),
            )
        }.getOrNull()

        fun decode(text: String?): StudyState? = text?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }
}

/** What one change did: the new state, what to play, and which checklist lines were finished by it. */
data class StudyStep(val state: StudyState, val signals: List<StudySignal> = emptyList(), val completed: List<String> = emptyList())

/**
 * The rules of a study session as pure functions of the state and the clock, so the service, the alarm and the
 * notification buttons can all call them (in any order, or twice) and reach the same result.
 */
object StudySession {
    const val WARN_BEFORE_MS = 60_000L

    /** Steps shorter than this get no one-minute warning. */
    const val WARN_MIN_MINUTES = 10

    /** Time between "dong dong" and moving on by itself, long enough to press "+5 phút". */
    const val GRACE_MS = 5_000L
    const val EXTEND_MS = 5 * 60_000L
    const val OVER_EVERY_MS = 2 * 60_000L
    const val MAX_OVERS = 3

    fun start(itemId: String, phases: List<StudyPhase>, now: Long): StudyStep? {
        if (phases.isEmpty()) return null
        val s = StudyState(itemId, phases, startedAt = now)
        return StudyStep(enter(s, 0, now), listOf(StudySignal.START))
    }

    private fun enter(s: StudyState, index: Int, startAt: Long): StudyState {
        val minutes = s.phases[index].minutes
        return s.copy(
            index = index,
            endsAt = if (minutes != null) startAt + minutes * 60_000L else 0,
            paused = false, pausedLeftMs = 0, warned = false, ended = false, overs = 0,
        )
    }

    private fun moveOn(s: StudyState, startAt: Long): StudyStep {
        val done = listOf(s.phases[s.index].entryId)
        return if (s.last) {
            StudyStep(s.copy(finished = true, paused = false, ended = true), listOf(StudySignal.DONE), done)
        } else {
            StudyStep(enter(s, s.index + 1, startAt), listOf(StudySignal.NEXT), done)
        }
    }

    /** "Xong → bài tiếp": the current step is done now; the next one gets its full planned time. */
    fun next(s: StudyState, now: Long): StudyStep = if (s.finished) StudyStep(s) else moveOn(s, now)

    /** "+5 phút" on the current step. Once time ran out the extra time counts from now. */
    fun extend(s: StudyState, now: Long, ms: Long = EXTEND_MS): StudyStep {
        if (s.finished || !s.timed) return StudyStep(s)
        val moved = when {
            s.paused -> s.copy(pausedLeftMs = s.pausedLeftMs + ms, ended = false)
            s.ended -> s.copy(endsAt = maxOf(s.endsAt, now) + ms, ended = false)
            else -> s.copy(endsAt = s.endsAt + ms)
        }
        return StudyStep(moved.copy(warned = false, overs = 0, extraMs = s.extraMs + ms))
    }

    fun pause(s: StudyState, now: Long): StudyStep {
        if (s.finished || s.paused) return StudyStep(s)
        return StudyStep(s.copy(paused = true, pausedLeftMs = if (s.timed) (s.endsAt - now).coerceAtLeast(0) else 0))
    }

    fun resume(s: StudyState, now: Long): StudyStep {
        if (s.finished || !s.paused) return StudyStep(s)
        // A step that had already run out stays out of time: it moves on after the usual grace period.
        val endsAt = if (s.ended) now else now + s.pausedLeftMs
        return StudyStep(s.copy(paused = false, pausedLeftMs = 0, endsAt = endsAt))
    }

    /**
     * Everything the clock made due since the last call. Safe to call at any time and any number of times. When the
     * phone was away for several steps it catches up and reports only the last change, not a burst of sounds.
     */
    fun advance(start: StudyState, now: Long, auto: Boolean): StudyStep {
        var s = start
        var signals = mutableListOf<StudySignal>()
        val completed = mutableListOf<String>()
        while (!s.finished && !s.paused && s.timed) {
            val warnAt = s.endsAt - WARN_BEFORE_MS
            val canWarn = (s.phase?.minutes ?: 0) >= WARN_MIN_MINUTES
            if (!s.warned && canWarn && now >= warnAt && now < s.endsAt) {
                s = s.copy(warned = true)
                signals += StudySignal.WARN
                continue
            }
            if (!s.ended && now >= s.endsAt) {
                s = s.copy(ended = true, warned = true)
                signals += StudySignal.END
                continue
            }
            if (s.ended && auto && now >= s.endsAt + GRACE_MS) {
                val step = moveOn(s, s.endsAt + GRACE_MS)
                s = step.state
                completed += step.completed
                signals = step.signals.toMutableList() // the earlier sounds are history now
                continue
            }
            if (s.ended && !auto && s.overs < MAX_OVERS && now >= s.endsAt + (s.overs + 1) * OVER_EVERY_MS) {
                s = s.copy(overs = s.overs + 1)
                signals += StudySignal.OVER
                continue
            }
            break
        }
        return StudyStep(s, signals, completed)
    }

    /** When [advance] next has something to do, or null while paused, finished or waiting for "Xong". */
    fun nextWakeAt(s: StudyState, auto: Boolean): Long? {
        if (s.finished || s.paused || !s.timed) return null
        val canWarn = (s.phase?.minutes ?: 0) >= WARN_MIN_MINUTES
        return when {
            !s.ended && !s.warned && canWarn -> s.endsAt - WARN_BEFORE_MS
            !s.ended -> s.endsAt
            auto -> s.endsAt + GRACE_MS
            s.overs < MAX_OVERS -> s.endsAt + (s.overs + 1) * OVER_EVERY_MS
            else -> null
        }
    }
}
