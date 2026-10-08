package com.hl.discipline

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class FeedbackRefinementDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
            rule.activity.refresh()
        }
        rule.waitForIdle()
    }

    private fun frame(name: String) {
        val folder = File(rule.activity.getExternalFilesDir(null),
            "refinement-0.10.8/${rule.activity.resources.configuration.uiMode}-${rule.activity.resources.configuration.fontScale}")
            .apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun planExpansionCanReverseAndPinningDoesNotChangeThePlanRules() {
        rule.runOnUiThread {
            val now = System.currentTimeMillis()
            for (i in 1..2) rule.activity.db.put("plans", Store.obj("id", "polish-$i",
                "name", "计划$i", "metric", "check", "target", 1, "cycle", "once",
                "deadline", now + 3_600_000, "from", 0, "until", 1440, "penalty", 0))
            rule.activity.engine.tick()
            rule.activity.refresh()
        }
        frame("today-with-tasks")
        rule.onNodeWithText("管理计划").performClick()
        rule.onNodeWithText("计划1").performClick()
        rule.onNodeWithText("编辑计划").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("置顶计划").performScrollTo().performClick()
        rule.runOnUiThread {
            val plan = rule.activity.db.one("plans", "polish-1")!!
            assertTrue(plan.optBoolean("pinned"))
            assertEquals(1.0, plan.optDouble("target"), 0.0)
            assertEquals("once", plan.optString("cycle"))
        }
        frame("plan-pinned")
        rule.onNodeWithText("计划1").performScrollTo()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("计划1").performClick()
        rule.mainClock.advanceTimeBy(64)
        frame("plan-collapse-middle")
        if (rule.activity.motionEnabled) {
            val outgoing = rule.onNodeWithText("编辑计划", useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(generateSequence(outgoing) { it.parent }.any {
                it.config.contains(SemanticsProperties.HideFromAccessibility)
            })
        } else rule.onNodeWithText("编辑计划").assertDoesNotExist()
        // Reverse mid-collapse: no vanished actions or stale hidden subtree.
        rule.onNodeWithText("计划1").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        frame("plan-reverse")
        android.util.Log.i("Refinement", rule.onRoot(useUnmergedTree = true).printToString())
        rule.onNodeWithText("编辑计划").performScrollTo().assertIsDisplayed()
    }
}
