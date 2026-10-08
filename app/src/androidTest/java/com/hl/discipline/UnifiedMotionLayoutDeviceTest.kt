package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class UnifiedMotionLayoutDeviceTest {
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
            "motion-0.10.7/${config.uiMode}-${config.fontScale}").apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun focusActionsStayVisibleWhileConfigurationScrollsAndTimerChanges() {
        rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
        rule.onNodeWithText("开始专注").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("focus-time").assertIsDisplayed()
        assertTrue("Entire time readout must stay above pinned controls",
            rule.onNodeWithTag("focus-time").getUnclippedBoundsInRoot().bottom <=
                rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot().top)
        screenshot("focus-idle")
        val actionTop = rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot().top
        rule.onNodeWithTag("focus-preferences").performScrollTo().performClick()
        rule.onNodeWithText("每组轮次").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("开始专注").assertIsDisplayed()
        assertEquals(actionTop, rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot().top)
        screenshot("focus-config-scrolled")
        rule.onNodeWithText("开始专注").performClick()
        rule.onNodeWithText("暂停专注").assertIsDisplayed().performClick()
        rule.onNodeWithText("继续专注").assertIsDisplayed()
        rule.onNodeWithText("结束本次").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.runOnUiThread { assertEquals("paused", rule.activity.db.one("state", "focus")!!.getString("phase")) }
        screenshot("focus-paused")
        rule.onNode(hasContentDescription("课表") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
        rule.onNodeWithText("继续专注").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("继续专注").assertIsDisplayed()
    }

    @Test fun configurationDisclosurePreservesDraftAndReachableQuotaEntry() {
        rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
        rule.onNodeWithTag("focus-preferences").performScrollTo().performClick()
        rule.onNodeWithText("分钟").performScrollTo().performTextReplacement("40")
        rule.onNodeWithTag("focus-preferences").performScrollTo().performClick()
        rule.onNodeWithTag("focus-preferences").assert(hasText("40 分钟专注", substring = true))
        rule.activityRule.scenario.recreate()
        rule.onNodeWithTag("focus-preferences").assert(hasText("40 分钟专注", substring = true))
        rule.onNodeWithTag("focus-app-limits").performScrollTo().performClick()
        rule.onNodeWithText("添加应用限额").assertIsDisplayed()
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.onNodeWithText("开始专注").assertIsDisplayed()
    }

    @Test fun interruptedNavigationHasOneActionableDestinationAndStableBar() {
        val navTop = rule.onNodeWithTag("nav-selection").getUnclippedBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        for (label in listOf("专注", "统计", "课表", "今天")) {
            rule.onNode(hasContentDescription(label) and hasClickAction()).performClick()
            rule.mainClock.advanceTimeBy(80)
            rule.onNode(hasContentDescription(label) and hasClickAction()).assertIsSelected()
            assertEquals(navTop, rule.onNodeWithTag("nav-selection").getUnclippedBoundsInRoot().top)
        }
        rule.mainClock.advanceTimeBy(500)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("create-plan-fab").assertIsDisplayed()
        screenshot("today-composition")
        rule.onNode(hasContentDescription("课表") and hasClickAction()).performClick()
        screenshot("timetable-composition")
    }
}
