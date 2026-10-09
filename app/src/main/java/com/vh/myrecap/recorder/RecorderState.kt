package com.vh.myrecap.recorder

import com.vh.myrecap.core.SessionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class RecorderUi(
    val active: Boolean = false,
    val sessionId: String? = null,
    val folderTitle: String = "",
    val mode: SessionMode = SessionMode.INTERVIEW,
    /** Someone is talking (a clip is open). */
    val speaking: Boolean = false,
    val clips: Int = 0,
    /** Time not uploaded thanks to silence filtering in this recording run. */
    val skippedMs: Long = 0,
    val autoSplit: Boolean = true,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val bookmarks: Int = 0,
    /** Set while the final segment is being written after Stop. */
    val stopping: Boolean = false,
    /** Session that just finished, so the UI can open it once. */
    val finishedSessionId: String? = null,
    val error: String? = null,
)

/** In-process bridge between [RecordingService] and the UI. */
object RecorderState {
    private val _ui = MutableStateFlow(RecorderUi())
    val ui: StateFlow<RecorderUi> = _ui

    internal fun set(transform: (RecorderUi) -> RecorderUi) = _ui.update(transform)

    fun consumeFinished() = _ui.update { it.copy(finishedSessionId = null) }

    fun clearError() = _ui.update { it.copy(error = null) }
}
