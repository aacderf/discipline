package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class MotionContinuityDeviceTest {
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
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(rule.activity.getExternalFilesDir(null), "motion-0.10.2").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun titleFeedbackAndViewChangesRemainInteractiveMidFlight() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("today-range-switch").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(100)
        frame("title-pressed")
        rule.onNodeWithTag("today-range-switch").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(80)
        frame("today-week-80")
        // Interrupt, don't wait for completion. Only the current view remains actionable.
        rule.onNodeWithTag("today-range-switch").performClick()
        rule.mainClock.advanceTimeBy(350)
        rule.onNodeWithText("今天，专注一件事").assertExists()
        rule.mainClock.autoAdvance = true
        rule.onNode(hasContentDescription("课表")).performClick()
        // Pick a populated day so agenda/grid actually have matching course identities.
        rule.onNodeWithText("一 ${Timetable.date(Timetable.weekOf(Rules.date(System.currentTimeMillis())).coerceIn(1,18), 1).dayOfMonth}")
            .performClick()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("timetable-view-switch").performClick()
        for (time in listOf(80L, 80L, 80L, 100L)) {
            rule.mainClock.advanceTimeBy(time)
            frame("agenda-grid-${rule.mainClock.currentTime}")
        }
        rule.onNodeWithTag("timetable-view-switch").performClick()
        rule.mainClock.advanceTimeBy(80)
        frame("grid-agenda-80")
        rule.onNodeWithTag("timetable-view-switch").performClick()
        rule.mainClock.advanceTimeBy(350)
        rule.mainClock.autoAdvance = true
        repeat(6) { rule.onNodeWithTag("timetable-zoom-out").performClick() }
        assertTrue(rule.onAllNodesWithTag("course-short-name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithText("导入 CSV").assertCountEquals(0)
        rule.onNodeWithTag("timetable-week-grid").performScrollTo()
        frame("grid-minimum")
        rule.onNodeWithTag("timetable-week-grid").performTouchInput { click(Offset(width * .22f, 120f)) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag("course-full-name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        frame("grid-focused-day")
    }

    @Test fun successfulTaskHandoffAndDetailKeepOneContinuousMaterial() {
        rule.runOnUiThread {
            val db = Store.get(rule.activity)
            val now = System.currentTimeMillis()
            for (n in 1..2) db.put("plans", Store.obj("id", "motion-$n", "name", "连续任务$n",
                "metric", "check", "target", 1, "cycle", "once", "deadline", now + 3_600_000,
                "from", 0, "until", 1440, "penalty", 0))
            rule.activity.engine.tick()
            rule.activity.refresh()
        }
        rule.onNodeWithText("标记完成").assertExists()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("标记完成").performScrollTo().performClick()
        for (t in listOf(80L, 80L, 120L, 160L)) {
            rule.mainClock.advanceTimeBy(t)
            frame("task-handoff-${rule.mainClock.currentTime}")
        }
        rule.onNodeWithText("连续任务2").performClick()
        rule.mainClock.advanceTimeBy(160)
        frame("task-detail-160")
        rule.mainClock.advanceTimeBy(180)
        rule.onNodeWithText("任务详情").assertExists()
        rule.onNode(hasContentDescription("返回")).performClick()
        rule.mainClock.advanceTimeBy(160)
        frame("detail-task-160")
        rule.mainClock.advanceTimeBy(180)
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("连续任务2").assertExists()
    }
}
