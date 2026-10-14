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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** UI-only regressions: baseline, refresh geometry, first-screen controls and accessibility. */
class LayoutHierarchyDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            rule.activity.db.atomic { Store.TABLES.forEach { rule.activity.db.writableDatabase.delete(it, null, null) } }
            rule.activity.launchIntro = false
            rule.activity.refresh()
        }
    }

    private fun frame(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "layout-0.12.5/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun metricPairsKeepBaselinesAndRemainReadableAtLargeText() {
        var large by mutableStateOf(false)
        rule.runOnUiThread { rule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.3f else 1f)) {
                DisciplineTheme(themeMode = if (large) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                        MetricValueText(minuteMetricParts(71 * 60L + 46), Modifier.width(136.dp), fontSize = 28.sp)
                        MetaPill("全天有效")
                    }
                }
            }
        } }
        fun baseline(text: String): Float {
            val node = rule.onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // Compose can reuse a wider paragraph for a no-wrap Text; check the painted line itself.
            assertTrue("Visible line overflow: $text", layout.getLineLeft(0) >= -1f &&
                layout.getLineRight(0) <= layout.size.width + 1f)
            assertFalse("Vertical overflow: $text", layout.didOverflowHeight)
            return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        for (isLarge in listOf(false, true)) {
            rule.runOnUiThread { large = isLarge }
            assertEquals(baseline("71"), baseline("小时"), 1f)
            assertEquals(baseline("46"), baseline("分"), 1f)
            frame(if (isLarge) "metric-dark-large" else "metric-light")
        }
    }

    @Test fun overviewRefreshDoesNotShiftAndRulesStayCollapsedUntilRequested() {
        var loading by mutableStateOf(false)
        val now = System.currentTimeMillis()
        val data = StatisticsReport(7, now - 6 * 86_400_000L, now, (71 * 60L + 46) * Rules.MINUTE,
            (12 * 60L + 30) * Rules.MINUTE, 12, 3, 2, 1, 1, 1, emptyList(), emptyList(), emptyList())
        rule.runOnUiThread { rule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                DisciplineTheme(themeMode = AppThemeMode.DARK) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                        StatsOverviewCard(data, loading, false)
                        Text("后续图表", Modifier.testTag("after-overview"))
                    }
                }
            }
        } }
        val before = rule.onNodeWithTag("after-overview").getUnclippedBoundsInRoot()
        rule.onNodeWithText("相连计时片段合并为一段", substring = true).assertDoesNotExist()
        rule.runOnUiThread { loading = true }
        rule.onNodeWithText("更新中…").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("after-overview").getUnclippedBoundsInRoot())
        rule.runOnUiThread { loading = false }
        frame("overview-dark-large")
        rule.onNodeWithText("ⓘ 统计规则").performClick()
        rule.onNodeWithText("相连计时片段合并为一段", substring = true).assertIsDisplayed()
    }

    @Test fun todayMetadataIsCompactAndFocusFooterIsVisibleBeforeStarting() {
        rule.runOnUiThread {
            val now = System.currentTimeMillis()
            rule.activity.db.put("plans", Store.obj("id", "compact-plan", "name", "复习高数",
                "metric", "presence", "target", 2400, "cycle", "once", "place", "",
                "deadline", now + 3_600_000, "from", 0, "until", 1440, "created", now - 60_000, "penalty", 0))
            rule.activity.engine.tick()
            rule.activity.selectedTab = "today"
            rule.activity.refresh()
        }
        rule.onNodeWithText("全天有效").assertIsDisplayed()
        rule.onNodeWithText("今日进度").assertDoesNotExist()
        rule.onNodeWithText("接下来").assertIsDisplayed()
        // IDs are generated by the existing Engine; find the single visible card without assuming its format.
        val taskCard = rule.onAllNodes(hasTestTagPrefix("task-card-"), useUnmergedTree = true).onFirst()
        val bounds = taskCard.getUnclippedBoundsInRoot()
        assertTrue("Compact task height: ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top < 225.dp)
        frame("today-compact")
        rule.runOnUiThread { rule.activity.selectedTab = "focus" }
        rule.onNodeWithTag("focus-preferences").assertIsDisplayed()
        rule.onNodeWithText("预计 ", substring = true).assertIsDisplayed()
        rule.onNodeWithText("本轮进度 0%").assertIsDisplayed()
        frame("focus-idle")
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
