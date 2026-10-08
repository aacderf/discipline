package com.hl.discipline

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class DarkTransitionDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()
    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            db.put("state", db.settings().put("themeMode", "dark"))
            val now = System.currentTimeMillis()
            db.put("plans", Store.obj("id", "dark-transition", "name", "复习高数", "metric", "presence",
                "target", 2400, "place", "library", "cycle", "once", "deadline", now + 3_600_000,
                "created", now - 1000, "from", 0, "until", 1440, "penalty", 0))
            rule.activity.engine.tick()
            rule.activity.launchIntro = false
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }
    private fun checkFrames(prefix: String) {
        repeat(10) { frame ->
            rule.mainClock.advanceTimeBy(32)
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            for (y in listOf(bitmap.height / 3, bitmap.height / 2, bitmap.height * 2 / 3)) {
                val pixel = bitmap.getPixel(2, y)
                assertTrue("$prefix frame $frame exposed a pale background", Color.red(pixel) < 60 && Color.green(pixel) < 60)
            }
            if (frame == 2 || frame == 6) {
                val file = File(rule.activity.getExternalFilesDir(null), "dark-transition-0.11.2/$prefix-$frame.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    @Test fun sharedDetailAndOrdinaryRoutesNeverRevealLightWindowBackground() {
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("复习高数").performClick()
        checkFrames("into-detail")
        rule.onNodeWithText("任务详情").assertExists()
        rule.onNode(hasContentDescription("返回") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        checkFrames("return-today")
        rule.onNode(hasContentDescription("设置") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        checkFrames("into-settings")
        rule.onNode(hasContentDescription("返回") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        checkFrames("return-settings")
        rule.mainClock.autoAdvance = true
    }
}
