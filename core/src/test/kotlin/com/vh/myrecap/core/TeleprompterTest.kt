package com.vh.myrecap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeleprompterTest {
    @Test
    fun countsWordsAcrossLinesAndIgnoresBlanks() {
        assertEquals(0, Teleprompter.wordCount("  \n "))
        assertEquals(6, Teleprompter.wordCount("Xin chào các   bạn\n\nhôm nay"))
    }

    @Test
    fun readingTimeFollowsWordsPerMinute() {
        assertEquals(60, Teleprompter.seconds(130, 130))
        assertEquals(33, Teleprompter.seconds(130, 260)) // 260 is clamped to 240: 32.5 s
        assertEquals(0, Teleprompter.seconds(0, 130))
    }

    @Test
    fun scrollSpeedKeepsThePaceWhateverTheTextSize() {
        // 260 words at 130 wpm is 2 minutes: 2400 px of text moves at 20 px/s, 4800 px at 40 px/s.
        assertEquals(20f, Teleprompter.pxPerSecond(2400f, 260, 130), 0.001f)
        assertEquals(40f, Teleprompter.pxPerSecond(4800f, 260, 130), 0.001f)
        assertEquals(0f, Teleprompter.pxPerSecond(0f, 260, 130), 0f)
        assertEquals(0f, Teleprompter.pxPerSecond(100f, 0, 130), 0f)
    }

    @Test
    fun speedAndSizeStayInRangeWhenStepped() {
        assertEquals(240, Teleprompter.stepWpm(240, up = true))
        assertEquals(60, Teleprompter.stepWpm(60, up = false))
        assertEquals(140, Teleprompter.stepWpm(130, up = true))
        assertEquals(72, Teleprompter.stepFont(72, up = true))
        assertEquals(24, Teleprompter.stepFont(24, up = false))
        assertEquals(40, Teleprompter.stepFont(36, up = true))
    }

    @Test
    fun formatsTimeForTheScreenAndForWords() {
        assertEquals("2:05", Teleprompter.clock(125))
        assertEquals("0:09", Teleprompter.clock(9))
        assertEquals("0:00", Teleprompter.clock(-3))
        assertEquals("2 phút 10 giây", Teleprompter.spoken(130))
        assertEquals("3 phút", Teleprompter.spoken(180))
        assertEquals("45 giây", Teleprompter.spoken(45))
        assertEquals("chưa có nội dung", Teleprompter.spoken(0))
    }

    @Test
    fun preparesTheScriptKeepingLineBreaksAndFoldingBlankRuns() {
        assertEquals("Một\n\nHai", Teleprompter.prepare("Một  \r\n\r\n\r\n\r\nHai  \n"))
        assertTrue(Teleprompter.prepare("a".repeat(70_000)).length == Teleprompter.MAX_CHARS)
    }

    @Test
    fun savedPrefsRoundTripAndBadOnesAreIgnoredOrClamped() {
        val p = TeleprompterPrefs(wpm = 150, fontSp = 48, mirror = true, countdown = false)
        assertEquals(p, Teleprompter.decode(Teleprompter.encode(p)))
        assertNull(Teleprompter.decode(null))
        assertNull(Teleprompter.decode("1,2"))
        assertNull(Teleprompter.decode("x,36,0,1"))
        assertEquals(TeleprompterPrefs(wpm = 240, fontSp = 24, mirror = false, countdown = true), Teleprompter.decode("999,1,0,1"))
    }
}
