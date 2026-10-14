package com.hl.discipline

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PhysicsInteractionDeviceTest {
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
    @Test fun controlsGiveOneHapticPerInputAndNoneOnExternalStateUpdates() {
        val events = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
        }
        var checked by mutableStateOf(false)
        var selected by mutableIntStateOf(0)
        rule.runOnUiThread { rule.activity.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false, LocalHapticFeedback provides feedback) {
                DisciplineTheme {
                    Column {
                        DraggableSegment(listOf("甲", "乙"), selected, false) { selected = it }
                        GlassSwitch(checked, { checked = it }, Modifier.testTag("switch"))
                        GlassCheckbox(checked, { checked = it }, Modifier.testTag("checkbox"))
                    }
                }
            }
        } }
        rule.onNodeWithText("乙").performClick()
        rule.onNodeWithTag("switch").performClick()
        rule.onNodeWithTag("checkbox").performClick()
        rule.runOnIdle { assertEquals(listOf(HapticFeedbackType.TextHandleMove, HapticFeedbackType.TextHandleMove,
            HapticFeedbackType.TextHandleMove), events); checked = true }
        rule.waitForIdle()
        assertEquals(3, events.size)
    }
    @Test fun focusFooterIsFullyAboveActionsAndRapidExpansionKeepsActionsInPlace() {
        rule.runOnUiThread { rule.activity.selectedTab = "focus" }
        val actions = rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot()
        val footer = rule.onNodeWithTag("focus-preferences").getUnclippedBoundsInRoot()
        assertTrue("Configuration footer extends under the fixed actions", footer.bottom <= actions.top)
        rule.onNodeWithContentDescription("启用悬浮球", useUnmergedTree = true).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        repeat(3) {
            rule.onNodeWithTag("focus-preferences").performClick()
            rule.mainClock.advanceTimeBy(48)
        }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(actions, rule.onNodeWithTag("focus-actions").getUnclippedBoundsInRoot())
        rule.mainClock.autoAdvance = true
    }
    @Test fun disabledMotionShowsFinalNumbersWithoutOldSemanticCopies() {
        var millis by mutableLongStateOf(25 * Rules.MINUTE)
        rule.runOnUiThread { rule.activity.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                DisciplineTheme { AnimatedStatDuration(millis, false, "专注时长") }
            }
        } }
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { millis = 90 * Rules.MINUTE }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("专注时长 ${duration(90 * Rules.MINUTE)}").assertExists()
        rule.onNodeWithContentDescription("专注时长 ${duration(25 * Rules.MINUTE)}").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
    }
}
