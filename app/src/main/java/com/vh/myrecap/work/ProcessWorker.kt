package com.vh.myrecap.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.R
import com.vh.myrecap.core.ApiException
import com.vh.myrecap.core.AudioClip
import com.vh.myrecap.core.Prompts
import com.vh.myrecap.core.ProviderKind
import com.vh.myrecap.core.Providers
import com.vh.myrecap.core.SttRequest
import com.vh.myrecap.data.RecState
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.AudioFiles
import com.vh.myrecap.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object Processing {
    /**
     * Queues background processing for a session. Work for one session runs sequentially (unique
     * chain), so segments are transcribed in order and each one gets the previous one's context.
     */
    fun enqueue(context: Context, sessionId: String, summarize: Boolean) {
        val app = MyRecapApp.from(context)
        val settings = app.settings.current
        if (summarize && settings.summaryEnabled) {
            app.store.update(sessionId) {
                if (it.summary == TaskStatus.DONE) it else it.copy(summary = TaskStatus.PENDING)
            }
        }
        val request = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(ProcessWorker.KEY_ID to sessionId, ProcessWorker.KEY_SUMMARIZE to summarize))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(sessionId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Clears errors and queues everything that is not done yet, including the summary. */
    fun retry(context: Context, sessionId: String, resummarize: Boolean) {
        val store = MyRecapApp.from(context).store
        store.update(sessionId) { s ->
            s.copy(
                segments = s.segments.map { if (it.stt == TaskStatus.ERROR) it.copy(stt = TaskStatus.PENDING, error = null) else it },
                summary = if (resummarize || s.summary == TaskStatus.ERROR) TaskStatus.PENDING else s.summary,
                error = null,
            )
        }
        if (resummarize) store.summaryFile(sessionId).delete()
        enqueue(context, sessionId, summarize = true)
    }

    fun cancel(context: Context, sessionId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(sessionId))
    }

    private fun workName(id: String) = "process-$id"
    private const val TAG = "process"
}

class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = MyRecapApp.from(context)
    private val store = app.store

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val summarize = inputData.getBoolean(KEY_SUMMARIZE, false)
        val session = store.get(id) ?: return@withContext Result.success() // deleted meanwhile
        val settings = app.settings.current
        val deadline = SystemClock.elapsedRealtime() + TIME_BUDGET_MS

        // 1) Speech-to-text for every finished segment, in order.
        val pending = session.segments.sortedBy { it.index }.filter { it.stt != TaskStatus.DONE }
        if (pending.isNotEmpty()) {
            val config = settings.sttConfig()
            if (!config.isComplete) {
                fail(id, "Chưa cấu hình dịch vụ chuyển giọng nói (API key/model) trong Cài đặt.")
                return@withContext Result.failure()
            }
            val stt = Providers.speechToText(config, settings.whisperLanguage.trim())
            for (segment in pending) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    // WorkManager caps one run at 10 minutes; continue in a follow-up run.
                    Processing.enqueue(applicationContext, id, summarize)
                    return@withContext Result.success()
                }
                if (store.get(id) == null) return@withContext Result.success()
                setSegment(id, segment.index, TaskStatus.RUNNING, null)
                try {
                    val clip = loadClip(id, segment, config.kind)
                    val previous = store.readTranscript(id, segment.index - 1).orEmpty()
                    val text = stt.transcribe(SttRequest(clip, session.mode, previous))
                    store.writeTranscript(id, segment.index, text)
                    setSegment(id, segment.index, TaskStatus.DONE, null)
                } catch (e: ApiException) {
                    return@withContext handleApiError(id, e) { setSegment(id, segment.index, it, e.message) }
                } catch (e: Exception) {
                    setSegment(id, segment.index, TaskStatus.ERROR, e.message)
                    fail(id, "Đoạn ${segment.index + 1}: ${e.message ?: e.javaClass.simpleName}")
                    return@withContext Result.failure()
                }
            }
        }

        // 2) Summary, once recording has ended and every segment has text.
        val current = store.get(id) ?: return@withContext Result.success()
        val recordingDone = current.state == RecState.STOPPED || current.state == RecState.INTERRUPTED
        // RUNNING here means a previous run was killed mid-request; runs for a session never overlap.
        val wantsSummary = summarize && settings.summaryEnabled &&
            (current.summary == TaskStatus.PENDING || current.summary == TaskStatus.RUNNING)
        if (recordingDone && current.allTranscribed && wantsSummary) {
            val config = settings.summaryConfig()
            if (!config.isComplete) {
                store.update(id) { it.copy(summary = TaskStatus.ERROR, error = "Chưa cấu hình dịch vụ AI tóm tắt trong Cài đặt.") }
                return@withContext Result.failure()
            }
            store.update(id) { it.copy(summary = TaskStatus.RUNNING) }
            try {
                val message = Prompts.summaryUserMessage(
                    mode = current.mode,
                    customPrompt = settings.customPrompt,
                    title = current.title,
                    durationMs = current.durationMs,
                    bookmarksMs = current.bookmarksMs,
                    transcript = store.fullTranscript(current),
                )
                val summary = Providers.textGenerator(config).generate(Prompts.summarySystem(settings.outputLanguage), message)
                store.writeSummary(id, summary)
                store.update(id) { it.copy(summary = TaskStatus.DONE, error = null) }
                notifyResult(current, "Đã có tóm tắt", "Chạm để xem và chia sẻ")
            } catch (e: ApiException) {
                return@withContext handleApiError(id, e) { status ->
                    store.update(id) { it.copy(summary = status) }
                }
            }
        } else if (recordingDone && current.allTranscribed && summarize && !settings.summaryEnabled) {
            notifyResult(current, "Đã có transcript", "Chạm để xem và chia sẻ")
        }
        Result.success()
    }

    /** Retries transient errors with backoff; surfaces permanent ones to the user. */
    private fun handleApiError(id: String, e: ApiException, mark: (TaskStatus) -> Unit): Result {
        val giveUp = !e.retryable || runAttemptCount >= MAX_ATTEMPTS
        mark(if (giveUp) TaskStatus.ERROR else TaskStatus.PENDING)
        if (giveUp) {
            fail(id, e.message ?: "Lỗi dịch vụ")
            return Result.failure()
        }
        store.update(id) { it.copy(error = "Đang thử lại: ${e.message}") }
        return Result.retry()
    }

    private fun loadClip(id: String, segment: Segment, kind: ProviderKind): AudioClip {
        val audio = store.audioFile(id, segment)
        return when (kind) {
            ProviderKind.GEMINI -> AudioClip(audio.readBytes(), "audio/aac", audio.name)
            ProviderKind.OPENAI_COMPATIBLE -> {
                val m4a = File(applicationContext.cacheDir, "stt_${id}_${segment.index}.m4a")
                try {
                    AudioFiles.adtsToM4a(audio, m4a)
                    AudioClip(m4a.readBytes(), "audio/mp4", "segment_${segment.index}.m4a")
                } finally {
                    m4a.delete()
                }
            }
        }
    }

    private fun setSegment(id: String, index: Int, status: TaskStatus, error: String?) {
        store.update(id) { s ->
            s.copy(segments = s.segments.map { if (it.index == index) it.copy(stt = status, error = error) else it })
        }
    }

    private fun fail(id: String, message: String) {
        val session = store.update(id) { it.copy(error = message) } ?: return
        notifyResult(session, "Xử lý chưa xong", message)
    }

    @SuppressLint("MissingPermission") // SecurityException handled below.
    private fun notifyResult(session: Session, title: String, text: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SESSION_ID, session.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            applicationContext,
            session.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, MyRecapApp.CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("$title · ${session.title}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(session.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notifications disabled; the result is still visible in the app.
        }
    }

    companion object {
        const val KEY_ID = "sessionId"
        const val KEY_SUMMARIZE = "summarize"
        private const val MAX_ATTEMPTS = 6
        private const val TIME_BUDGET_MS = 8 * 60_000L
    }
}
