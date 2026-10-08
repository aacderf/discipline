package com.hl.discipline

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

class ComposeUiDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun clearTestData() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone")) {
            "UI fixtures may run only in an emulator"
        }
        rule.runOnUiThread {
            val db = Store.get(rule.activity)
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.getSharedPreferences("timetable_ui", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    @Test fun emptyHomePlanAndDetail() {
        rule.onNodeWithText("今天，专注一件事").assertExists()
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("创建计划").assertExists()
        rule.onNodeWithText("计划名称").performTextInput("ComposeQA")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onNodeWithText("确认计划").assertExists()
        rule.onNodeWithText("保存计划").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("ComposeQA").assertExists()
        rule.runOnUiThread {
            assertEquals(1, Store.get(rule.activity).all("plans").size)
        }
    }

    @Test fun focusStatsAndSettingsNavigation() {
        rule.onNode(hasContentDescription("专注")).performClick()
        rule.onNodeWithText("开始专注").assertExists()
        rule.onNode(hasContentDescription("统计")).performClick()
        rule.onNodeWithText("还没有专注记录").assertExists()
        rule.onNode(hasContentDescription("设置")).performClick()
        rule.onNodeWithText("运行状态").assertExists()
        rule.onNodeWithText("权限和后台运行").performScrollTo().performClick()
        rule.onNodeWithText("精确定位").assertExists()
    }

    @Test fun focusControlsAndStatsFollowRecordedState() {
        rule.onNode(hasContentDescription("专注")).performClick()
        rule.onNodeWithText("开始专注").assertIsDisplayed().performClick()
        rule.onNodeWithText("暂停专注").assertIsDisplayed().performClick()
        rule.runOnUiThread {
            assertEquals("paused", Store.get(rule.activity).one("state", "focus")?.optString("phase"))
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("继续专注").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("继续专注").assertIsDisplayed().performClick()
        Thread.sleep(1200)
        rule.onNodeWithText("结束本次").performClick()
        rule.onNodeWithText("确认").performClick()
        rule.runOnUiThread {
            assertTrue(rule.activity.engine.ranges("focus", null, null).isNotEmpty())
        }
        rule.onNode(hasContentDescription("统计")).performClick()
        rule.onNodeWithText("本期概览").assertExists()
        rule.onNodeWithText("还没有专注记录").assertDoesNotExist()
    }

    @Test fun switchingTabsKeepsSelectionAndBackReturnsToday() {
        val navTop = rule.onNode(hasContentDescription("今天")).getUnclippedBoundsInRoot().top
        rule.onNode(hasContentDescription("专注")).performClick()
        assertEquals(navTop, rule.onNode(hasContentDescription("今天")).getUnclippedBoundsInRoot().top)
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNodeWithText("课表草稿 ·", substring = true).assertExists()
        rule.onNodeWithTag("timetable-view-switch").performClick()
        rule.onNode(hasContentDescription("周网格视图")).assertExists()
        rule.onNode(hasContentDescription("统计")).performClick()
        rule.onNodeWithText("本月").performClick()
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNode(hasContentDescription("周网格视图")).assertExists()
        rule.onNode(hasContentDescription("统计")).performClick()
        rule.onNodeWithText("本月").assertIsSelected()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("今天，专注一件事").assertExists()
    }

    @Test fun compactViewSwitchesAndGridZoomKeepSelection() {
        rule.onNodeWithTag("today-range-switch").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("本周进度").assertExists()
        rule.onNodeWithTag("today-range-switch").performClick()
        rule.onNodeWithText("今天，专注一件事").assertExists()
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNodeWithTag("timetable-view-switch").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasContentDescription("周网格视图")).assertExists()
        repeat(6) { rule.onNodeWithTag("timetable-zoom-out").performClick() }
        rule.onAllNodesWithText("添加课程").assertCountEquals(0)
        rule.onAllNodesWithText("导入 CSV").assertCountEquals(0)
        assertTrue(rule.onAllNodesWithTag("course-short-name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val before = rule.onNodeWithTag("timetable-day-1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right - it.left }
        rule.onNodeWithTag("timetable-zoom-in").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("timetable-zoom-in").performClick()
        val after = rule.onNodeWithTag("timetable-day-1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue(after > before)
        val gridNode = rule.onNodeWithTag("timetable-week-grid").performScrollTo()
        val gridBounds = gridNode.getUnclippedBoundsInRoot()
        gridNode.performTouchInput {
            val middle = center.x
            pinch(Offset(middle - 35f, 80f), Offset(middle - 85f, 80f),
                Offset(middle + 35f, 80f), Offset(middle + 85f, 80f))
        }
        val pinched = rule.onNodeWithTag("timetable-day-1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("Pinch should enlarge day columns: $after -> $pinched, grid=$gridBounds", pinched > after)
        rule.onNodeWithTag("timetable-view-switch").performScrollTo().performClick()
        rule.onNode(hasContentDescription("日程视图")).assertExists()
        rule.onNodeWithTag("timetable-view-switch").performClick()
        rule.onNode(hasContentDescription("周网格视图")).assertExists()
    }

    @Test fun bottomNavigationDragSnapsAndTapStillWorks() {
        rule.onNode(hasContentDescription("今天")).performTouchInput {
            swipeRight()
        }
        rule.onNodeWithText("课表草稿 ·", substring = true).assertExists()
        rule.onNode(hasContentDescription("今天")).performClick()
        rule.onNodeWithText("今天，专注一件事").assertExists()
    }

    @Test fun finishingLastTodayTaskHandsOffToProgress() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("计划名称").performTextInput("最后一项")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onNodeWithText("保存计划").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("我的计划").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("标记完成").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("标记完成").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("今日任务已完成").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("今日任务已完成").assertExists()
        rule.onNodeWithText("1 / 1").assertExists()
        rule.onNodeWithText("下一步").assertDoesNotExist()
    }

    @Test fun planDraftSurvivesRecreation() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("计划名称").performTextInput("旋转草稿")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("旋转草稿").assertExists()
        rule.onNodeWithText("预览并保存").assertExists()
    }

    @Test fun incompletePlanKeepsEditingAndExplainsError() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onAllNodesWithText("请填写计划名称").assertCountEquals(2)
        rule.onNodeWithText("确认计划").assertDoesNotExist()
        rule.runOnUiThread { assertEquals(0, Store.get(rule.activity).all("plans").size) }
    }

    @Test fun repeatWeekdaysRemainReachable() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("重复与有效时段").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("单次目标／指定日期").performScrollTo().performClick()
        rule.onNodeWithText("每周指定日，分别达标").performClick()
        rule.onNodeWithText("周日").performScrollTo().assertIsDisplayed()
    }

    @Test fun taskCardOpensDetailAndReturns() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("计划名称").performTextInput("转场任务")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onNodeWithText("保存计划").performClick()
        rule.runOnUiThread {
            assertEquals(1, Store.get(rule.activity).all("instances").size)
        }
        rule.waitForIdle()
        rule.onNodeWithText("我的计划").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("下一步").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("下一步").assertExists()
        rule.onNodeWithText("查看详情").assertDoesNotExist()
        rule.onNode(hasContentDescription("收起任务")).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("转场任务").performClick()
        rule.onNodeWithText("任务详情").assertExists()
        rule.onNodeWithText("标记完成").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("下一步").assertExists()
    }

    @Test fun pinnedPlanComesFirstAndCompletedTaskCanBeDismissed() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("计划名称").performTextInput("A普通")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onNodeWithText("保存计划").performClick()
        rule.runOnUiThread {
            val db = Store.get(rule.activity)
            val clone = JSONObject(db.all("plans").first().toString())
            Store.set(clone, "id", Store.id())
            Store.set(clone, "name", "Z置顶")
            db.put("plans", clone)
            rule.activity.engine.tick()
            rule.activity.refresh()
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("我的计划").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Z置顶").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("置顶计划").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnUiThread {
            val plans = Store.get(rule.activity).all("plans")
            val pinned = plans
                .first { it.optString("name") == "Z置顶" }
            assertTrue("置顶状态：${plans.map { it.optString("name") to it.optBoolean("pinned") }}",
                pinned.optBoolean("pinned"))
        }
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("下一步").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText("Z置顶").assertExists()
        rule.onNodeWithText("标记完成").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("已完成 1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("已完成 1").performScrollTo().performClick()
        rule.waitForIdle()
        if (rule.onAllNodesWithText("从日常列表移除").fetchSemanticsNodes().isEmpty())
            rule.onNode(hasContentDescription("展开任务")).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("从日常列表移除").performScrollTo().performClick()
        rule.onNodeWithText("撤销移除").assertExists()
        rule.runOnUiThread {
            val done = Store.get(rule.activity).all("instances")
                .first { it.optString("name") == "Z置顶" }
            assertTrue(done.optBoolean("dismissedToday"))
        }
    }

    @Test fun deletingPlanFromManagementConfirmsAndRemovesItsCard() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("计划名称").performTextInput("待删除计划")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onNodeWithText("保存计划").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("我的计划").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("待删除计划").performScrollTo().performClick()
        rule.onNodeWithText("删除整个计划").performScrollTo().performClick()
        rule.onNodeWithText("删除整个计划？").assertExists()
        rule.onNodeWithText("确认").performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        rule.runOnUiThread {
            assertEquals(0, Store.get(rule.activity).all("plans").size)
            assertEquals(0, Store.get(rule.activity).all("instances").size)
        }
        rule.onNodeWithText("待删除计划").assertDoesNotExist()
    }

    @Test fun planTaskReturnsToExpandedPlan() {
        rule.onNodeWithTag("create-plan-fab").performClick()
        rule.onNodeWithText("计划名称").performTextInput("计划往返")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("预览并保存").performClick()
        rule.onNodeWithText("保存计划").performClick()
        rule.onNodeWithText("计划往返").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("查看任务周期").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("计划概览").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("计划概览").assertExists()
        val taskId = Store.get(rule.activity).all("instances").first().optString("id")
        rule.onNodeWithTag("task-card-$taskId", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("任务详情").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("计划概览").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("计划概览").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithText("我的计划").assertExists()
        rule.onNodeWithText("编辑计划").assertExists()
    }

    @Test fun buttonStatesKeepAccessibleTargets() {
        rule.runOnUiThread {
            rule.activity.setContent {
                DisciplineTheme {
                    Column {
                        GlassPrimaryButton("不可用", enabled = false, onClick = {})
                        GlassSecondaryButton("次要操作", onClick = {})
                        GlassIconButton("展开任务", onClick = {}) {
                            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null)
                        }
                        GlassPillButton("筛选", selected = true, onClick = {})
                        GlassLoadingButton()
                        GlassSuccessButton()
                    }
                }
            }
        }
        rule.onNodeWithText("不可用").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("次要操作").assertHeightIsAtLeast(48.dp)
        rule.onNode(hasContentDescription("展开任务")).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("筛选").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("处理中").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("已完成").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
    }
    @Test fun timetableManagementIsSeparateAndViewportSurvivesRecreation() {
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNodeWithTag("timetable-management").performClick()
        rule.onNodeWithText("添加课程").assertExists()
        rule.onNodeWithText("导入 CSV").assertExists()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        rule.onNodeWithTag("timetable-view-switch").performClick()
        repeat(3) { rule.onNodeWithTag("timetable-zoom-out").performClick() }
        val width = rule.onNodeWithTag("timetable-day-1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right - it.left }
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNode(hasContentDescription("周网格视图")).assertExists()
        assertEquals(width, rule.onNodeWithTag("timetable-day-1", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { it.right - it.left })
        assertTrue(rule.onAllNodesWithTag("course-short-name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

}
