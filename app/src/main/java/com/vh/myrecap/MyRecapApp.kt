package com.vh.myrecap

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import com.vh.myrecap.core.Adts
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.recorder.RecorderState
import com.vh.myrecap.settings.SettingsRepository
import com.vh.myrecap.work.Processing
import java.io.File

class MyRecapApp : Application() {
    lateinit var store: SessionStore
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(File(filesDir, "sessions"))
        settings = SettingsRepository(this)
        createChannels()
        recoverInterruptedSessions()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Đang ghi âm", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Điều khiển ghi âm, hiển thị cả trên màn hình khoá"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Kết quả xử lý", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Báo khi transcript/tóm tắt đã sẵn sàng hoặc gặp lỗi"
            },
        )
    }

    /**
     * A session still marked as recording at process start means the process died mid-recording
     * (killed by the system or a vendor battery manager). Salvage the audio written so far.
     */
    private fun recoverInterruptedSessions() {
        if (RecorderState.ui.value.active) return
        for (session in store.list().filter { it.isRecording }) {
            val dir = store.dir(session.id)
            val known = session.segments.map { it.fileName }.toSet()
            var start = session.segments.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
            var nextIndex = (session.segments.maxOfOrNull { it.index } ?: -1) + 1
            val orphans = dir.listFiles { f -> f.name.endsWith(".aac") && f.name !in known }
                .orEmpty().sortedBy { it.name }
            val recovered = mutableListOf<Segment>()
            for (f in orphans) {
                val scan = Adts.repair(f)
                if (scan.frames == 0) {
                    f.delete()
                    continue
                }
                val duration = scan.durationMs(16_000)
                recovered += Segment(nextIndex++, f.name, start, duration)
                start += duration
            }
            val updated = store.update(session.id) {
                it.copy(
                    state = RecState.INTERRUPTED,
                    segments = it.segments + recovered,
                    durationMs = start,
                    error = "Ghi âm bị hệ thống dừng đột ngột. Đã giữ lại ${TimeFormat.clock(start)} âm thanh.",
                )
            }
            if (updated != null && updated.segments.isNotEmpty() && settings.current.autoProcess) {
                Processing.enqueue(this, updated.id, summarize = true)
            }
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_RESULTS = "results"

        fun from(context: Context): MyRecapApp = context.applicationContext as MyRecapApp
    }
}
