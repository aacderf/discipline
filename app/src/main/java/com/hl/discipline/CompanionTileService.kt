package com.hl.discipline

import android.content.SharedPreferences
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Restores the small overlay without opening the app or leaving a hidden touch target. */
class CompanionTileService : TileService() {
    private val preferences by lazy { getSharedPreferences("companion-ui", MODE_PRIVATE) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "hidden") updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        preferences.registerOnSharedPreferenceChangeListener(listener)
        updateTile()
    }

    override fun onStopListening() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (!available()) { updateTile(); return }
        if (isLocked) unlockAndRun { restore() } else restore()
    }

    private fun available() = Store.get(this).settings().optBoolean("companionEnabled") &&
        Settings.canDrawOverlays(this)

    private fun restore() {
        try { FocusCompanionService.restore(this) }
        catch (error: RuntimeException) { Store.get(this).log("gap", "悬浮球未能恢复：${error.message}") }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = available()
        val hidden = preferences.getBoolean("hidden", false)
        tile.label = "自律悬浮球"
        tile.state = when {
            !enabled -> Tile.STATE_UNAVAILABLE
            hidden -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.contentDescription = if (!enabled) "请先在专注页启用悬浮球并授权" else
            if (hidden) "显示自律悬浮球" else "自律悬浮球已显示"
        tile.updateTile()
    }
}
