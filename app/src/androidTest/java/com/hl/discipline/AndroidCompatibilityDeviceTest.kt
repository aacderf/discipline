package com.hl.discipline

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Run only in a dedicated QA emulator, with location and overlay permissions denied beforehand. */
class AndroidCompatibilityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        assertEquals(PackageManager.PERMISSION_DENIED,
            rule.activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(Settings.canDrawOverlays(rule.activity))
        rule.runOnUiThread {
            for (service in listOf(LocationService::class.java, FocusCompanionService::class.java,
                    FallbackBlockService::class.java)) rule.activity.stopService(Intent(rule.activity, service))
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.launchIntro = false
            rule.activity.motionEnabled = false
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    @After fun cleanup() {
        rule.runOnUiThread {
            rule.activity.stopService(Intent(rule.activity, FocusCompanionService::class.java))
            rule.activity.db.remove("state", "focus")
        }
    }

    @Test fun plannerTabsAndRangeSwitchWorkWithoutSpecialPermissions() {
        for (title in listOf("课表", "专注", "统计", "今天")) {
            rule.onNode(hasContentDescription(title) and hasClickAction()).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag("today-range-switch").performClick()
        rule.onNodeWithTag("today-range-switch").performClick()
        rule.onNodeWithTag("create-plan-fab").assertIsDisplayed()
        val frame = File(rule.activity.filesDir, "compatibility-api-${Build.VERSION.SDK_INT}.png")
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(frame))
    }

    @Test fun locationPermissionFailureKeepsPlannerAndSavedPlanAlive() {
        rule.runOnUiThread {
            val now = System.currentTimeMillis()
            rule.activity.db.put("plans", Store.obj("id", "compat-plan", "name", "兼容性任务",
                "created", now, "deadline", now + Rules.MINUTE * 60, "cycle", "once",
                "metric", "check", "target", 1, "from", 0, "until", 1440))
            AndroidCompat.startService(rule.activity, Intent(rule.activity, LocationService::class.java))
        }
        rule.waitUntil(15_000) {
            AndroidCompat.serviceProblem(rule.activity, LocationService::class.java).isNotBlank()
        }
        assertFalse(LocationService.running)
        assertEquals("兼容性任务", rule.activity.db.one("plans", "compat-plan").optString("name"))
        rule.onNodeWithTag("create-plan-fab").assertIsDisplayed()
    }

    @Suppress("DEPRECATION")
    @Test fun focusForegroundServiceWorksWithoutOverlayOrLocationPermission() {
        assertSame(rule.activity, AndroidCompat.overlayContext(rule.activity))
        rule.runOnUiThread {
            rule.activity.engine.startFocus("", "", 25, false, JSONArray(), 5, 15, 4)
            FocusCompanionService.sync(rule.activity)
            rule.activity.refresh()
        }
        rule.waitUntil(15_000) {
            rule.activity.getSystemService(ActivityManager::class.java).getRunningServices(30)
                .any { it.service.className == FocusCompanionService::class.java.name && it.foreground }
        }
        assertEquals("work", rule.activity.db.one("state", "focus").optString("phase"))
        assertTrue(AndroidCompat.serviceProblem(rule.activity, FocusCompanionService::class.java).isBlank())
        rule.runOnUiThread { rule.activity.engine.pauseFocus(); rule.activity.refresh() }
        rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
        rule.onNodeWithText("继续专注").assertIsDisplayed().performClick()
        assertEquals("work", rule.activity.db.one("state", "focus").optString("phase"))
    }

    @Test fun unavailableOemSettingsFallBackToReachableSystemSettings() {
        val actions = mutableListOf<String?>()
        val unavailable = object : ContextWrapper(rule.activity) {
            override fun startActivity(intent: Intent) {
                actions += intent.action
                when (actions.size) {
                    1 -> throw ActivityNotFoundException("OEM entry removed")
                    2 -> throw SecurityException("OEM details protected")
                    else -> assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                }
            }
        }
        assertTrue(AndroidCompat.openSettings(unavailable, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)))
        assertEquals(listOf(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS), actions)
    }
}
