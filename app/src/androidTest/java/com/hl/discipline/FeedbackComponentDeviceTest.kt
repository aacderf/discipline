package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class FeedbackComponentDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun frame(name: String) {
        val folder = File(rule.activity.getExternalFilesDir(null),
            "refinement-0.10.8/${rule.activity.resources.configuration.uiMode}-${rule.activity.resources.configuration.fontScale}")
            .apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun progressFramesDrawWithoutRepeatedParentCompositionAndSemanticsUseRealResult() {
        var actual by mutableFloatStateOf(.15f)
        var compositions = 0
        rule.setContent {
                DisciplineTheme {
                    CompositionLocalProvider(LocalMotionEnabled provides ValueAnimator.areAnimatorsEnabled()) {
                        Column(Modifier.fillMaxSize().padding(20.dp)) {
                            SideEffect { compositions++ }
                            Text("真实任务进度")
                            ProgressTrack(actual, "测试任务完成进度", Modifier.testTag("track"))
                        }
                    }
                }
        }
        rule.waitForIdle()
        val before = rule.onNodeWithTag("track").captureToImage().asAndroidBitmap()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { actual = 1f }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(1f, rule.onNodeWithTag("track").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        val count = compositions
        repeat(4) { rule.mainClock.advanceTimeBy(48) }
        assertEquals(count, compositions)
        rule.mainClock.advanceTimeBy(300)
        val after = rule.onNodeWithTag("track").captureToImage().asAndroidBitmap()
        assertFalse(before.sameAs(after))
        frame("progress-final")
        rule.mainClock.autoAdvance = true
    }

    @Test fun chartSelectionAndLiveDataKeepTheSameGeometryAndOnlyRedrawTheBars() {
        var rows by mutableStateOf(listOf(ChartRow("周一", 10 * Rules.MINUTE),
            ChartRow("周二", 25 * Rules.MINUTE), ChartRow("周三", 5 * Rules.MINUTE)))
        var selected by mutableIntStateOf(0)
        var compositions = 0
        rule.setContent {
                DisciplineTheme {
                    CompositionLocalProvider(LocalMotionEnabled provides ValueAnimator.areAnimatorsEnabled()) {
                        Column(Modifier.fillMaxSize().padding(20.dp)) {
                            SideEffect { compositions++ }
                            FocusDurationChart(rows, selected, 7, ValueAnimator.areAnimatorsEnabled()) { selected = it }
                        }
                    }
                }
        }
        rule.waitForIdle()
        frame("chart-before")
        val initial = rule.onNodeWithTag("focus-duration-chart").getUnclippedBoundsInRoot()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("周二").performClick()
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithText("周二").assertIsSelected().assertHeightIsAtLeast(48.dp)
        frame("chart-selection-middle")
        rule.runOnUiThread { rows = rows.mapIndexed { i, row -> if (i == 1) row.copy(focus = 55 * Rules.MINUTE) else row } }
        rule.mainClock.advanceTimeBy(32)
        val count = compositions
        val mid = rule.onNodeWithTag("focus-duration-chart").captureToImage().asAndroidBitmap()
        repeat(5) { rule.mainClock.advanceTimeBy(48) }
        assertEquals(count, compositions)
        assertEquals(initial, rule.onNodeWithTag("focus-duration-chart").getUnclippedBoundsInRoot())
        rule.mainClock.advanceTimeBy(300)
        val finalChart = rule.onNodeWithTag("focus-duration-chart").captureToImage().asAndroidBitmap()
        if (ValueAnimator.areAnimatorsEnabled()) assertFalse(mid.sameAs(finalChart)) else assertTrue(mid.sameAs(finalChart))
        rule.onNodeWithText("55 分钟", useUnmergedTree = true).assertExists()
        frame("chart-final")
        rule.mainClock.autoAdvance = true
    }

    @Test fun mountedCompletedGlyphStaysStillAndARealStateChangeDrawsOneCheck() {
        var completed by mutableStateOf(true)
        rule.setContent {
                DisciplineTheme {
                    CompositionLocalProvider(LocalMotionEnabled provides ValueAnimator.areAnimatorsEnabled()) {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            TaskTypeGlyph("focus", Modifier.testTag("glyph"), completed = completed)
                        }
                    }
                }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val done = rule.onNodeWithTag("glyph").captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(240)
        assertTrue(done.sameAs(rule.onNodeWithTag("glyph").captureToImage().asAndroidBitmap()))
        rule.runOnUiThread { completed = false }
        rule.mainClock.advanceTimeBy(300)
        val idle = rule.onNodeWithTag("glyph").captureToImage().asAndroidBitmap()
        assertFalse(done.sameAs(idle))
        rule.runOnUiThread { completed = true }
        rule.mainClock.advanceTimeBy(64)
        frame("check-middle")
        rule.mainClock.advanceTimeBy(300)
        assertTrue(done.sameAs(rule.onNodeWithTag("glyph").captureToImage().asAndroidBitmap()))
        frame("check-final")
        rule.mainClock.autoAdvance = true
    }

    @Test fun taskPanelKeepsItsEdgesAndIconPressUsesOnlyMintFeedback() {
        var dark by mutableStateOf(false)
        var motion by mutableStateOf(true)
        var expanded by mutableStateOf(true)
        rule.setContent {
            DisciplineTheme(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                CompositionLocalProvider(LocalMotionEnabled provides motion) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        GlassTaskPanel(Modifier.testTag("task-shell")) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("复习高数", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                GlassIconButton(if (expanded) "收起任务" else "展开任务",
                                    Modifier.testTag("task-disclosure"), state = if (expanded) "已展开" else "已收起",
                                    onClick = { expanded = !expanded }) { DisclosureMark(expanded) }
                            }
                            ProgressTrack(.07f, "真实任务进度", Modifier.testTag("task-track"))
                            if (expanded) Text("有效时段 00:00—24:00")
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            rule.runOnUiThread { dark = night; expanded = true }
            rule.waitForIdle()
            rule.onNodeWithTag("task-disclosure").assertHeightIsAtLeast(48.dp)
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("task-disclosure").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(160)
            val pressed = rule.onNodeWithTag("task-disclosure").captureToImage().asAndroidBitmap()
            assertEquals(AppColors.palette(night).iconPressed.toArgb(), pressed.getPixel(10, pressed.height / 2))
            frame(if (night) "task-pressed-dark" else "task-pressed-light")
            rule.onNodeWithTag("task-disclosure").performTouchInput { up() }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            val shell = rule.onNodeWithTag("task-shell").getUnclippedBoundsInRoot()
            val track = rule.onNodeWithTag("task-track").getUnclippedBoundsInRoot()
            assertTrue(track.left > shell.left && track.right < shell.right && track.bottom < shell.bottom)
            rule.onNodeWithContentDescription("展开任务").assertIsDisplayed()
            frame(if (night) "task-collapsed-dark" else "task-collapsed-light")
        }
        rule.runOnUiThread { motion = false }
        rule.onNodeWithTag("task-disclosure").performClick()
        rule.onNodeWithContentDescription("收起任务").assertIsDisplayed()
    }

}
