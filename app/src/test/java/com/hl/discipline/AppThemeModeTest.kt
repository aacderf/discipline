package com.hl.discipline

import org.junit.Assert.*
import org.junit.Test

class AppThemeModeTest {
    @Test fun explicitThemesOverrideSystemAndSystemModeFollowsIt() {
        for (system in listOf(false, true)) {
            assertTrue(AppThemeMode.DARK.isDark(system))
            assertFalse(AppThemeMode.LIGHT.isDark(system))
            assertEquals(system, AppThemeMode.SYSTEM.isDark(system))
        }
    }

    @Test fun olderSettingsWithoutModeUseSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue(""))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromValue("system"))
    }
}
