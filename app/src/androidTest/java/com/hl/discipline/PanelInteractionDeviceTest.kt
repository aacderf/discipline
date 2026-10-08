package com.hl.discipline

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

class PanelInteractionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = Store.get(rule.activity)
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val config = rule.activity.resources.configuration
        val folder = File(rule.activity.getExternalFilesDir(null),
            "panels-0.10.5/${config.uiMode}-${config.fontScale}").apply { mkdirs() }
        check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(folder, "$name.png")))
    }

    @Test fun selectionSurvivesSearchAndIncludesOffscreenChoices() {
        var result = ""
        val apps = (0..199).map { SelectableApp("qa.app.$it", "应用 $it") }
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme { AppSelectionContent(apps, "[\"qa.app.1\"]",
                onDismiss = {}, onSave = { result = it }) }
        } }
        // Only the viewport is composed, not all 200 installed apps.
        assertTrue(rule.onAllNodes(hasText("qa.app.", substring = true)).fetchSemanticsNodes().size < 25)
        rule.onNodeWithText("搜索应用名称或包名").performTextInput("qa.app.199")
        rule.onNodeWithText("应用 199").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("搜索应用名称或包名").performTextClearance()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("应用 1"))
        rule.onNodeWithText("应用 1").assertIsOn()
        rule.onNodeWithText("保存选择").performClick()
        rule.runOnUiThread { assertEquals(setOf("qa.app.1", "qa.app.199"), jsonArray(result).toStringList().toSet()) }
    }

    @Test fun singleChoiceAndFailureStatesDoNotSaveEmptyOrStaleResults() {
        var apps by mutableStateOf<List<SelectableApp>?>(null)
        var failed by mutableStateOf(false)
        var result = ""
        var retries = 0
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme { AppSelectionContent(apps, "[]", singleSelection = true, loadError = failed,
                onRetry = { retries++ }, onDismiss = {}, onSave = { result = it }) }
        } }
        rule.onNodeWithText("正在读取应用…").assertExists()
        rule.onNodeWithText("设置使用上限").assertIsNotEnabled()
        rule.runOnUiThread { failed = true }
        rule.onNodeWithText("重新读取").performClick()
        rule.runOnUiThread { assertEquals(1, retries); failed = false; apps = listOf(
            SelectableApp("qa.music", "音乐"), SelectableApp("qa.video", "视频")) }
        rule.onNodeWithText("音乐").performClick()
        rule.onNodeWithText("视频").performClick()
        rule.onNodeWithText("音乐").assertIsOff()
        rule.onNodeWithText("视频").assertIsOn()
        rule.onNodeWithText("设置使用上限").performClick()
        rule.runOnUiThread { assertEquals(listOf("qa.video"), jsonArray(result).toStringList()) }
    }

    @Test fun reversingSheetExitRestoresItAndClosingCompletesOnce() {
        var visible by mutableStateOf(true)
        var hidden = 0
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme { Box(Modifier.fillMaxSize()) {
                AppModalSheet(visible, { visible = false }, skipPartiallyExpanded = true,
                    onHidden = { hidden++ }) {
                    Text("可重开的面板", Modifier.testTag("reopen-body"))
                    TextButton(onClick = { visible = false }) { Text("收起") }
                }
            } }
        } }
        rule.onNodeWithTag("reopen-body").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("收起").performClick()
        rule.mainClock.advanceTimeBy(48)
        rule.runOnUiThread { visible = true }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("reopen-body").assertIsDisplayed()
        if (rule.activity.motionEnabled) rule.runOnUiThread { assertEquals(0, hidden) }
        val before = hidden
        rule.onNodeWithText("收起").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("reopen-body").assertDoesNotExist()
        rule.runOnUiThread { assertEquals(before + 1, hidden) }
        rule.mainClock.autoAdvance = true
    }

    @Test fun collapsingFocusedFieldsDropsFocusAndKeepsDraftOnReopen() {
        var open by mutableStateOf(true)
        var draft by mutableStateOf("原值")
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme { Column {
                TextButton(onClick = { open = !open }) { Text("切换字段") }
                FormDisclosure(open) { Input("测试输入", draft, { draft = it }, Modifier.testTag("draft-field")) }
            } }
        } }
        rule.onNodeWithTag("draft-field").performClick().performTextReplacement("新值")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("切换字段").performClick()
        rule.mainClock.advanceTimeBy(48)
        if (rule.activity.motionEnabled) rule.onNodeWithTag("draft-field").assertIsNotFocused()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("draft-field").assertDoesNotExist()
        rule.onNodeWithText("切换字段").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("新值").assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test fun applicationChoiceHandsOffToQuotaEditorAndPreservesRules() {
        rule.onNode(hasContentDescription("专注")).performClick()
        rule.onNodeWithTag("focus-app-limits").performScrollTo().performClick()
        rule.onNodeWithText("添加应用限额").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("正在读取应用…").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("搜索应用名称或包名").performTextInput("com.android.chrome")
        rule.onNodeWithText("Chrome").assertIsDisplayed().performTouchInput { click() }
        screenshot("application-selection")
        rule.onNodeWithText("设置使用上限").assertIsDisplayed().performTouchInput { click() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("每日上限（分钟）").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("选择一个应用").assertDoesNotExist()
        screenshot("quota-handoff")
        rule.onNodeWithText("每日上限（分钟）").performTextInput("20")
        rule.onNodeWithText("每周上限（分钟）").performTextInput("90")
        rule.onNodeWithText("保存", substring = false).performClick()
        rule.runOnUiThread {
            val limits = rule.activity.db.settings().getJSONArray("appLimits")
            assertEquals(1, limits.length())
            assertEquals("com.android.chrome", limits.getJSONObject(0).getString("package"))
            assertEquals(20, limits.getJSONObject(0).getInt("dailyMinutes"))
            assertEquals(90, limits.getJSONObject(0).getInt("weeklyMinutes"))
        }
    }

    @Test fun courseDraftSurvivesRecreationAndSavingDoesNotDuplicateIt() {
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNodeWithTag("timetable-management").performClick()
        rule.onNodeWithText("添加课程").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("课程名").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("课表管理").assertDoesNotExist()
        rule.onNodeWithText("课程名").performTextInput("面板草稿课程")
        rule.onNodeWithText("地点", substring = false).performTextInput("测试教室")
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("面板草稿课程").assertExists()
        rule.onNodeWithText("课程名").performClick().performTextReplacement("面板草稿课程")
        screenshot("course-editor-restored")
        rule.onNodeWithText("保存课程").assertHeightIsAtLeast(48.dp).assertIsDisplayed().performTouchInput { click() }
        rule.runOnUiThread {
            val rows = Timetable.rows(Timetable.state(rule.activity.db))
            assertEquals(1, rows.count { it.optString("title") == "面板草稿课程" })
            assertEquals("测试教室", rows.single { it.optString("title") == "面板草稿课程" }.optString("room"))
        }
    }

    @Test fun editingOneWeekPreservesTheSeriesAndInvalidInputsDoNotSave() {
        val today = Rules.date(System.currentTimeMillis())
        val week = Timetable.weekOf(today).coerceIn(1, 18)
        rule.runOnUiThread {
            val state = Timetable.state(rule.activity.db)
            Timetable.upsert(state, Store.obj("id", "edit-series", "title", "系列原名", "room", "原教室",
                "day", today.dayOfWeek.value, "from", 1, "until", 2,
                "weeks", org.json.JSONArray((1..18).toList()), "source", "manual", "review", false))
            rule.activity.db.put("state", state)
            rule.activity.refresh()
        }
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNodeWithText("系列原名").performClick()
        rule.onNodeWithText("星期：1–7").performTextReplacement("0")
        rule.onNodeWithText("保存这周").performClick()
        rule.onNodeWithText("请填写名称、星期、1–12 节及 1–18 周").assertExists()
        rule.runOnUiThread { assertEquals(1, Timetable.rows(Timetable.state(rule.activity.db)).count {
            it.optString("id") == "edit-series" }) }
        rule.onNodeWithText("星期：1–7").performTextReplacement(today.dayOfWeek.value.toString())
        rule.onNodeWithText("课程名").performTextReplacement("本周临时名称")
        rule.onNodeWithText("保存这周").assertIsDisplayed().performTouchInput { click() }
        rule.runOnUiThread {
            val rows = Timetable.rows(Timetable.state(rule.activity.db))
            assertTrue(rows.any { it.optString("title") == "本周临时名称" && Timetable.inWeek(it, week) })
            val other = if (week == 18) 17 else week + 1
            assertTrue(rows.any { it.optString("title") == "系列原名" && Timetable.inWeek(it, other) })
        }
    }
}
