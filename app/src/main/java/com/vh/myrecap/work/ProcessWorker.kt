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
import com.vh.myrecap.core.ClipNotes
import com.vh.myrecap.core.ClipText
import com.vh.myrecap.core.Prompts
import com.vh.myrecap.core.ProviderKind
import com.vh.myrecap.core.Providers
import com.vh.myrecap.core.SessionMode
import com.vh.myrecap.core.SttRequest
import com.vh.myrecap.core.SummaryComposer
import com.vh.myrecap.data.Segment
import com.vh.myrecap.data.Session
import com.vh.myrecap.data.SessionStore
import com.vh.myrecap.data.SummaryJob
import com.vh.myrecap.data.TaskStatus
import com.vh.myrecap.recorder.AudioFiles
import com.vh.myrecap.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object Processing {
    /**
     * Queues background work for a folder: speech-to-text for clips without text, then any
     * summaries the user requested. Work for one folder runs sequentially (unique chain), so
     * clips are transcribed in order and each gets the previous one's context.
     */
    fun enqueue(context: Context, sessionId: String) {
        val settings = MyRecapApp.from(context).settings.current
        val request = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(ProcessWorker.KEY_ID to sessionId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(sessionId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Re-runs speech-to-text for one clip, or for every failed clip when [index] is null. */
    fun retryStt(context: Context, sessionId: String, index: Int? = null) {
        MyRecapApp.from(context).store.update(sessionId) { s ->
            s.copy(
                segments = s.segments.map {
                    val again = if (index == null) it.stt == TaskStatus.ERROR else it.index == index
                    if (again) it.copy(stt = TaskStatus.PENDING, error = null) else it
                },
                error = null,
            )
        }
        enqueue(context, sessionId)
    }

    /** Creates a summary over the chosen clips and queues it. */
    fun requestSummary(context: Context, sessionId: String, clipIndexes: List<Int>, mode: SessionMode) {
        val job = SummaryJob(
            id = SessionStore.newJobId(),
            createdAt = System.currentTimeMillis(),
            mode = mode,
            clipIndexes = clipIndexes.sorted(),
            status = TaskStatus.PENDING,
        )
        MyRecapApp.from(context).store.update(sessionId) { it.copy(summaries = it.summaries + job, error = null) }
        enqueue(context, sessionId)
    }

    fun retrySummary(context: Context, sessionId: String, jobId: String) {
        MyRecapApp.from(context).store.update(sessionId) { s ->
            s.copy(summaries = s.summaries.map { if (it.id == jobId) it.copy(status = TaskStatus.PENDING, error = null) else it })
        }
        enqueue(context, sessionId)
    }

    fun cancel(context: Context, sessionId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(sessionId))
    }

    private fun workName(id: String) = "process-$id"
}

class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = MyRecapApp.from(context)
    private val store = app.store

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val session = store.get(id) ?: return@withContext Result.success() // deleted meanwhile
        val settings = app.settings.current
        val deadline = SystemClock.elapsedRealtime() + TIME_BUDGET_MS

        // 1) Speech-to-text for every clip without text, in order. RUNNING means a killed earlier run.
        val pending = session.segments.sortedBy { it.index }
            .filter { it.stt == TaskStatus.PENDING || it.stt == TaskStatus.RUNNING }
        if (pending.isNotEmpty()) {
            val config = settings.sttConfig()
            if (!config.isComplete) {
                fail(id, "Chưa cấu hình dịch vụ chuyển giọng nói (API key/model) trong Cài đặt.")
                return@withContext Result.failure()
            }
            val stt = Providers.speechToText(config, settings.whisperLanguage.trim())
            for (segment in pending) {
                if (SystemClock.elapsedRealtime() > deadline) return@withContext continueLater(id)
                val current = store.get(id) ?: return@withContext Result.success()
                if (current.segments.none { it.index == segment.index }) continue // clip deleted
                setSegment(id, segment.index) { it.copy(stt = TaskStatus.RUNNING, error = null) }
                try {
                    val clip = loadClip(id, segment, config.kind)
                    val previous = current.segments.filter { it.index < segment.index }.maxByOrNull { it.index }
                        ?.let { store.readTranscript(id, it.index) }.orEmpty()
                    val raw = stt.transcribe(
                        SttRequest(clip, session.mode, previous, settings.interviewerSpeech, settings.outputLanguage),
                    )
                    val parsed = Prompts.parseClipTranscript(raw)
                    store.writeTranscript(id, segment.index, parsed.text)
                    val title = parsed.title ?: fallbackTitle(parsed.text)
                    setSegment(id, segment.index) { it.copy(stt = TaskStatus.DONE, error = null, title = title) }
                } catch (e: ApiException) {
                    return@withContext handleApiError(id, e) { status ->
                        setSegment(id, segment.index) { it.copy(stt = status, error = e.message) }
                    }
                } catch (e: Exception) {
                    setSegment(id, segment.index) { it.copy(stt = TaskStatus.ERROR, error = e.message) }
                    fail(id, "Đoạn ${segment.number}: ${e.message ?: e.javaClass.simpleName}")
                    return@withContext Result.failure()
                }
            }
            store.update(id) { if (it.error?.startsWith(RETRY_PREFIX) == true) it.copy(error = null) else it }
        }

        // 2) Summaries the user asked for.
        val jobs = store.get(id)?.summaries?.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING }.orEmpty()
        for (job in jobs) {
            if (SystemClock.elapsedRealtime() > deadline) return@withContext continueLater(id)
            val current = store.get(id) ?: return@withContext Result.success()
            val config = settings.summaryConfig()
            if (!config.isComplete) {
                setJob(id, job.id) { it.copy(status = TaskStatus.ERROR, error = "Chưa cấu hình AI tóm tắt trong Cài đặt.") }
                continue
            }
            val clips = current.segments.filter { it.index in job.clipIndexes }
            if (clips.any { it.stt != TaskStatus.DONE }) {
                // Should not happen (UI only offers transcribed clips); wait for STT instead of summarising gaps.
                setJob(id, job.id) { it.copy(status = TaskStatus.ERROR, error = "Có đoạn chưa có transcript.") }
                continue
            }
            setJob(id, job.id) { it.copy(status = TaskStatus.RUNNING, error = null) }
            try {
                val generator = Providers.textGenerator(config)
                val clipTexts = clips.sortedBy { it.index }
                    .map { ClipText(it.number, it.startMs, it.endMs, it.title, store.readTranscript(id, it.index)) }
                    .filter { ClipNotes.hasSpeech(it) }
                val byNumber = clips.associateBy { it.number }
                val lang = settings.outputLanguage

                // Stage 1: notes for every clip, a few clips per request, cached per clip.
                val notes = mutableMapOf<Int, String>()
                clipTexts.forEach { c -> store.readNotes(id, byNumber.getValue(c.number).index, job.mode, lang)?.let { notes[c.number] = it } }
                for (batch in ClipNotes.batches(clipTexts.filter { it.number !in notes })) {
                    if (SystemClock.elapsedRealtime() > deadline) return@withContext continueLater(id)
                    setJob(id, job.id) { it.copy(progress = "Đang ghi chú ${notes.size}/${clipTexts.size} đoạn…") }
                    val parsed = ClipNotes.parse(
                        generator.generate(ClipNotes.system(lang), ClipNotes.userMessage(job.mode, settings.interviewerSpeech, batch)),
                        batch.map { it.number },
                    ).toMutableMap()
                    // A clip the model skipped gets its own request; if that fails too, keep the transcript words.
                    for (c in batch.filter { it.number !in parsed }) {
                        val single = if (batch.size > 1) {
                            ClipNotes.parse(
                                generator.generate(ClipNotes.system(lang), ClipNotes.userMessage(job.mode, settings.interviewerSpeech, listOf(c))),
                                listOf(c.number),
                            )[c.number]
                        } else {
                            null
                        }
                        parsed[c.number] = single ?: ClipNotes.fallback(c)
                    }
                    for (c in batch) {
                        val note = parsed.getValue(c.number)
                        store.writeNotes(id, byNumber.getValue(c.number).index, job.mode, lang, note)
                        notes[c.number] = note
                    }
                }

                // Stage 2: the model writes the evaluation; the app appends every clip's notes.
                setJob(id, job.id) { it.copy(progress = "Đang viết tóm tắt từ ${clipTexts.size} đoạn…") }
                val ordered = clipTexts.map { it to notes.getValue(it.number) }
                val synthesis = generator.generate(
                    SummaryComposer.system(lang),
                    SummaryComposer.synthesisMessage(
                        mode = job.mode,
                        customPrompt = settings.customPrompt,
                        title = current.title,
                        durationMs = clips.sumOf { it.endMs - it.startMs },
                        bookmarksMs = current.bookmarksMs,
                        notes = ordered,
                    ),
                )
                store.writeSummary(id, job.id, SummaryComposer.compose(job.mode, synthesis, ordered))
                setJob(id, job.id) { it.copy(status = TaskStatus.DONE, error = null, progress = null) }
                notifyResult(current, "Đã có tóm tắt", "${clips.size} đoạn · chạm để xem và chia sẻ")
            } catch (e: ApiException) {
                return@withContext handleApiError(id, e) { status ->
                    setJob(id, job.id) { it.copy(status = status, error = e.message) }
                }
            }
        }
        Result.success()
    }

    /** WorkManager caps one run at 10 minutes; continue in a follow-up run of the same chain. */
    private fun continueLater(id: String): Result {
        Processing.enqueue(applicationContext, id)
        return Result.success()
    }

    /** Retries transient errors with backoff; surfaces permanent ones to the user. */
    private fun handleApiError(id: String, e: ApiException, mark: (TaskStatus) -> Unit): Result {
        val giveUp = !e.retryable || runAttemptCount >= MAX_ATTEMPTS
        mark(if (giveUp) TaskStatus.ERROR else TaskStatus.PENDING)
        if (giveUp) {
            fail(id, e.message ?: "Lỗi dịch vụ")
            return Result.failure()
        }
        store.update(id) { it.copy(error = "$RETRY_PREFIX ${e.message}") }
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

    private fun setSegment(id: String, index: Int, change: (Segment) -> Segment) {
        store.update(id) { s -> s.copy(segments = s.segments.map { if (it.index == index) change(it) else it }) }
    }

    private fun setJob(id: String, jobId: String, change: (SummaryJob) -> SummaryJob) {
        store.update(id) { s -> s.copy(summaries = s.summaries.map { if (it.id == jobId) change(it) else it }) }
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
        const val RETRY_PREFIX = "Đang thử lại:"
        private const val MAX_ATTEMPTS = 6
        private const val TIME_BUDGET_MS = 8 * 60_000L

        /** Title for providers that do not write one (Whisper): the first words of the text. */
        fun fallbackTitle(text: String): String? {
            if (text == Prompts.NO_SPEECH || text == Prompts.INTERVIEWER_ONLY) return null
            val words = text.substringAfter(':', text).trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) return null
            return words.take(8).joinToString(" ") + if (words.size > 8) "…" else ""
        }
    }
}
