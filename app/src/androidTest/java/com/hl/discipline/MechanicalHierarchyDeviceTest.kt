package com.hl.discipline

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MechanicalHierarchyDeviceTest {
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

    @Test fun homeAndStatsExposeLimitsWithoutVisitingSettings() {
        rule.onNodeWithTag("today-app-limit-status").assertIsDisplayed().performClick()
        rule.onNodeWithText("添加应用限额").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回", useUnmergedTree = true).performClick()
        rule.runOnUiThread { rule.activity.selectedTab = "stats" }
        rule.onNodeWithTag("stats-app-limits").assertIsDisplayed().performClick()
        rule.onNodeWithText("添加应用限额").assertIsDisplayed()
    }

    @Test fun mountedAndLoadingGateAreQuietButARealLockHasOneFeedback() {
        val events = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
        }
        var label by mutableStateOf("已设限")
        var ready by mutableStateOf(false)
        var motion by mutableStateOf(true)
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme { CompositionLocalProvider(LocalMotionEnabled provides motion, LocalHapticFeedback provides feedback) {
                LimitGateBadge(label, true, ready = ready)
            } }
        } }
        rule.runOnIdle { label = "已锁止"; ready = true }
        rule.waitForIdle(); assertTrue("First query should not mimic a new lock", events.isEmpty())
        rule.runOnIdle { label = "已设限" }; rule.waitForIdle()
        rule.runOnIdle { label = "已锁止" }; rule.waitForIdle()
        assertEquals(2, events.size) // two real engaged state changes, never mounting/loading.
        rule.runOnIdle { motion = false; label = "已设限" }; rule.waitForIdle()
        assertEquals(2, events.size)
        rule.onNodeWithText("已设限").assertIsDisplayed()
    }

    @Test fun focusRestoreAndResumeDoNotFlipButNewRoundDoesAndMotionOffSettles() {
        var identity by mutableStateOf<FocusTurnIdentity?>(FocusTurnIdentity(10, 1))
        var phase by mutableStateOf("work")
        var motion by mutableStateOf(true)
        var turn: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>? = null
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme {
                val token = rememberFocusTurn(identity, phase, motion) {}
                turn = rememberHourglassTurn(token, if(phase=="work") HourglassState.Running else HourglassState.Paused, motion)
                Text("$token", Modifier.testTag("turn-token"))
            }
        } }
        rule.onNodeWithTag("turn-token").assertTextEquals("0")
        rule.runOnIdle { phase = "paused" }; rule.waitForIdle()
        rule.runOnIdle { phase = "work" }; rule.waitForIdle()
        rule.onNodeWithTag("turn-token").assertTextEquals("0")
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { identity = FocusTurnIdentity(10, 2) }
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("turn-token").assertTextEquals("1")
        assertTrue("New round did not move the instrument", (turn?.value ?: 0f) > 0f)
        rule.runOnUiThread { motion = false }
        rule.mainClock.advanceTimeBy(32)
        assertEquals(0f, turn!!.value, .001f)
        rule.mainClock.autoAdvance = true
    }

    @Test fun completedRowsMountStaticAndMotionOffNeverLeavesAnIntermediateStamp() {
        var event by mutableIntStateOf(0)
        var completed by mutableStateOf(true)
        var motion by mutableStateOf(true)
        var stamp: CompletionStamp? = null
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme { stamp = rememberCompletionStamp(event, completed, motion)
                Box(Modifier.size(80.dp).completionScale(stamp!!, motion).testTag("stamp")) }
        } }
        rule.runOnIdle { assertEquals(1f, stamp!!.stroke.value, .001f); assertEquals(1f, stamp!!.scale.value, .001f) }
        rule.runOnIdle { completed = false }; rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { completed = true; event++ }
        rule.mainClock.advanceTimeBy(64)
        assertTrue(stamp!!.stroke.value in 0f..0.99f)
        rule.runOnUiThread { motion = false }; rule.mainClock.advanceTimeBy(32)
        assertEquals(1f, stamp!!.stroke.value, .001f); assertEquals(1f, stamp!!.scale.value, .001f)
        rule.runOnUiThread { motion = true }; rule.mainClock.advanceTimeBy(48)
        assertEquals("Re-enabling motion must not replay a consumed operation", 1f, stamp!!.stroke.value, .001f)
        assertEquals(1f, stamp!!.scale.value, .001f)
        rule.mainClock.autoAdvance = true
    }

    @Test fun successShowsSavedProgressImmediatelyAndFailureDoesNotStamp() {
        val now = System.currentTimeMillis()
        val task = Store.obj("id", "stamp-task", "planId", "stamp-plan", "name", "完成反馈样例",
            "metric", "check", "target", 1, "manual", 0, "status", "open", "from", 0, "until", 1440,
            "start", Rules.at(Rules.date(now), 0), "due", now + 60 * Rules.MINUTE)
        var callbacks = 0
        rule.runOnUiThread {
            rule.activity.db.put("plans", Store.obj("id", "stamp-plan", "name", "完成反馈样例", "metric", "check",
                "target", 1, "cycle", "once", "created", now - Rules.MINUTE, "deadline", now + 60 * Rules.MINUTE,
                "from", 0, "until", 1440, "penalty", 0))
            rule.activity.db.put("instances", task)
            rule.activity.setContent { DisciplineTheme {
                val nav = androidx.navigation.compose.rememberNavController()
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    TaskCard(rule.activity, nav, task, true, onCompleted = { callbacks++ })
                }
            } }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("标记完成").performClick()
        rule.mainClock.advanceTimeBy(32)
        assertEquals("done", rule.activity.db.one("instances", "stamp-task")!!.optString("status"))
        assertEquals(1, callbacks)
        val info = rule.onNodeWithContentDescription("完成反馈样例完成进度", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, info.current, .001f)
        rule.onNode(isNotEnabled() and hasAnyDescendant(hasText("标记完成")), useUnmergedTree = true).assertExists()
        rule.mainClock.autoAdvance = true
        // A stale card whose task was removed must keep its uncompleted presentation.
        val missing = Store.copy(task).apply { put("id", "missing-task"); put("name", "失败反馈样例") }
        rule.runOnUiThread { rule.activity.setContent { DisciplineTheme {
            val nav = androidx.navigation.compose.rememberNavController()
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                TaskCard(rule.activity, nav, missing, true, onCompleted = { callbacks++ })
            }
        } } }
        rule.onNodeWithText("标记完成").performClick()
        assertEquals(1, callbacks)
        rule.onNodeWithText("已完成").assertDoesNotExist()
        rule.onNodeWithText("标记完成").assertIsEnabled()
    }
}
