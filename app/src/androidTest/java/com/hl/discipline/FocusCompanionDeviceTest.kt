package com.hl.discipline

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusCompanionDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = Store.get(context)
    private val engine get() = Engine(context)
    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
    }
    private fun elapsed(phase: String) {
        engine.startFocus("", "", 1, false, JSONArray(), 1, 2, 4)
        val focus = db.one("state", "focus")
        Store.set(focus, "phase", phase)
        Store.set(focus, "remaining", 500)
        Store.set(focus, "checkpoint", System.currentTimeMillis() - 1000)
        Store.set(focus, "elapsed", SystemClock.elapsedRealtime() - 1000)
        db.put("state", focus)
    }
    @Test fun breakAutomaticallyStartsNextWorkOnce() {
        elapsed("break"); engine.tick()
        val focus = db.one("state", "focus")
        assertEquals("work", focus.optString("phase"))
        assertEquals(2, focus.optInt("cycle"))
        assertEquals(60_000, focus.optLong("remaining"))
        assertEquals(0, focus.optLong("total"))
        val event = db.one("state", "focusEvent")
        assertEquals("rest-ended", event.optString("kind"))
        engine.tick()
        assertEquals(event.optString("token"), db.one("state", "focusEvent").optString("token"))
    }
    @Test fun workEndsIntoBreakAndPublishesOneEvent() {
        elapsed("work"); engine.tick()
        assertEquals("break", db.one("state", "focus").optString("phase"))
        assertEquals(500, db.one("state", "focus").optLong("total"))
        val event = db.one("state", "focusEvent")
        assertEquals("work-ended", event.optString("kind"))
        engine.tick()
        assertEquals(event.optString("token"), db.one("state", "focusEvent").optString("token"))
        FocusEvents.acknowledge(context, event.optString("token"))
        assertTrue(db.one("state", "focusEvent").optBoolean("acknowledged"))
    }
    @Test fun missingPlacePausesAutomaticNextRound() {
        elapsed("break")
        val focus = db.one("state", "focus")
        Store.set(focus, "place", "library")
        db.put("state", focus); engine.tick()
        val next = db.one("state", "focus")
        assertEquals("paused", next.optString("phase"))
        assertEquals("work", next.optString("pausedPhase"))
        assertEquals(2, next.optInt("cycle"))
        assertEquals(60_000, next.optLong("remaining"))
        assertEquals(0, db.all("sessions").size)
    }
    @Test fun pausedRestDoesNotAdvanceOrEmitEvent() {
        elapsed("break")
        val focus = db.one("state", "focus")
        Store.set(focus, "phase", "paused"); Store.set(focus, "pausedPhase", "break")
        db.put("state", focus); engine.tick()
        assertEquals("paused", db.one("state", "focus").optString("phase"))
        assertEquals(500, db.one("state", "focus").optLong("remaining"))
        assertNull(db.one("state", "focusEvent"))
    }
    @Test fun clockMismatchRetainsRestPhaseForResume() {
        elapsed("break")
        val focus = db.one("state", "focus")
        Store.set(focus, "boot", -999); db.put("state", focus); engine.tick()
        assertEquals("paused", db.one("state", "focus").optString("phase"))
        assertEquals("break", db.one("state", "focus").optString("pausedPhase"))
        engine.resumeFocus()
        assertEquals("break", db.one("state", "focus").optString("phase"))
    }
}
