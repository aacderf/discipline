package com.hl.discipline

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*

class UsageInsightsUiDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<ComposeMainActivity>()
    @Before fun fixture() {
        check(rule.activity.packageName.endsWith(".qa") && (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone")))
        rule.runOnUiThread {
            listOf(LocationService::class.java,FocusCompanionService::class.java,FallbackBlockService::class.java)
                .forEach {rule.activity.stopService(Intent(rule.activity,it))}
            rule.activity.db.atomic {Store.TABLES.forEach {rule.activity.db.writableDatabase.delete(it,null,null)}}
            rule.activity.launchIntro=false; rule.activity.motionEnabled=false; rule.activity.refresh()
        }
    }
    @Test fun reportEntryExplainsAccessAndKeySettingsStayWithinSmallScreen() {
        rule.onNode(hasContentDescription("统计") and hasClickAction()).performClick()
        rule.onNodeWithText("应用使用与下周计划").performScrollTo().performClick()
        rule.onNodeWithText("应用使用").assertIsDisplayed()
        rule.onNodeWithText("授权使用情况访问").assertExists()
        rule.onNodeWithText("配置 DeepSeek").performScrollTo().performClick()
        rule.onNodeWithText("DeepSeek V4.1").assertIsDisplayed()
        rule.onNodeWithText("API Key").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("生成下周建议").performScrollTo().assertIsNotEnabled()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(rule.activity.getExternalFilesDir(null),"usage-insights-empty-0.12.0.png"))
    }
    @Test fun reviewedBudgetUsesEditedValueAndPreservesPermanentRule() {
        val now=System.currentTimeMillis()
        val monday=Rules.monday(Rules.date(now)).plusWeeks(1)
        val start=Rules.at(monday,0)
        rule.runOnUiThread {
            val settings=rule.activity.db.settings()
            Store.set(settings,"appLimits",JSONArray(listOf(Store.obj("package","com.android.chrome","dailyMinutes",120,"weeklyMinutes",500,"enabled",true))))
            rule.activity.db.put("state",settings)
            rule.activity.db.put("state",Store.obj("id","usageAnalysisDraft","created",now,"model",DeepSeekUsageClient.MODEL,
                "start",start,"end",Rules.at(monday.plusWeeks(1),0),"summary","测试建议：为娱乐留出适量时间。",
                "apps",JSONArray(listOf(Store.obj("package","com.android.chrome","label","浏览器","dailyMinutes",60,"weeklyMinutes",360,
                    "reason","测试建议，确认前不生效。","enabled",true)))))
            rule.activity.refresh()
        }
        rule.onNode(hasContentDescription("统计") and hasClickAction()).performClick()
        rule.onNodeWithText("应用使用与下周计划").performScrollTo().performClick()
        rule.onNodeWithText("查看并调整建议").performScrollTo().performClick()
        rule.onNodeWithText("确认下周计划").assertIsDisplayed()
        rule.onNode(hasText("每日分钟") and hasSetTextAction()).performScrollTo().performTextReplacement("45")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(rule.activity.getExternalFilesDir(null),"usage-insights-review-0.12.0.png"))
        rule.onNodeWithText("确认启用").performClick()
        rule.waitForIdle()
        assertEquals(120,UsageWeekPlans.effectiveLimits(rule.activity.db,now).getJSONObject(0).getInt("dailyMinutes"))
        assertEquals(45,UsageWeekPlans.effectiveLimits(rule.activity.db,start).getJSONObject(0).getInt("dailyMinutes"))
        assertNull(rule.activity.db.one("state","usageAnalysisDraft"))
        rule.onNodeWithText("等待周一生效",substring=true).assertExists()
    }
    @Test fun manualWeekCanBeCreatedEditedAndDeletedWithoutAiOrUsageAccess() {
        rule.onNode(hasContentDescription("统计") and hasClickAction()).performClick()
        rule.onNodeWithText("应用使用与下周计划").performScrollTo().performClick()
        rule.onNodeWithText("手动安排下周").performScrollTo().performClick()
        rule.onNodeWithText("选择应用").performClick()
        rule.waitUntil(10_000) {rule.onAllNodesWithText("Chrome").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Chrome").performClick()
        rule.onNodeWithText("保存选择").performClick()
        rule.waitUntil(6_000) {rule.onAllNodesWithText("每日分钟").fetchSemanticsNodes().isNotEmpty()}
        rule.onNode(hasText("每日分钟") and hasSetTextAction()).performTextReplacement("45")
        rule.onNode(hasText("每周分钟") and hasSetTextAction()).performTextReplacement("180")
        rule.waitForIdle()
        val imeBottom=androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!
            .getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
        assertTrue("Keyboard must be visible for this check",imeBottom>0)
        val fieldBottom=rule.onNode(hasText("每周分钟") and hasSetTextAction()).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Focused budget stays above keyboard",fieldBottom<=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).displayHeight-imeBottom+2)
        rule.runOnUiThread {rule.activity.recreate()}
        rule.waitUntil(8_000) {rule.onAllNodes(hasText("180") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        rule.onNode(hasText("45") and hasSetTextAction()).assertExists()
        rule.onNodeWithContentDescription("Chrome每日上限分钟").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(rule.activity.getExternalFilesDir(null),"usage-week-editor-0.12.1.png"))
        rule.onNodeWithText("保存下周计划").performScrollTo().performClick()
        rule.waitForIdle()
        val saved=UsageWeekPlans.plans(rule.activity.db).single()
        assertEquals("manual",saved.getString("source"))
        assertEquals(45,saved.getJSONArray("apps").getJSONObject(0).getInt("dailyMinutes"))
        rule.onNodeWithText("调整限额").performScrollTo().performClick()
        rule.onNode(hasText("每日分钟") and hasSetTextAction()).performScrollTo().performTextReplacement("30")
        rule.onNodeWithText("保存调整").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(30,UsageWeekPlans.plans(rule.activity.db).single().getJSONArray("apps").getJSONObject(0).getInt("dailyMinutes"))
        val switchName="${Rules.date(saved.getLong("start"))}的使用计划"
        rule.onNodeWithContentDescription(switchName).performScrollTo().performClick()
        rule.waitForIdle(); assertFalse(UsageWeekPlans.plans(rule.activity.db).single().getBoolean("enabled"))
        rule.onNodeWithContentDescription(switchName).performClick()
        rule.waitForIdle(); assertTrue(UsageWeekPlans.plans(rule.activity.db).single().getBoolean("enabled"))
        rule.onNodeWithText("删除计划").performScrollTo().performClick()
        rule.onNodeWithText("确认").performClick()
        rule.waitForIdle(); assertTrue(UsageWeekPlans.plans(rule.activity.db).isEmpty())
    }
}
