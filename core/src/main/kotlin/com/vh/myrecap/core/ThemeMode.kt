package com.vh.myrecap.core

/** "Tự động" follows the phone's light/dark setting; "Sáng" and "Tối" fix it (same three modes as the web app). */
enum class ThemeMode(val label: String) {
    SYSTEM("Tự động"),
    LIGHT("Sáng"),
    DARK("Tối");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}
