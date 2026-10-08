package com.hl.discipline

import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class CompanionOverlayDeviceTest {
    @Test fun dashboardToggleFullyHideRestoreAndPhasePopup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        val device = UiDevice.getInstance(instrumentation)
        val originalTiles = device.executeShellCommand("settings get secure sysui_qs_tiles").trim().replace("'", "")
        device.executeShellCommand("cmd statusbar set-tiles $originalTiles")
        device.executeShellCommand("wm dismiss-keyguard")
        context.stopService(Intent(context, FocusCompanionService::class.java))
        val db = Store.get(context)
        db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("appops set ${context.packageName} GET_USAGE_STATS allow")
        context.getSharedPreferences("companion-ui", 0).edit().clear().commit()
        val settings = db.settings()
        Store.set(settings, "companionEnabled", true)
        Store.set(settings, "appLimits", JSONArray().put(Store.obj("package", "com.android.settings",
            "enabled", true, "dailyMinutes", 60, "weeklyMinutes", 120)))
        db.put("state", settings)
        val now = System.currentTimeMillis()
        db.put("instances", Store.obj("id", "overlay-task", "planId", "overlay-task", "name", "悬浮球验证计划",
            "metric", "check", "target", 1, "start", now - 1000, "due", now + 3600_000,
            "status", "open", "from", 0, "until", 1440, "manual", 0, "place", ""))
        Engine(context).startFocus("", "", 1, false, JSONArray(), 1, 2, 4)
        val directory = File(context.getExternalFilesDir(null), "companion-0.11.3").apply { mkdirs() }
        val scenario = ActivityScenario.launch(ComposeMainActivity::class.java)
        try {
            device.pressHome()
            val ballVisible = device.wait(Until.hasObject(By.descStartsWith("自律悬浮球")), 8000)
            if (!ballVisible) {
                device.takeScreenshot(File(directory, "start-failure.png"))
                device.dumpWindowHierarchy(File(directory, "start-failure.xml"))
            }
            assertTrue("The orb must be available above the launcher", ballVisible)
            device.findObject(By.descStartsWith("自律悬浮球")).click()
            assertTrue(device.wait(Until.hasObject(By.text("此刻")), 5000))
            assertTrue(device.wait(Until.hasObject(By.textContains("悬浮球验证计划")), 5000))
            assertTrue(device.hasObject(By.textContains("今日剩余")))
            SystemClock.sleep(320) // Accessibility nodes can exist before the 280ms reveal is visible.
            val activityDump = device.executeShellCommand("dumpsys activity activities")
            assertFalse(activityDump.lineSequence().filter { it.contains("mResumedActivity:") }
                .any { it.contains(context.packageName + "/") })
            device.takeScreenshot(File(directory, "dashboard.png"))
            val orb = device.findObject(By.descStartsWith("自律悬浮球")).visibleBounds
            val dashboard = device.findObject(By.text("此刻")).visibleBounds
            assertTrue("The orb must remain exposed beside the dashboard", orb.left > dashboard.right)
            assertTrue("Compact orb keeps a 48dp hit area", orb.width() <= (49 * context.resources.displayMetrics.density).toInt())
            val pauseButton = device.findObject(By.desc("暂停专注"))
            pauseButton.click()
            val paused = device.wait(Until.hasObject(By.desc("继续计时")), 5000)
            if (!paused) {
                device.takeScreenshot(File(directory, "pause-failure.png"))
                device.dumpWindowHierarchy(File(directory, "pause-failure.xml"))
            }
            assertTrue("Pause did not update the panel; stored phase=${db.one("state", "focus")?.optString("phase")}", paused)
            assertEquals("paused", db.one("state", "focus").optString("phase"))
            // Click the orb again, not the close button: ACTION_OUTSIDE must not turn this into a reopen.
            device.findObject(By.descStartsWith("自律悬浮球")).click()
            assertTrue(device.wait(Until.gone(By.text("此刻")), 3000))
            SystemClock.sleep(400) // Includes single-tap confirmation and the finite exit.
            assertFalse("Second orb tap reopened the panel", device.hasObject(By.text("此刻")))
            device.takeScreenshot(File(directory, "ball-collapsed.png"))
            val bounds = device.findObject(By.descStartsWith("自律悬浮球")).visibleBounds
            doubleTap(instrumentation, Point(bounds.centerX(), bounds.centerY()))
            val hidden = device.wait(Until.gone(By.descStartsWith("自律悬浮球")), 4000)
            if (!hidden) {
                device.takeScreenshot(File(directory, "hide-failure.png"))
                device.dumpWindowHierarchy(File(directory, "hide-failure.xml"))
            }
            assertTrue("Double tap at $bounds; hidden=${context.getSharedPreferences("companion-ui", 0).getBoolean("hidden", false)}", hidden)
            assertTrue(context.getSharedPreferences("companion-ui", 0).getBoolean("hidden", false))
            assertFalse("Hidden mode must not retain a touch handle", device.hasObject(By.descStartsWith("恢复自律悬浮球")))
            // Send the actual ongoing notification action; it must restore the orb without opening an Activity.
            context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
                .first { it.id == 417 }.notification.contentIntent.send()
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("自律悬浮球")), 4000))
            assertFalse(device.executeShellCommand("dumpsys activity activities").lineSequence()
                .filter { it.contains("mResumedActivity:") }.any { it.contains(context.packageName + "/") })
            // Also exercise the exported, permission-protected Quick Settings restore entry.
            val restoredBounds = device.findObject(By.descStartsWith("自律悬浮球")).visibleBounds
            doubleTap(instrumentation, Point(restoredBounds.centerX(), restoredBounds.centerY()))
            assertTrue(device.wait(Until.gone(By.descStartsWith("自律悬浮球")), 4000))
            val tile = "${context.packageName}/com.hl.discipline.CompanionTileService"
            device.executeShellCommand("cmd statusbar add-tile $tile")
            device.openQuickSettings()
            var restoreTile = device.wait(Until.findObject(By.text("自律悬浮球")), 1000)
            repeat(3) {
                if (restoreTile == null) {
                    device.swipe(device.displayWidth - 30, device.displayHeight / 2,
                        30, device.displayHeight / 2, 20)
                    restoreTile = device.wait(Until.findObject(By.text("自律悬浮球")), 1500)
                }
            }
            device.takeScreenshot(File(directory, "quick-tile.png"))
            device.dumpWindowHierarchy(File(directory, "quick-tile.xml"))
            assertNotNull("The recovery tile must be visible", restoreTile)
            restoreTile!!.click()
            repeat(2) { device.swipe(device.displayWidth / 2, device.displayHeight - 80,
                device.displayWidth / 2, 60, 20) }
            device.pressHome()
            assertTrue(device.wait(Until.hasObject(By.descStartsWith("自律悬浮球")), 5000))
            device.executeShellCommand("cmd statusbar set-tiles $originalTiles")
            val focus = db.one("state", "focus")
            Store.set(focus, "phase", "break"); Store.set(focus, "remaining", 200)
            Store.set(focus, "checkpoint", System.currentTimeMillis()); Store.set(focus, "elapsed", SystemClock.elapsedRealtime())
            db.put("state", focus)
            val phaseShown = device.wait(Until.hasObject(By.text("休息结束").pkg(context.packageName)), 5000)
            if (!phaseShown) {
                device.takeScreenshot(File(directory, "phase-failure.png"))
                device.dumpWindowHierarchy(File(directory, "phase-failure.xml"))
            }
            assertTrue("Phase overlay missing; visible=${ComposeMainActivity.visible}; focus=${db.one("state", "focus")}; event=${db.one("state", "focusEvent")}", phaseShown)
            assertEquals("work", db.one("state", "focus").optString("phase"))
            val confirm = device.wait(Until.findObject(By.text("知道了").pkg(context.packageName)), 3000)
            assertNotNull("The phase overlay must expose its own confirmation action", confirm)
            device.takeScreenshot(File(directory, "phase-popup.png"))
            confirm.click()
            assertTrue(device.wait(Until.gone(By.text("休息结束")), 3000))
            assertTrue(db.one("state", "focusEvent").optBoolean("acknowledged"))
            Store.set(settings, "themeMode", "dark"); db.put("state", settings)
            SystemClock.sleep(1200) // The background snapshot refreshes once per second.
            device.findObject(By.descStartsWith("自律悬浮球")).click()
            assertTrue(device.wait(Until.hasObject(By.text("此刻")), 3000))
            SystemClock.sleep(320)
            device.takeScreenshot(File(directory, "dashboard-dark.png"))
        } finally {
            device.executeShellCommand("cmd statusbar set-tiles $originalTiles")
            scenario.close(); context.stopService(Intent(context, FocusCompanionService::class.java))
            db.remove("state", "focus")
            Store.set(settings, "companionEnabled", false); db.put("state", settings)
        }
    }
    private fun doubleTap(instrumentation: android.app.Instrumentation, point: Point) {
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point.x.toFloat(), point.y.toFloat(), 0)
            press.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(press, false); press.recycle(); SystemClock.sleep(40)
            val release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point.x.toFloat(), point.y.toFloat(), 0)
            release.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(release, false); release.recycle(); SystemClock.sleep(70)
        }
    }
}
