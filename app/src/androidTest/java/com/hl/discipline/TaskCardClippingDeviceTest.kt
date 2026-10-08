package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TaskCardClippingDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Test fun collapsedEmbeddedTrackHasTwoIntactEnds() {
        assertTrue(rule.activity.packageName.endsWith(".qa"))
        assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        var fill = 0; var track = 0
        val now = System.currentTimeMillis()
        val task = Store.obj("id", "clip-test", "planId", "clip-test", "name", "停留测试",
            "metric", "presence", "target", 60, "manual", 20, "status", "open",
            "start", now - 1000, "due", now + Rules.MINUTE, "from", 0, "until", 1440)
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme {
                fill = AppColors.current.chartFocus.toArgb()
                track = MaterialTheme.colorScheme.secondaryContainer.toArgb()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
                    val nav = rememberNavController()
                    GlassTaskPanel { TaskCard(rule.activity, nav, task, true, embedded = true) }
                }
            }
        } }
        rule.onNodeWithContentDescription("收起任务", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        val bounds = rule.onNodeWithContentDescription("停留测试完成进度", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val frame = rule.onRoot().captureToImage().asAndroidBitmap()
        val y = bounds.center.y.toInt()
        fun close(expected: Int, actual: Int) {
            for (shift in listOf(0, 8, 16)) assertTrue("Progress end was clipped",
                kotlin.math.abs((expected shr shift and 255) - (actual shr shift and 255)) <= 22)
        }
        close(fill, frame.getPixel(bounds.left.toInt() + 1, y))
        close(track, frame.getPixel(bounds.right.toInt() - 2, y))
        val folder = File(rule.activity.getExternalFilesDir(null), "location-proof-0.11.4").apply { mkdirs() }
        File(folder, "collapsed-track.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
