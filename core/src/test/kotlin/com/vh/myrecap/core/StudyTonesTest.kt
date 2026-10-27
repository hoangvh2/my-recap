package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StudyTonesTest {
    @Test
    fun everySignalReachesTheSameLoudPeakWithoutClipping() {
        for (signal in StudySignal.entries) {
            val peak = StudyTones.render(signal).maxOf { abs(it.toInt()) } / Short.MAX_VALUE.toDouble()
            assertEquals("$signal peak", StudyTones.PEAK, peak, 0.01)
        }
    }

    @Test
    fun signalsAreShortAndEndInSilenceSoThereIsNoClick() {
        for (signal in StudySignal.entries) {
            val s = StudyTones.render(signal)
            assertTrue("$signal is under 3 s", s.size < StudyTones.SAMPLE_RATE * 3)
            assertTrue("$signal ends quietly", abs(s.last().toInt()) < 400)
        }
    }

    @Test
    fun everySignalFeelsDifferentWithTheSoundOff() {
        val patterns = StudySignal.entries.map { StudyTones.vibration(it).toList() }
        assertEquals(patterns.size, patterns.toSet().size)
    }
}
