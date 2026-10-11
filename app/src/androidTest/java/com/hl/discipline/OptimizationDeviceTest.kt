package com.hl.discipline

import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*

class OptimizationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Before fun fixture() {
        check(rule.activity.packageName.endsWith(".qa"))
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        rule.runOnUiThread {
            for (service in listOf(LocationService::class.java,FocusCompanionService::class.java,FallbackBlockService::class.java))
                rule.activity.stopService(Intent(rule.activity,service))
            val db = rule.activity.db
            db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it,null,null) } }
            rule.activity.launchIntro = false; rule.activity.motionEnabled = false
            rule.activity.refresh()
        }
    }

    @Test fun documentWorkIsOffMainAndNavigationRemainsResponsive() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var result = ""
        try {
            rule.runOnUiThread {
                assertTrue(rule.activity.runDataOperation("测试文件读写", {
                    assertNotEquals(Looper.getMainLooper(),Looper.myLooper())
                    entered.countDown()
                    check(release.await(10,TimeUnit.SECONDS))
                    "完成"
                }) { assertEquals(Looper.getMainLooper(),Looper.myLooper()); result = it })
                assertFalse(rule.activity.runDataOperation("重复操作", { error("不应执行") }) { })
            }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            rule.onNode(hasContentDescription("专注") and hasClickAction()).performClick()
            rule.onNodeWithTag("focus-app-limits").assertIsDisplayed()
            rule.onNodeWithTag("data-operation").assertIsDisplayed()
        } finally { release.countDown() }
        rule.waitUntil(10_000) { rule.activity.dataOperation.isEmpty() }
        assertEquals("完成",result)
    }

    @Test fun failedDocumentOperationClearsBusyAndPreservesData() {
        rule.runOnUiThread {
            rule.activity.db.put("plans",Store.obj("id","preserved","name","原有计划"))
            rule.activity.runDataOperation("测试失败处理", { error("无法读取文件") }) { fail("不应发布成功") }
        }
        rule.waitUntil(10_000) { rule.activity.dataOperation.isEmpty() }
        assertEquals("原有计划",rule.activity.db.one("plans","preserved").optString("name"))
    }

    @Test fun concurrentStartsHaveOneOwnerAndFocusResumeKeepsConfiguration() {
        val accepted = AtomicInteger(); val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map {
                pool.submit {
                    gate.await()
                    try {
                        rule.activity.engine.startFocus("","",50,false,JSONArray(),10,20,3)
                        accepted.incrementAndGet()
                    } catch (_: IllegalStateException) { }
                }
            }
            gate.countDown(); futures.forEach { it.get(15,TimeUnit.SECONDS) }
            assertEquals(1,accepted.get())
            rule.activity.engine.pauseFocus()
            val paused = rule.activity.db.one("state","focus")
            rule.activity.engine.resumeFocus()
            val resumed = rule.activity.db.one("state","focus")
            assertEquals("work",resumed.optString("phase"))
            assertEquals(paused.optLong("remaining"),resumed.optLong("remaining"))
            assertEquals(10,resumed.optInt("shortBreak")); assertEquals(20,resumed.optInt("longBreak"))
            assertEquals(3,resumed.optInt("cycles"))
            rule.activity.engine.endFocus(); rule.activity.engine.resumeFocus()
            assertNull(rule.activity.db.one("state","focus"))
        } finally { pool.shutdownNow() }
    }

    @Test fun reportShowsVerifiedRateAndContinuousSegmentsInBothThemes() {
        rule.runOnUiThread {
            val now = System.currentTimeMillis()
            val task = Store.obj("id","done-task","planId","plan","name","报告测试","metric","check","manual",1,
                "target",1,"start",now-10*Rules.MINUTE,"due",now-1,"status","done")
            rule.activity.db.put("instances",task)
            val review = Store.copy(task); Store.set(review,"id","review-task"); Store.set(review,"status","review")
            Store.set(review,"manual",0); rule.activity.db.put("instances",review)
            rule.activity.db.put("plans",Store.obj("id","plan","name","阅读","created",now,"deadline",now+Rules.MINUTE))
            rule.activity.db.put("sessions",Store.obj("id","a","kind","focus","task","done-task","start",now-5*Rules.MINUTE,"end",now-2*Rules.MINUTE))
            rule.activity.db.put("sessions",Store.obj("id","b","kind","focus","task","done-task","start",now-2*Rules.MINUTE,"end",now))
            rule.activity.refresh()
        }
        rule.onNode(hasContentDescription("统计") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("有效专注段").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("100%").assertIsDisplayed()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.takeScreenshot(File(rule.activity.filesDir,"report-light-0.11.7.png")))
        rule.runOnUiThread {
            val settings = rule.activity.db.settings(); Store.set(settings,"themeMode","dark")
            rule.activity.db.put("state",settings); rule.activity.refresh()
        }
        rule.waitForIdle()
        rule.onNodeWithText("100%").assertIsDisplayed()
        assertTrue(device.takeScreenshot(File(rule.activity.filesDir,"report-dark-0.11.7.png")))
    }
}
