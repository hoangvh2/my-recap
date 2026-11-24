package com.vh.myrecap.core

/** How a script is read on the teleprompter: speed in words per minute, text size, mirrored text, a 3-2-1 before it starts. */
data class TeleprompterPrefs(
    val wpm: Int = Teleprompter.DEFAULT_WPM,
    val fontSp: Int = Teleprompter.DEFAULT_FONT_SP,
    val mirror: Boolean = false,
    val countdown: Boolean = true,
) {
    fun clean(): TeleprompterPrefs = copy(wpm = Teleprompter.clampWpm(wpm), fontSp = Teleprompter.clampFont(fontSp))
}

/**
 * Pure rules of the teleprompter. Speed is set in words per minute (what a speaker feels), then turned into scroll
 * speed from the real height of the text, so the pace stays right when the text size changes.
 */
object Teleprompter {
    const val MIN_WPM = 60
    const val MAX_WPM = 240
    const val WPM_STEP = 10
    const val DEFAULT_WPM = 130
    const val MIN_FONT_SP = 24
    const val MAX_FONT_SP = 72
    const val FONT_STEP = 4
    const val DEFAULT_FONT_SP = 36

    fun clampWpm(v: Int): Int = v.coerceIn(MIN_WPM, MAX_WPM)
    fun clampFont(v: Int): Int = v.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    /** Words are runs of non-space characters; a Vietnamese syllable counts as one, as it does when spoken. */
    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Seconds to read [words] at [wpm]. */
    fun seconds(words: Int, wpm: Int): Int = if (words <= 0) 0 else Math.round(words * 60.0 / clampWpm(wpm)).toInt()

    /** Scroll speed in pixels per second so that [totalPx] of text takes as long as reading [words] at [wpm]. */
    fun pxPerSecond(totalPx: Float, words: Int, wpm: Int): Float {
        val secs = seconds(words, wpm)
        return if (totalPx <= 0f || secs <= 0) 0f else totalPx / secs
    }

    /** "2:05" */
    fun clock(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** "khoảng 2 phút 10 giây", "45 giây", "dưới 1 giây" for the setup screen. */
    fun spoken(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s == 0 -> "chưa có nội dung"
            s < 60 -> "$s giây"
            s % 60 == 0 -> "${s / 60} phút"
            else -> "${s / 60} phút ${s % 60} giây"
        }
    }

    fun stepWpm(current: Int, up: Boolean): Int = clampWpm(current + if (up) WPM_STEP else -WPM_STEP)
    fun stepFont(current: Int, up: Boolean): Int = clampFont(current + if (up) FONT_STEP else -FONT_STEP)

    /** Scripts longer than this are cut for the screen (a note that long is not read aloud in one go). */
    const val MAX_CHARS = 60_000

    /** The text as shown: line breaks kept, runs of blank lines folded, trailing spaces removed. */
    fun prepare(text: String): String = text.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_CHARS)

    fun encode(p: TeleprompterPrefs): String = "${p.wpm},${p.fontSp},${if (p.mirror) 1 else 0},${if (p.countdown) 1 else 0}"

    fun decode(raw: String?): TeleprompterPrefs? {
        val parts = raw?.split(',') ?: return null
        if (parts.size != 4) return null
        val wpm = parts[0].toIntOrNull() ?: return null
        val font = parts[1].toIntOrNull() ?: return null
        return TeleprompterPrefs(wpm, font, parts[2] == "1", parts[3] == "1").clean()
    }
}
