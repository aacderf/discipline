package com.hl.discipline

internal enum class AppThemeMode(val value: String, val title: String) {
    SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromValue(value: String): AppThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
