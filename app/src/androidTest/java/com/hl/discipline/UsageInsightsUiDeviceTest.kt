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
        rule.onNodeWithText("等待周一生效",substring=true).assertExists()
    }
}
