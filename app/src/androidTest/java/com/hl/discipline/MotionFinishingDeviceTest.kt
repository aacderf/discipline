package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class MotionFinishingDeviceTest {
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

    private fun frame(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(rule.activity.getExternalFilesDir(null), "motion-0.10.3").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun backProgress(progress: Float) {
        rule.runOnUiThread {
            val dispatcher = rule.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 220f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 220f, progress, BackEventCompat.EDGE_LEFT))
        }
        rule.mainClock.advanceTimeBy(48)
    }

    @Test fun incomingTabAcceptsTouchBeforeItsAnimationFinishes() {
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasContentDescription("今天")).performTouchInput { click() }
        rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithTag("create-plan-fab").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("创建计划").assertExists()
        rule.mainClock.autoAdvance = true
    }

    @Test fun internalBackCancellationRestoresPanelAndReturnRestoresScroll() {
        rule.onNode(hasContentDescription("设置")).performClick()
        rule.onNodeWithText("权限和后台运行").performScrollTo()
        val y = rule.onNodeWithText("权限和后台运行").getUnclippedBoundsInRoot().top
        rule.onNodeWithText("权限和后台运行").performClick()
        rule.onNodeWithText("精确定位").assertExists()
        rule.mainClock.autoAdvance = false
        backProgress(.5f)
        frame("settings-back-half")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("精确定位").assertExists()
        frame("settings-back-cancelled")
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        assertEquals(y.value, rule.onNodeWithText("权限和后台运行").getUnclippedBoundsInRoot().top.value, 2f)
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.onNode(hasContentDescription("专注")).performClick()
        rule.onNodeWithTag("focus-app-limits").performScrollTo().performClick()
        rule.onNodeWithText("添加应用限额").assertExists()
        // A non-gesture hardware/keyboard back uses the same handler and commits once.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("focus-app-limits").assertExists()
        rule.runOnUiThread { assertTrue(rule.activity.db.all("plans").isEmpty()) }
    }

    @Test fun nativeSharedBackCancellationDoesNotPopOrLoseTaskIdentity() {
        rule.runOnUiThread {
            val now = System.currentTimeMillis()
            rule.activity.db.put("plans", Store.obj("id", "return-task", "name", "精密返回任务",
                "metric", "check", "target", 1, "cycle", "once", "deadline", now + 3_600_000,
                "from", 0, "until", 1440, "penalty", 0))
            rule.activity.engine.tick()
            rule.activity.refresh()
        }
        rule.onNodeWithText("精密返回任务").performClick()
        rule.onNodeWithText("任务详情").assertExists()
        rule.mainClock.autoAdvance = false
        backProgress(.5f)
        frame("detail-back-half")
        if (rule.activity.motionEnabled) assertTrue(rule.onAllNodes(hasContentDescription("收起任务"),
            useUnmergedTree = true).fetchSemanticsNodes().any {
                it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.HideFromAccessibility)
            })
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("任务详情").assertExists()
        frame("detail-back-cancelled")
        backProgress(.75f)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(500)
        rule.mainClock.autoAdvance = true
        rule.onAllNodesWithText("精密返回任务").assertCountEquals(1)
        rule.onNodeWithText("任务详情").assertDoesNotExist()
        rule.runOnUiThread { assertEquals("open", rule.activity.db.all("instances").single().optString("status")) }
    }

    @Test fun sheetActionKeepsExitMountedAndCanReopen() {
        var visible by mutableStateOf(true)
        rule.runOnUiThread {
        rule.activity.setContent {
            DisciplineTheme {
                CompositionLocalProvider(LocalMotionEnabled provides rule.activity.motionEnabled) {
                    Box(Modifier.fillMaxSize()) {
                        TextButton(onClick = { visible = true }) { Text("打开面板") }
                        AppModalSheet(visible, onDismiss = { visible = false }) {
                            Text("面板内容", Modifier.testTag("sheet-body"))
                            TextButton(onClick = { visible = false }) { Text("收起面板") }
                        }
                    }
                }
            }
        }
        }
        rule.onNodeWithTag("sheet-body").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("收起面板").performClick()
        rule.mainClock.advanceTimeBy(64)
        if (rule.activity.motionEnabled) rule.onNodeWithTag("sheet-body").assertExists()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("sheet-body").assertDoesNotExist()
        rule.onNodeWithText("打开面板").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("sheet-body").assertIsDisplayed()
        rule.mainClock.autoAdvance = true
    }

    @Test fun sandProgressRedrawsWithoutRecomposingLabelsOrControls() {
        var progress by mutableFloatStateOf(.1f)
        var compositions = 0
        rule.runOnUiThread {
        rule.activity.setContent {
            DisciplineTheme {
                SideEffect { compositions++ }
                HourglassInstrument({ progress }, HourglassState.Paused, true,
                    motionEnabled = false, richMotion = false, progressLabel = "静态测试仪器",
                    modifier = Modifier.testTag("instrument"))
            }
        }
        }
        rule.waitForIdle()
        val count = compositions
        val before = rule.onNodeWithTag("instrument").captureToImage().asAndroidBitmap()
        repeat(6) { step ->
            rule.runOnUiThread { progress = .2f + step * .1f }
            rule.waitForIdle()
        }
        val after = rule.onNodeWithTag("instrument").captureToImage().asAndroidBitmap()
        assertFalse(before.sameAs(after))
        assertEquals(count, compositions)
    }

    @Test fun outgoingSurfaceCannotReceivePointerClick() {
        var count = 0
        var leaving by mutableStateOf(false)
        rule.runOnUiThread {
        rule.activity.setContent {
            DisciplineTheme {
                ScreenExitGuard(leaving) {
                    TextButton(onClick = { count++ }, modifier = Modifier.testTag("guarded-action")) {
                        Text("临时操作")
                    }
                }
            }
        }
        }
        rule.onNodeWithTag("guarded-action").performTouchInput { click() }
        rule.runOnUiThread { assertEquals(1, count); leaving = true }
        rule.onNodeWithTag("guarded-action").performTouchInput { click() }
        rule.runOnUiThread { assertEquals(1, count); leaving = false }
        rule.onNodeWithTag("guarded-action").performTouchInput { click() }
        rule.runOnUiThread { assertEquals(2, count) }
    }
}
