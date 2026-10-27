package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySessionTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000L
    private val plan = listOf(
        StudyPhase("a", "10 phút: Anki", 10),
        StudyPhase("b", "20 phút: Voca", 20),
        StudyPhase("c", "15 phút: nghe lại", 15),
        StudyPhase("d", "5 phút: ghi lỗi", 5),
    )

    private fun started(phases: List<StudyPhase> = plan) = StudySession.start("item", phases, t0)!!.state

    @Test
    fun startsOnTheFirstStepWithItsFullTime() {
        val step = StudySession.start("item", plan, t0)!!
        assertEquals(listOf(StudySignal.START), step.signals)
        assertEquals(t0 + 10 * min, step.state.endsAt)
        assertNull(StudySession.start("item", emptyList(), t0))
    }

    @Test
    fun warnsOneMinuteBeforeTheEndOnlyOnceAndNotOnShortSteps() {
        val s = started()
        assertEquals(emptyList<StudySignal>(), StudySession.advance(s, t0 + 8 * min, auto = true).signals)
        val warned = StudySession.advance(s, t0 + 9 * min, auto = true)
        assertEquals(listOf(StudySignal.WARN), warned.signals)
        assertEquals("a second call must not repeat it", emptyList<StudySignal>(), StudySession.advance(warned.state, t0 + 9 * min + 5_000, auto = true).signals)
        val short = started(listOf(StudyPhase("d", "5 phút", 5)))
        assertEquals("a 5 minute step has no warning", emptyList<StudySignal>(), StudySession.advance(short, t0 + 4 * min + 30_000, auto = true).signals)
    }

    @Test
    fun endsThenMovesOnByItselfAfterTheGracePeriodAndTicksTheFinishedLine() {
        val ended = StudySession.advance(started(), t0 + 10 * min, auto = true)
        assertEquals(listOf(StudySignal.END), ended.signals)
        assertEquals(0, ended.state.index)
        val moved = StudySession.advance(ended.state, t0 + 10 * min + StudySession.GRACE_MS, auto = true)
        assertEquals(listOf(StudySignal.NEXT), moved.signals)
        assertEquals(listOf("a"), moved.completed)
        assertEquals(1, moved.state.index)
        assertEquals("the next step starts when the last one ended, so the plan does not drift", t0 + 10 * min + StudySession.GRACE_MS + 20 * min, moved.state.endsAt)
    }

    @Test
    fun withoutAutoAdvanceItStaysAndRemindsAFewTimes() {
        var s = StudySession.advance(started(), t0 + 10 * min, auto = false).state
        assertEquals("nothing moves by itself", 0, StudySession.advance(s, t0 + 10 * min + 10_000, auto = false).state.index)
        val heard = mutableListOf<StudySignal>()
        for (k in 1..6) {
            val step = StudySession.advance(s, t0 + 10 * min + k * StudySession.OVER_EVERY_MS, auto = false)
            heard += step.signals
            s = step.state
        }
        assertEquals(List(StudySession.MAX_OVERS) { StudySignal.OVER }, heard)
        assertNull(StudySession.nextWakeAt(s, auto = false))
    }

    @Test
    fun finishingEarlyStartsTheNextStepWithItsFullPlannedTime() {
        val step = StudySession.next(started(), t0 + 4 * min)
        assertEquals(listOf(StudySignal.NEXT), step.signals)
        assertEquals(listOf("a"), step.completed)
        assertEquals(t0 + 4 * min + 20 * min, step.state.endsAt)
        assertFalse(step.state.warned)
    }

    @Test
    fun runningLateAddsFiveMinutesAndTheWarningComesAgain() {
        val s = StudySession.advance(started(), t0 + 9 * min, auto = true).state // warned
        val more = StudySession.extend(s, t0 + 9 * min + 30_000).state
        assertEquals(t0 + 15 * min, more.endsAt)
        assertEquals(5 * min, more.extraMs)
        assertEquals(listOf(StudySignal.WARN), StudySession.advance(more, t0 + 14 * min, auto = true).signals)
    }

    @Test
    fun extendingAfterTheEndGivesFiveMinutesFromNowAndCancelsTheMoveOn() {
        val ended = StudySession.advance(started(), t0 + 10 * min, auto = true).state
        val more = StudySession.extend(ended, t0 + 10 * min + 2_000).state
        assertEquals(t0 + 10 * min + 2_000 + 5 * min, more.endsAt)
        assertFalse(more.ended)
        assertEquals(0, StudySession.advance(more, t0 + 10 * min + 10_000, auto = true).state.index)
    }

    @Test
    fun pausingFreezesTheTimeLeftAndResumingContinuesFromIt() {
        val p = StudySession.pause(started(), t0 + 4 * min).state
        assertTrue(p.paused)
        assertEquals(6 * min, p.remainingMs(t0 + 30 * min))
        assertEquals("a paused session makes no sound", emptyList<StudySignal>(), StudySession.advance(p, t0 + 60 * min, auto = true).signals)
        assertNull(StudySession.nextWakeAt(p, auto = true))
        val r = StudySession.resume(p, t0 + 30 * min).state
        assertEquals(t0 + 36 * min, r.endsAt)
        assertEquals(6 * min, r.remainingMs(t0 + 30 * min))
    }

    @Test
    fun theLastStepFinishesTheSession() {
        val one = started(listOf(StudyPhase("d", "5 phút", 5)))
        val ended = StudySession.advance(one, t0 + 5 * min, auto = true)
        val done = StudySession.advance(ended.state, t0 + 5 * min + StudySession.GRACE_MS, auto = true)
        assertEquals(listOf(StudySignal.DONE), done.signals)
        assertEquals(listOf("d"), done.completed)
        assertTrue(done.state.finished)
        assertEquals(emptyList<StudySignal>(), StudySession.advance(done.state, t0 + 99 * min, auto = true).signals)
        assertEquals("early finish of the last step", listOf(StudySignal.DONE), StudySession.next(one, t0 + 60_000).signals)
    }

    @Test
    fun aPhoneAsleepForSeveralStepsCatchesUpWithOneSoundAndTicksEveryFinishedLine() {
        val step = StudySession.advance(started(), t0 + 40 * min, auto = true) // 10 + 20 are over, 15 min step is running
        assertEquals(2, step.state.index)
        assertEquals(listOf("a", "b"), step.completed)
        assertEquals(listOf(StudySignal.NEXT), step.signals)
    }

    @Test
    fun aStepWithoutATimeWaitsForDone() {
        val s = started(listOf(StudyPhase("x", "Ghi chú", null), StudyPhase("y", "5 phút", 5)))
        assertNull(s.remainingMs(t0))
        assertEquals(emptyList<StudySignal>(), StudySession.advance(s, t0 + 999 * min, auto = true).signals)
        assertNull(StudySession.nextWakeAt(s, auto = true))
        val next = StudySession.next(s, t0 + 3 * min)
        assertEquals(t0 + 8 * min, next.state.endsAt)
    }

    @Test
    fun nextWakeIsTheWarningThenTheEndThenTheMoveOn() {
        val s = started()
        assertEquals(t0 + 9 * min, StudySession.nextWakeAt(s, true))
        val warned = StudySession.advance(s, t0 + 9 * min, true).state
        assertEquals(t0 + 10 * min, StudySession.nextWakeAt(warned, true))
        val ended = StudySession.advance(warned, t0 + 10 * min, true).state
        assertEquals(t0 + 10 * min + StudySession.GRACE_MS, StudySession.nextWakeAt(ended, true))
        assertEquals(t0 + 10 * min + StudySession.OVER_EVERY_MS, StudySession.nextWakeAt(ended, false))
    }

    @Test
    fun expectedEndCountsWhatIsLeftAndTheLaterSteps() {
        val s = started()
        assertEquals(t0 + 50 * min, s.expectedEndAt(t0))
        val late = StudySession.extend(s, t0).state
        assertEquals(t0 + 55 * min, late.expectedEndAt(t0))
    }

    @Test
    fun stateSurvivesBeingSavedAndRestored() {
        val s = StudySession.advance(StudySession.pause(started(), t0 + 3 * min).state, t0 + 4 * min, true).state
        val back = StudyState.decode(s.toJson().toString())
        assertNotNull(back)
        assertEquals(s, back)
        val untimed = StudyState("i", listOf(StudyPhase("x", "Ghi chú", null)))
        assertEquals(untimed, StudyState.decode(untimed.toJson().toString()))
        assertNull(StudyState.decode("not json"))
        assertNull(StudyState.decode(null))
    }
}
