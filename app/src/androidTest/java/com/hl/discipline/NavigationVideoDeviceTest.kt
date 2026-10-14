package com.hl.discipline

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationVideoDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun representativeFixture() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            db.put("state", db.settings().put("themeMode", "light").put("monitor", false))
            val now = System.currentTimeMillis()
            repeat(11) { index ->
                db.put("plans", Store.obj("id", "video-$index", "name", "合成任务 ${index + 1}",
                    "metric", if (index == 1) "presence" else "check", "target", if (index == 1) 240 else 1,
                    "cycle", "once", "created", if (index < 2) now - 1000 else now + 7 * 1440 * Rules.MINUTE,
                    "deadline", now + (if (index < 2) 1 else 8) * 1440 * Rules.MINUTE,
                    "from", 0, "until", 1440, "penalty", 0))
            }
            rule.activity.engine.tick()
            rule.activity.launchIntro = false
            rule.activity.motionEnabled = true
            rule.activity.selectedTab = "today"
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    private fun maximumInk(bitmap: Bitmap, bounds: Rect): Int {
        var maximum = 0
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
            for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                maximum = maxOf(maximum, 247 - Color.red(bitmap.getPixel(x, y)))
            }
        }
        return maximum
    }

    private fun frame(name: String): Bitmap {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(rule.activity.getExternalFilesDir(null), "navigation-video-0.12.4").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun incomingHeadingAndReportHaveTheSameOpacityAndRapidNavigationKeepsTheTarget() {
        // Warm both reports so this checks motion, not first-load work.
        rule.onNode(hasContentDescription("课表") and hasClickAction()).performClick()
        rule.onNodeWithTag("timetable-view-switch").assertExists()
        rule.onNode(hasContentDescription("今天") and hasClickAction()).performClick()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasContentDescription("课表") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(96)
        val bitmap = frame("tab-96ms")
        val heading = rule.onNode(hasText("课表") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val report = rule.onNodeWithTag("timetable-view-switch").fetchSemanticsNode().boundsInRoot
        val headingInk = maximumInk(bitmap, heading)
        val reportInk = maximumInk(bitmap, report)
        assertTrue("The new page must already be appearing", headingInk > 20 && reportInk > 20)
        assertTrue("The report must not appear fully under a still-fading heading: $headingInk / $reportInk",
            kotlin.math.abs(headingInk - reportInk) < 35)
        rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onNode(hasContentDescription("统计") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onNode(hasContentDescription("今天") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("today-range-switch").assertExists()
        rule.onNode(hasContentDescription("今天") and hasClickAction()).assertIsSelected()
        rule.runOnUiThread { rule.activity.motionEnabled = false; rule.activity.selectedTab = "focus" }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithText("自由专注").assertExists()
        rule.onNodeWithTag("today-range-switch").assertDoesNotExist()
        rule.onNode(hasContentDescription("专注") and hasClickAction()).assertIsSelected()
        rule.mainClock.autoAdvance = true
    }

    @Test fun opaqueSettingsEntersSpatiallyAndReturnsToTheSameTask() {
        rule.mainClock.autoAdvance = false
        rule.onNode(hasContentDescription("设置") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(80)
        val midpoint = rule.onNodeWithText("设置").getUnclippedBoundsInRoot().left.value
        frame("settings-80ms")
        rule.mainClock.advanceTimeBy(400)
        val settled = rule.onNodeWithText("设置").getUnclippedBoundsInRoot().left.value
        assertTrue("A full page must travel into view rather than pop in and drift a few pixels",
            midpoint > settled + 16f)
        rule.onNode(hasContentDescription("返回") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("合成任务 1").assertExists()
        rule.onNodeWithTag("today-range-switch").assertExists()
        rule.mainClock.autoAdvance = true
    }
}
