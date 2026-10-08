package com.hl.discipline

import android.os.Build
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CopySettingsDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val config = rule.activity.resources.configuration
        val folder = File(rule.activity.getExternalFilesDir(null),
            "copy-0.10.6/${config.uiMode}-${config.fontScale}").apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun quotaShortcutIsReachableAndReturningKeepsTheLiveTimer() {
        rule.onNode(hasContentDescription("专注")).performClick()
        rule.onNodeWithTag("focus-app-limits").assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        screenshot("focus-entry")
        rule.onNodeWithText("开始专注").assertIsDisplayed().performClick()
        rule.runOnUiThread { assertEquals("work", rule.activity.db.one("state", "focus")!!.getString("phase")) }
        rule.onNodeWithTag("focus-app-limits").performScrollTo().performClick()
        rule.onNodeWithText("添加应用限额").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("添加应用限额").assertExists()
        rule.runOnUiThread { assertEquals("work", rule.activity.db.one("state", "focus")!!.getString("phase")) }
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.onNode(hasContentDescription("专注") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("暂停专注").assertIsDisplayed().performClick()
        var paused = ""
        rule.runOnUiThread { paused = rule.activity.db.one("state", "focus")!!.toString() }
        rule.onNodeWithTag("focus-app-limits").performScrollTo().performClick()
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.runOnUiThread { assertEquals(paused, rule.activity.db.one("state", "focus")!!.toString()) }
        rule.onNodeWithText("继续专注").assertExists()
    }

    @Test fun settingsGroupsSeparateTaskLimitsFromAppQuotas() {
        rule.onNode(hasContentDescription("设置")).performClick()
        rule.onNodeWithText("运行状态").assertExists()
        rule.onNodeWithText("管理应用限额").assertDoesNotExist()
        rule.onNodeWithText("应用限额").assertDoesNotExist()
        rule.onNodeWithText("专注设置").assertExists()
        screenshot("settings-first-screen")
        rule.onNodeWithText("任务未完成时").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("每日限制时长上限").performScrollTo().assertHeightIsAtLeast(48.dp)
        screenshot("settings-task-limits")
        rule.onNodeWithText("每日限制时长上限").performClick()
        rule.onNodeWithText("任务限制每日时长上限").assertIsDisplayed()
        rule.onNodeWithText("小时").assertExists()
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("首页寄语").performScrollTo().performClick()
        rule.onNodeWithText("寄语内容").assertExists()
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.onNodeWithText("数据与隐私").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("从备份恢复").performScrollTo().assertHeightIsAtLeast(48.dp)
        screenshot("settings-data")
    }

    @Test fun movingNavigationSurfaceRemainsLightInTheActualBar() {
        rule.onNodeWithTag("nav-selection").assertExists()
        for (name in listOf("课表", "专注", "统计", "今天")) {
            rule.onNode(hasContentDescription(name) and hasClickAction()).performClick()
            rule.onNode(hasContentDescription(name) and hasClickAction()).assertIsSelected()
        }
        screenshot("navigation-mint")
    }
}
