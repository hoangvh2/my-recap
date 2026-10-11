package com.vh.myrecap.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeTest {
    @Test
    fun systemFollowsThePhone() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun manualModesIgnoreThePhone() {
        for (systemDark in listOf(true, false)) {
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
        }
    }

    @Test
    fun threeModesWithVietnameseLabels() {
        assertEquals(listOf("Tự động", "Sáng", "Tối"), ThemeMode.entries.map { it.label })
    }
}
