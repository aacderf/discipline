package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class LaunchNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun emulatorOnly() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
    }

    private fun frame(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val folder = File(rule.activity.filesDir, "launch-0.10.4").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun finiteSceneMovesAndRevealsLiveContentWithoutDuplicateClick() {
        var finished = 0
        var clicked = 0
        rule.mainClock.autoAdvance = false
        rule.activity.setContent {
            DisciplineTheme {
                CompositionLocalProvider(LocalMotionEnabled provides true) {
                    LaunchPresentation(true, true, null, 0f, { finished++ }) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Button({ clicked++ }, Modifier.padding(24.dp)) { Text("立即开始") }
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("launch-scene").assertExists()
        frame("scene-200ms")
        val early = rule.onRoot().captureToImage().asAndroidBitmap()
        rule.mainClock.advanceTimeBy(300)
        frame("scene-500ms")
        val middle = rule.onRoot().captureToImage().asAndroidBitmap()
        assertFalse("Scene must actually move", early.sameAs(middle))
        rule.mainClock.advanceTimeBy(250)
        frame("scene-750ms")
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        assertEquals(1, finished)
        rule.onNodeWithText("立即开始").performTouchInput { click() }
        assertEquals(1, clicked)
        frame("scene-finished")
        rule.mainClock.autoAdvance = true
    }

    @Test fun skipTouchEndsSceneWithoutClickingHiddenButton() {
        var finished = 0
        var clicked = 0
        rule.mainClock.autoAdvance = false
        rule.activity.setContent {
            DisciplineTheme {
                CompositionLocalProvider(LocalMotionEnabled provides true) {
                    LaunchPresentation(true, true, null, 0f, { finished++ }) {
                        Button({ clicked++ }, Modifier.fillMaxSize()) { Text("不会误触") }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("launch-scene").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        assertEquals(1, finished)
        assertEquals(0, clicked)
        rule.onNodeWithText("不会误触").performTouchInput { click() }
        assertEquals(1, clicked)
        rule.mainClock.autoAdvance = true
    }

    @Test fun reducedMotionShowsContentImmediately() {
        rule.activity.setContent {
            DisciplineTheme {
                CompositionLocalProvider(LocalMotionEnabled provides false) {
                    LaunchPresentation(true, false, null, 0f, {}) { Text("已经就绪") }
                }
            }
        }
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        rule.onNodeWithText("已经就绪").assertIsDisplayed()
    }

    @Test fun restoredActivityCannotReplayAnUnfinishedScene() {
        var enabled by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.activity.setContent {
            DisciplineTheme {
                CompositionLocalProvider(LocalMotionEnabled provides true) {
                    LaunchPresentation(enabled, true, null, 0f, {}) { Text("恢复的页面") }
                }
            }
        }
        rule.mainClock.advanceTimeBy(160)
        rule.onNodeWithTag("launch-scene").assertExists()
        rule.runOnUiThread { enabled = false }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        rule.onNodeWithText("恢复的页面").assertIsDisplayed()
        rule.mainClock.autoAdvance = true
    }

    @Test fun allActiveMarksAreSolidOnLightMintInBothThemes() {
        for (dark in listOf(false, true)) for (route in listOf("today", "timetable", "focus", "stats")) {
            rule.activity.setContent {
                DisciplineTheme(dark) {
                    Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(10.dp)) {
                        Box(Modifier.size(50.dp).background(AppColors.current.navigationFill).testTag("active")) {
                            NavigationMark(route, { 1f }, Modifier.size(23.dp).align(androidx.compose.ui.Alignment.Center))
                        }
                        Box(Modifier.size(50.dp).background(MaterialTheme.colorScheme.surface).testTag("inactive")) {
                            NavigationMark(route, { 0f }, Modifier.size(23.dp).align(androidx.compose.ui.Alignment.Center))
                        }
                    }
                }
            }
            val active = rule.onNodeWithTag("active").captureToImage().asAndroidBitmap()
            val inactive = rule.onNodeWithTag("inactive").captureToImage().asAndroidBitmap()
            val inverse = AppColors.palette(dark).navigationInk.toArgb()
            val outline = AppColors.scheme(dark).onSurfaceVariant.toArgb()
            fun pixels(image: Bitmap, color: Int): Int = (0 until image.width).sumOf { x ->
                (0 until image.height).count { y -> image.getPixel(x, y) == color }
            }
            assertTrue("Solid active $route/$dark must have visual weight", pixels(active, inverse) > pixels(inactive, outline))
            assertEquals(AppColors.palette(dark).navigationFill.toArgb(), active.getPixel(0, 0))
            frame("navigation-$route-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun navigationAndResumeDoNotReplayOpening() {
        rule.waitForIdle()
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        rule.onNode(hasContentDescription("课表")).performClick()
        rule.onNode(hasContentDescription("今天")).performClick()
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag("launch-scene").assertDoesNotExist()
        rule.onNode(hasContentDescription("今天")).assertIsSelected()
    }
}
