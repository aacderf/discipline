package com.hl.discipline

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Same fixture and navigation sequence for both installed APKs; emulator results only. */
class PagePerformanceDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()
    @Before fun fixture() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            val now = System.currentTimeMillis()
            repeat(100) { i ->
                val id = "bench-$i"
                db.put("plans", Store.obj("id", id, "name", "性能样例计划${i + 1}", "metric", "check", "target", 1,
                    "cycle", "once", "created", now - Rules.MINUTE, "deadline", now + 60 * Rules.MINUTE,
                    "from", 0, "until", 1440, "penalty", 0))
                db.put("instances", Store.obj("id", "task-$id", "planId", id, "name", "性能样例计划${i + 1}",
                    "metric", "check", "target", 1, "manual", 1, "status", "done", "from", 0, "until", 1440,
                    "start", Rules.at(Rules.date(now), 0), "due", now + 60 * Rules.MINUTE))
                db.put("sessions", Store.obj("id", "session-$id", "kind", "focus", "task", "task-$id", "place", "",
                    "start", now - (i + 2) * Rules.MINUTE, "end", now - (i + 1) * Rules.MINUTE))
            }
            rule.activity.launchIntro = false
            rule.activity.motionEnabled = true
            rule.activity.selectedTab = "today"
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    @Test fun prepareFixtureOnly() { rule.waitForIdle() }

    @Test fun reportRowsAreCreatedOnlyWhenScrolledIntoView() {
        rule.runOnUiThread { rule.activity.selectedTab = "stats" }
        rule.waitUntil(8_000) { rule.onAllNodesWithText("专注总时长").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("性能样例计划100").assertDoesNotExist()
        rule.onNodeWithTag("stats-list").performScrollToNode(hasText("性能样例计划100"))
        rule.onNodeWithText("性能样例计划100").assertIsDisplayed()
        val before = rule.onNodeWithTag("stats-list").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("统计") and hasClickAction()).performClick()
        rule.onNodeWithText("性能样例计划100").assertIsDisplayed()
        val after = rule.onNodeWithTag("stats-list").fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        org.junit.Assert.assertEquals(before, after, .01f)
    }
}
