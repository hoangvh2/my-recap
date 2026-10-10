package com.vh.myrecap.data

import com.vh.myrecap.settings.AppSettings
import java.io.File

/**
 * Keeps disk use from growing with time: audio is the only large data (~240 KB per minute), and
 * once a clip has its transcript the audio is only needed to listen back. Runs at app start.
 */
object StorageJanitor {
    private const val DAY_MS = 24 * 60 * 60_000L

    /** Returns bytes freed. */
    fun run(store: SessionStore, settings: AppSettings, cacheDir: File, now: Long = System.currentTimeMillis()): Long {
        var freed = 0L
        // Temporary m4a conversions left by a killed worker.
        cacheDir.listFiles { f -> f.name.startsWith("stt_") && f.name.endsWith(".m4a") }?.forEach {
            freed += it.length()
            it.delete()
        }
        for (session in store.list()) {
            if (session.isRecording || session.audioClips.isEmpty()) continue
            if (session.isMemo) {
                // Catch-up for captures analysed while the app was killed before cleaning up.
                if (!settings.keepMemoAudio && session.extract == TaskStatus.DONE) freed += store.deleteAudio(session.id)
                continue
            }
            if (settings.audioRetentionDays <= 0) continue
            val cutoff = now - settings.audioRetentionDays * DAY_MS
            val old = session.audioClips.filter { (if (it.recordedAt > 0) it.recordedAt else session.createdAt) < cutoff }
            if (old.isNotEmpty()) freed += store.deleteAudio(session.id, old.map { it.index })
        }
        return freed
    }
}
