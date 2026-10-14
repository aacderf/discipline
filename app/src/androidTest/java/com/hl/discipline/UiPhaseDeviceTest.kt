package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Only UI contracts; fixtures are limited to the disposable emulator QA package. */
class UiPhaseDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.launchIntro = false
            rule.activity.selectedTab = "today"
            rule.activity.refresh()
        }
    }

    private fun frame(name: String, dialog: Boolean = false) {
        val file = File(rule.activity.getExternalFilesDir(null), "ui-phase/$name.png")
        file.parentFile!!.mkdirs()
        val image = (if (dialog) rule.onNode(isDialog()) else rule.onRoot()).captureToImage().asAndroidBitmap()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun typographyUsesControlledFamiliesAndDigitsHaveEqualAdvances() {
        val typography = AppTypography.material
        val styles = listOf(typography.displayLarge, typography.displayMedium, typography.displaySmall,
            typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
            typography.titleLarge, typography.titleMedium, typography.titleSmall, typography.bodyLarge,
            typography.bodyMedium, typography.bodySmall, typography.labelLarge, typography.labelMedium, typography.labelSmall)
        assertTrue(styles.all { it.fontFamily == TitleFontFamily || it.fontFamily == BodyFontFamily })
        assertEquals(BodyFontFamily, typography.bodyLarge.fontFamily)
        assertEquals(BodyFontFamily, AppTypography.number.fontFamily)
        assertEquals("tnum", AppTypography.number.fontFeatureSettings)
        var widths by mutableStateOf<List<Int>>(emptyList())
        rule.runOnUiThread {
            rule.activity.setContent {
                DisciplineTheme {
                    val measurer = rememberTextMeasurer()
                    LaunchedEffect(Unit) {
                        widths = (0..9).map { measurer.measure(AnnotatedString("$it"),
                            AppTypography.number.copy(fontSize = 52.sp)).size.width }
                    }
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("专注", style = MaterialTheme.typography.headlineMedium)
                        Text("正文与表单：今天的计划，09:30", style = MaterialTheme.typography.bodyLarge)
                        Text("49:57", style = AppTypography.number, fontSize = 52.sp)
                    }
                }
            }
        }
        rule.waitUntil(5_000) { widths.size == 10 }
        assertTrue("Tabular digit widths were $widths", widths.max() - widths.min() <= 1)
        frame("typography")
    }

    @Test fun disabledTextMaintainsFourPointFiveContrastOnActualSurfaces() {
        for (dark in listOf(false, true)) {
            val p = AppColors.palette(dark)
            val s = AppColors.scheme(dark)
            for (fill in listOf(s.background, s.surface, p.primaryDisabledTop, p.primaryDisabledBottom,
                p.secondaryDisabled, p.glassButtonFill.compositeOver(s.background))) {
                val a = p.disabledText.luminance()
                val b = fill.luminance()
                val ratio = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                assertTrue("dark=$dark contrast=$ratio", ratio >= 4.5f)
            }
        }
    }

    @Test fun confirmationCardIsOpaqueAndNativeScrimIsThirtyTwoPercent() {
        var dim = -1f
        rule.runOnUiThread {
            rule.activity.setContent {
                DisciplineTheme(themeMode = AppThemeMode.LIGHT) {
                    Box(Modifier.fillMaxSize().background(Color.Red)) {
                        Text("49:57", fontSize = 72.sp)
                        GlassAlertDialog({}, title = {
                            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                            SideEffect { dim = window?.attributes?.dimAmount ?: -1f }
                            Text("结束本次专注？")
                        }, text = { Text("有效专注记录会保留。") }, confirmButton = { Text("确认") })
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(.32f, dim, .001f)
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        assertEquals(Color.White.toArgb(), bitmap.getPixel(bitmap.width / 2, bitmap.height - 8))
        frame("opaque-dialog", dialog = true)
    }

    @Test fun rangeRefreshDoesNotMoveFollowingContentAndEmptyChartHasAnAction() {
        var loading by mutableStateOf(false)
        var opened = false
        rule.runOnUiThread {
            rule.activity.setContent {
                DisciplineTheme {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        StatsRangeLine("统计范围 · 10月12日 至现在", loading)
                        Text("下方内容", Modifier.testTag("below-range"))
                        FocusDurationChart(listOf(ChartRow("周一", 0)), 0, 7, false,
                            onStartFocus = { opened = true }) { }
                    }
                }
            }
        }
        val before = rule.onNodeWithTag("below-range").getUnclippedBoundsInRoot()
        rule.runOnUiThread { loading = true }
        assertEquals(before, rule.onNodeWithTag("below-range").getUnclippedBoundsInRoot())
        rule.onNodeWithTag("chart-selection-label").assertDoesNotExist()
        rule.onNodeWithText("前往专注 ›").performClick()
        assertTrue(opened)
        frame("empty-chart")
    }

    @Test fun sevenAgendaDatesFitAt320DpAndLargeTextCentersTheSelection() {
        var scale by mutableFloatStateOf(1f)
        var day by mutableIntStateOf(7)
        rule.runOnUiThread {
            rule.activity.setContent {
                DisciplineTheme {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        Column(Modifier.width(320.dp).padding(horizontal = 20.dp)) {
                            AgendaDayStrip(7, day, false) { day = it }
                        }
                    }
                }
            }
        }
        for (index in 1..7) rule.onNodeWithTag("timetable-day-$index").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        frame("agenda-seven-days")
        for (date in 12..18) rule.onNodeWithText("$date", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                action(results)
                assertEquals(1, results.single().lineCount)
                val layout = results.single()
                assertFalse("date=$date size=${layout.size} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
            }
        val friday = rule.onNodeWithTag("timetable-day-5").getUnclippedBoundsInRoot()
        val sunday = rule.onNodeWithTag("timetable-day-7").getUnclippedBoundsInRoot()
        assertTrue(friday.right < sunday.right)
        frame("agenda-seven-days")
        rule.runOnUiThread { scale = 1.3f }
        rule.waitForIdle()
        rule.onNodeWithTag("timetable-day-7").assertIsDisplayed().assertIsSelected()
        rule.runOnUiThread { day = 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("timetable-day-1").assertIsDisplayed().assertIsSelected()
        frame("agenda-large-text")
    }

    @Test fun statisticsPeriodStaysFixedWhileTheReportScrolls() {
        rule.runOnUiThread { rule.activity.selectedTab = "stats" }
        rule.waitUntil(6_000) { rule.onAllNodesWithText("专注总时长").fetchSemanticsNodes().isNotEmpty() }
        val before = rule.onNodeWithText("本周", useUnmergedTree = true).getUnclippedBoundsInRoot()
        rule.onNodeWithTag("stats-list").performScrollToNode(hasText("导出统计 CSV"))
        assertEquals(before, rule.onNodeWithText("本周", useUnmergedTree = true).getUnclippedBoundsInRoot())
        frame("stats-scrolled-fixed-range")
    }

    @Test fun planManagementUsesCompactToolsAndTheSameCreationEntry() {
        rule.runOnUiThread {
            val now = System.currentTimeMillis()
            repeat(8) { index ->
                rule.activity.db.put("plans", Store.obj("id", "ui-plan-$index", "name", "计划 $index",
                    "metric", "check", "target", 1, "cycle", "once", "deadline", now + 3_600_000,
                    "from", 0, "until", 1440, "created", now - 60_000, "penalty", 0))
            }
            rule.activity.engine.tick()
            rule.activity.refresh()
        }
        rule.onNodeWithText("管理计划 ›").performClick()
        rule.onNodeWithText("共 8 项").assertIsDisplayed()
        rule.onNodeWithText("我的计划").assertDoesNotExist()
        rule.onNodeWithText("地点：全部地点 ▾").assertIsDisplayed()
        rule.onNodeWithTag("create-plan-fab").assertIsDisplayed().assertWidthIsAtLeast(56.dp)
        val first = rule.onNodeWithText("计划 0", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val rootBounds = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(first.top.value < (rootBounds.bottom.value - rootBounds.top.value) / 2)
        frame("compact-plans")
    }

    @Test fun focusTaskPickerIsDirectAndControlsKeepTheirHeightWhenRunningOrPaused() {
        rule.runOnUiThread {
            rule.activity.selectFocusTask("")
            rule.activity.focusMinutes = "25"
            rule.activity.selectedTab = "focus"
        }
        rule.onNodeWithTag("focus-preferences").assertIsDisplayed()
        rule.onNodeWithTag("focus-task-selector").performClick()
        rule.onNodeWithText("选择关联任务").assertIsDisplayed()
        rule.onNode(hasText("自由专注") and !hasTestTag("focus-task-selector")).performClick()
        val before = rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot()
        rule.onNodeWithText("开始专注").performClick()
        assertEquals(before, rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot())
        rule.onNodeWithText("暂停专注").performClick()
        assertEquals(before, rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot())
        rule.onNodeWithText("继续专注").assertIsDisplayed()
        frame("focus-paused-fixed-controls")
    }

    @Test fun usagePlansAreReachableWithoutScrollingTheApplicationList() {
        rule.runOnUiThread { rule.activity.selectedTab = "stats" }
        rule.onNodeWithText("应用使用与下周计划").performClick()
        rule.onNodeWithText("手动安排下周").assertIsDisplayed()
        rule.onNodeWithText("展开调整 ▾").performClick()
        rule.onNodeWithText("分析应用").assertIsDisplayed()
        frame("usage-direct-switch")
    }

    @Test fun floatingBallRetainsActionsAndAddsPhaseStateWithoutExposingHiddenPanel() {
        rule.runOnUiThread {
            val ball = CompanionBallView(rule.activity, {}, {}, {}, { _, _ -> }, {})
            ball.updateStatus(false, .25f, "work")
            ball.setExpanded(true, animate = false)
            val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
            ball.onInitializeAccessibilityNodeInfo(node)
            assertTrue(node.contentDescription.toString().contains("点按收起面板"))
            if (Build.VERSION.SDK_INT >= 30) assertTrue(node.stateDescription.toString().contains("专注中"))
            ball.updateStatus(false, .25f, "paused")
            ball.onInitializeAccessibilityNodeInfo(node)
            if (Build.VERSION.SDK_INT >= 30) assertTrue(node.stateDescription.toString().contains("已暂停"))
            val panel = CompanionRevealLayout(rule.activity)
            assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, panel.importantForAccessibility)
            panel.reveal = 1f
            assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, panel.importantForAccessibility)
            panel.acceptsInput = false
            assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, panel.importantForAccessibility)
        }
    }
}
