package com.hl.discipline

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PressPerformanceDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Test fun pressedColorAndScaleDoNotRecomposeTheClickableContentEachFrame() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        val compositions = AtomicInteger()
        var clicks = 0
        rule.mainClock.autoAdvance = false
        rule.activity.setContent {
            DisciplineTheme {
                CompositionLocalProvider(LocalMotionEnabled provides true) {
                    PressProbe(compositions) { clicks++ }
                }
            }
        }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("press-probe").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(32)
        val afterPressStateChange = compositions.get()
        rule.mainClock.advanceTimeBy(160)
        assertEquals("Press frames must only change the drawing layer", afterPressStateChange, compositions.get())
        rule.onNodeWithTag("press-probe").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(32)
        val afterReleaseStateChange = compositions.get()
        rule.mainClock.advanceTimeBy(400)
        assertEquals("Release frames must not rebuild the content", afterReleaseStateChange, compositions.get())
        assertEquals(1, clicks)
        rule.mainClock.autoAdvance = true
    }
}

@Composable
private fun PressProbe(compositions: AtomicInteger, onClick: () -> Unit) {
    SideEffect { compositions.incrementAndGet() }
    Box(Modifier.size(120.dp).testTag("press-probe").quietClickable("按压测试", onClick = onClick)) {
        Text("按压")
    }
}
