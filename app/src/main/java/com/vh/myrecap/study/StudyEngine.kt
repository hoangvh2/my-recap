package com.vh.myrecap.study

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.vh.myrecap.MyRecapApp
import com.vh.myrecap.core.StudyPlan
import com.vh.myrecap.core.StudySession
import com.vh.myrecap.core.StudySignal
import com.vh.myrecap.core.StudyState
import com.vh.myrecap.core.StudyStep
import com.vh.myrecap.reminder.Reminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs the study session: applies a change to the saved state, ticks the finished lines, makes the sounds, updates
 * the notification and sets the alarm for the next moment. The service, the alarm and the buttons all come here, so a
 * session carries on (from the saved state) even if the service was killed. Call it off the main thread.
 */
object StudyEngine {
    private const val PREFS = "study_session"
    private const val KEY_STATE = "state"
    private const val ALARM_REQUEST = 0x5100

    private val _active = MutableStateFlow<StudyState?>(null)

    /** The running session, for the screens. Null when none. */
    val active: StateFlow<StudyState?> = _active.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun peek(context: Context): StudyState? {
        _active.value?.let { return it }
        val saved = StudyState.decode(prefs(context).getString(KEY_STATE, null))?.takeIf { !it.finished }
        _active.value = saved
        return saved
    }

    @Synchronized
    fun nextWakeAt(context: Context): Long? = peek(context)?.let { StudySession.nextWakeAt(it, MyRecapApp.from(context).settings.current.studyAuto) }

    /** Starts a session over the lines still to do. False when there is nothing to time or another session is running. */
    @Synchronized
    fun start(context: Context, itemId: String): Boolean {
        peek(context)?.let { return it.itemId == itemId }
        val app = MyRecapApp.from(context)
        val item = app.items.get(itemId) ?: return false
        if (!StudyPlan.canStart(item)) return false
        val now = System.currentTimeMillis()
        val step = StudySession.start(itemId, StudyPlan.phases(item), now) ?: return false
        NotificationManagerCompat.from(context).cancel(Reminders.notificationId(itemId))
        StudyNotifications.clearFinished(context)
        if (app.settings.current.studyLoud) StudyAudio.raiseVolume(context)
        apply(context, step, now)
        return true
    }

    /** One of the buttons, or the clock ([StudyService.ACTION_TICK]). Time that passed is always applied first. */
    @Synchronized
    fun act(context: Context, action: String): Long {
        val state = peek(context) ?: return 0
        val now = System.currentTimeMillis()
        val auto = MyRecapApp.from(context).settings.current.studyAuto
        val due = StudySession.advance(state, now, auto)
        val then = when (action) {
            StudyService.ACTION_NEXT -> StudySession.next(due.state, now)
            StudyService.ACTION_EXTEND -> StudySession.extend(due.state, now)
            StudyService.ACTION_PAUSE -> StudySession.pause(due.state, now)
            StudyService.ACTION_RESUME -> StudySession.resume(due.state, now)
            else -> StudyStep(due.state)
        }
        return apply(context, StudyStep(then.state, due.signals + then.signals, due.completed + then.completed), now)
    }

    /** Ends the session without finishing the remaining lines (the screen asks first). Lines already ticked stay ticked. */
    @Synchronized
    fun stop(context: Context) {
        val state = peek(context)
        clear(context)
        if (state != null) context.stopService(Intent(context, StudyService::class.java))
    }

    private fun clear(context: Context, restoreVolumeAfterMs: Long = 0) {
        prefs(context).edit().remove(KEY_STATE).apply()
        _active.value = null
        setAlarm(context, null)
        StudyNotifications.cancel(context)
        StudyAudio.restoreVolume(context, restoreVolumeAfterMs)
    }

    /** Saves [step], plays what is due and arms the next alarm. Returns how long the sound lasts. */
    private fun apply(context: Context, step: StudyStep, now: Long): Long {
        val app = MyRecapApp.from(context)
        val settings = app.settings.current
        val state = step.state
        if (step.completed.isNotEmpty()) app.items.update(state.itemId) { StudyPlan.tick(it, step.completed) }
        // Several moments at once (the phone slept) are heard as the last one only.
        val signal = step.signals.lastOrNull { it != StudySignal.WARN || settings.studyWarn }
        val lasts = signal?.let { StudyAudio.play(context, it, settings.studyLoud) } ?: 0L
        if (state.finished) {
            val title = app.items.get(state.itemId)?.title.orEmpty()
            clear(context, restoreVolumeAfterMs = lasts + 500) // not while the closing chime is still playing
            StudyNotifications.showFinished(context, state, title)
            context.stopService(Intent(context, StudyService::class.java))
            return lasts
        }
        prefs(context).edit().putString(KEY_STATE, state.toJson().toString()).apply()
        _active.value = state
        StudyNotifications.show(context, state, now)
        setAlarm(context, StudySession.nextWakeAt(state, settings.studyAuto))
        return lasts
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST,
        Intent(context, StudyReceiver::class.java).setAction(StudyService.ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The safety net: if the service is killed or the phone dozes, this alarm still brings the next sound on time. */
    private fun setAlarm(context: Context, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context)
        am.cancel(pending)
        if (at != null) Reminders.setAlarm(context, am, maxOf(at, System.currentTimeMillis() + 200), pending)
    }
}
