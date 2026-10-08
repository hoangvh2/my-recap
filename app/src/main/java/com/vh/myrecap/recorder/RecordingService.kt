package com.vh.myrecap.recorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.TimeFormat
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.ui.MainActivity
import com.vh.myrecap.work.Processing
import java.io.File

/**
 * Foreground service (type microphone) that owns the recorder. Keeps recording with the screen
 * off; the notification exposes Pause/Resume, Bookmark and Stop, including on the lock screen.
 */
class RecordingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var recorder: ClipRecorder? = null
    private var createdFolder = false
    private var clipCount = 0
    private var sessionId: String? = null
    private var mode: SessionMode = SessionMode.INTERVIEW
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    private val app get() = MyRecapApp.from(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(
                SessionMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: SessionMode.INTERVIEW,
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                intent.getStringExtra(EXTRA_FOLDER),
            )
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_BOOKMARK -> bookmark()
            ACTION_STOP -> stopRecording()
        }
        if (recorder == null && !stopping) stopSelf()
        return START_NOT_STICKY
    }

    private fun startRecording(requestedMode: SessionMode, title: String, folderId: String?) {
        if (recorder != null) return
        val existing = folderId?.let { app.store.get(it) }
        this.mode = existing?.mode ?: requestedMode
        // Must enter the foreground first: Android requires it within seconds of startForegroundService.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(paused = false, elapsedMs = 0, bookmarks = 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException / SecurityException when not started from the UI.
            RecorderState.set { RecorderUi(error = "Không thể bắt đầu ghi âm: ${e.message}") }
            stopSelf()
            return
        }

        val settings = app.settings.current
        // Recording more into an existing folder continues its timeline and clip numbering.
        val session = existing?.let { f -> app.store.update(f.id) { it.copy(state = RecState.RECORDING, error = null) } }
            ?: app.store.create(mode, title)
        createdFolder = existing == null
        clipCount = session.segments.size
        sessionId = session.id
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRecap:recording")
            .apply { acquire(MAX_RECORDING_MS) }

        val rec = ClipRecorder(
            source = MicPcmSource(),
            outDir = app.store.dir(session.id),
            config = settings.segmenterConfig(),
            useVad = settings.usesVad,
            sensitivity = settings.vadSensitivity,
            firstIndex = session.nextSegmentIndex,
            timelineOffsetMs = session.durationMs,
            callback = RecorderCallback(session.id),
            fileName = SessionStore::segmentFileName,
        )
        recorder = rec
        RecorderState.set {
            RecorderUi(
                active = true,
                sessionId = session.id,
                folderTitle = session.title,
                mode = mode,
                elapsedMs = session.durationMs,
                bookmarks = session.bookmarksMs.size,
                clips = clipCount,
                autoSplit = settings.autoSplit,
            )
        }
        refreshNotification()
        rec.start()
    }

    private inner class RecorderCallback(private val id: String) : ClipRecorder.Callback {
        override fun onProgress(progress: RecProgress) {
            RecorderState.set {
                if (it.sessionId == id) {
                    it.copy(
                        elapsedMs = progress.elapsedMs,
                        level = progress.level,
                        speaking = progress.speaking,
                        skippedMs = progress.skippedMs,
                    )
                } else {
                    it
                }
            }
        }

        override fun onClipClosed(index: Int, file: File, startMs: Long, endMs: Long, durationMs: Long, recordedAt: Long) {
            app.store.update(id) {
                it.copy(
                    segments = it.segments + Segment(index, file.name, startMs, durationMs, endMs = endMs, recordedAt = recordedAt),
                    durationMs = maxOf(it.durationMs, endMs),
                )
            }
            clipCount++
            RecorderState.set { if (it.sessionId == id) it.copy(clips = clipCount) else it }
            main.post { refreshNotification() }
            val s = app.settings.current
            if (s.autoProcess && s.processWhileRecording && !stopping) Processing.enqueue(this@RecordingService, id)
        }

        override fun onError(error: Throwable) {
            main.post {
                if (stopping) return@post
                Haptics.error(this@RecordingService)
                RecorderState.set { it.copy(error = "Ghi âm dừng do lỗi: ${error.message ?: error.javaClass.simpleName}") }
                finish(RecState.INTERRUPTED, "Ghi âm dừng do lỗi: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun togglePause() {
        val rec = recorder ?: return
        if (stopping) return
        rec.paused = !rec.paused
        val id = sessionId ?: return
        app.store.update(id) { it.copy(state = if (rec.paused) RecState.PAUSED else RecState.RECORDING) }
        RecorderState.set { it.copy(paused = rec.paused) }
        if (rec.paused) Haptics.paused(this) else Haptics.resumed(this)
        refreshNotification()
    }

    private fun bookmark() {
        val rec = recorder ?: return
        val id = sessionId ?: return
        if (stopping) return
        val at = rec.recordedMs
        val updated = app.store.update(id) { it.copy(bookmarksMs = it.bookmarksMs + at) }
        RecorderState.set { it.copy(bookmarks = updated?.bookmarksMs?.size ?: it.bookmarks + 1) }
        Haptics.bookmark(this)
        refreshNotification()
    }

    private fun stopRecording() {
        if (recorder == null || stopping) return
        Haptics.stopped(this)
        finish(RecState.STOPPED, null)
    }

    /** Stops the recorder off the main thread (it joins the encoder thread), then tears down. */
    private fun finish(finalState: RecState, error: String?) {
        if (stopping) return
        stopping = true
        val rec = recorder
        val id = sessionId
        RecorderState.set { it.copy(stopping = true) }
        Thread {
            rec?.stop()
            main.post {
                recorder = null
                if (id != null) {
                    val session = app.store.update(id) { s ->
                        s.copy(
                            state = finalState,
                            durationMs = maxOf(s.durationMs, rec?.recordedMs ?: 0L),
                            error = error ?: s.error,
                        )
                    }
                    if (session != null && session.segments.isEmpty() && createdFolder) {
                        app.store.delete(id) // Nothing recorded (stopped immediately, silence only, or mic failed).
                    } else if (session != null && app.settings.current.autoProcess) {
                        Processing.enqueue(this, id)
                    }
                }
                RecorderState.set {
                    RecorderUi(finishedSessionId = if (finalState == RecState.STOPPED) id else null, error = it.error)
                }
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        // Destroyed without Stop (e.g. app task removed on some ROMs): keep what was recorded.
        recorder?.let { rec ->
            rec.stop()
            sessionId?.let { id ->
                app.store.update(id) { s ->
                    s.copy(state = RecState.STOPPED, durationMs = maxOf(s.durationMs, rec.recordedMs))
                }
                if (app.settings.current.autoProcess) Processing.enqueue(this, id)
            }
            RecorderState.set { RecorderUi() }
        }
        recorder = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // SecurityException handled below.
    private fun refreshNotification() {
        val rec = recorder ?: return
        val ui = RecorderState.ui.value
        val n = buildNotification(rec.paused, rec.recordedMs, ui.bookmarks)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // Notification permission denied: recording continues, controls stay in the app.
        }
    }

    private fun buildNotification(paused: Boolean, elapsedMs: Long, bookmarks: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (paused) "⏸ Đã tạm dừng · ${TimeFormat.clock(elapsedMs)}" else "● Đang ghi âm"
        val text = "${mode.label} · $clipCount đoạn · ⭐ $bookmarks"
        return NotificationCompat.Builder(this, MyRecapApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            // Plain short labels: the system truncates action text on narrow screens.
            .addAction(0, if (paused) "Tiếp tục" else "Tạm dừng", commandIntent(ACTION_TOGGLE_PAUSE, 1))
            .addAction(0, "Đánh dấu", commandIntent(ACTION_BOOKMARK, 2))
            .addAction(0, "Dừng", commandIntent(ACTION_STOP, 3))
            .build()
    }

    private fun commandIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RECORDING_MS = 6 * 60 * 60 * 1000L

        const val ACTION_START = "com.vh.myrecap.START"
        const val ACTION_TOGGLE_PAUSE = "com.vh.myrecap.TOGGLE_PAUSE"
        const val ACTION_BOOKMARK = "com.vh.myrecap.BOOKMARK"
        const val ACTION_STOP = "com.vh.myrecap.STOP"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FOLDER = "folder"

        /**
         * Starts recording into a new folder, or into [folderId] to continue an existing one.
         * Must be called while the app is in the foreground (Android 14+ microphone FGS rule).
         */
        fun start(context: Context, mode: SessionMode, title: String, folderId: String? = null) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FOLDER, folderId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun command(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}
