package com.hl.discipline

import android.os.Build
import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowInsetsController
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class TaskWindowThemeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()
    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.launchIntro = false
            rule.activity.refresh()
        }
    }
    @After fun restoreDefaultTheme() {
        rule.runOnUiThread {
            val settings = rule.activity.db.settings().put("themeMode", "system")
            rule.activity.db.put("state", settings)
            rule.activity.refresh()
        }
    }
    private fun frame(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "fixes-0.10.9/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun oldCrossDayLocationTaskAppearsInTodayAndWeekAndPlusRemainsAvailable() {
        val now = System.currentTimeMillis()
        val due = Rules.at(Rules.date(now).plusDays(6), 1439)
        rule.runOnUiThread {
            val p = Store.obj("id", "cross-day", "name", "复习高数", "created", now - Rules.MINUTE,
                "deadline", due, "cycle", "once", "metric", "presence", "target", 2400,
                "place", "library", "from", 0, "until", 1440, "pinned", true)
            rule.activity.db.put("plans", p)
            rule.activity.db.put("instances", Store.copy(p).put("id", "cross-day:$due")
                .put("planId", "cross-day").put("start", Rules.at(Rules.date(due - 1), 0))
                .put("due", due).put("status", "open").put("manual", 0))
            rule.activity.engine.tick()
            rule.activity.refresh()
        }
        rule.onNodeWithText("复习高数").assertIsDisplayed()
        rule.onNodeWithTag("create-plan-fab").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        frame("today-cross-day")
        rule.onNodeWithTag("today-range-switch").performClick()
        rule.onNodeWithText("本周累计与跨日目标").assertIsDisplayed()
        rule.onNodeWithText("复习高数").assertIsDisplayed()
        frame("week-cross-day")
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("创建计划").assertIsDisplayed()
        rule.onNodeWithText("计划名称").assertExists()
    }
    @Test fun themeSelectionChangesActualPaletteBarsAndSurvivesRecreationAndBackup() {
        rule.onNode(hasContentDescription("设置") and hasClickAction()).performClick()
        rule.onNodeWithText("主题").performScrollTo().performClick()
        rule.onNodeWithText("深色").performClick()
        assertEquals(AppThemeMode.DARK, rule.activity.themeMode)
        assertEquals("dark", rule.activity.db.settings().optString("themeMode"))
        rule.onNode(hasContentDescription("返回") and hasClickAction()).performClick()
        rule.waitForIdle()
        val pixel = rule.onRoot().captureToImage().asAndroidBitmap().getPixel(1, 100)
        assertTrue("Actual page background must be dark", Color.red(pixel) < 60)
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        rule.runOnUiThread { assertEquals(0, rule.activity.window.insetsController!!.systemBarsAppearance and mask) }
        frame("today-dark")
        rule.activityRule.scenario.recreate()
        assertEquals(AppThemeMode.DARK, rule.activity.themeMode)
        val backup = rule.activity.db.prepareImport(rule.activity.db.exportJson())
        val states = backup.getJSONArray("state")
        assertTrue((0 until states.length()).map { states.getJSONObject(it) }.any {
            it.optString("id") == "settings" && it.optString("themeMode") == "dark"
        })
        for (name in listOf("课表", "专注", "统计", "今天")) {
            rule.onNode(hasContentDescription(name) and hasClickAction()).performClick()
            frame("dark-$name")
        }
        rule.onNode(hasContentDescription("设置") and hasClickAction()).performClick()
        rule.onNodeWithText("主题").performScrollTo().performClick()
        rule.onNodeWithText("浅色").performClick()
        assertEquals(AppThemeMode.LIGHT, rule.activity.themeMode)
        frame("settings-light")
    }
}
