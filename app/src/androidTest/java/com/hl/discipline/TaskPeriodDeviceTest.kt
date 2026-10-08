package com.hl.discipline

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class TaskPeriodDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = Store.get(context)
    private val engine get() = Engine(context)
    private fun at(day: Int, minute: Int = 0) = Rules.at(LocalDate.of(2026, 10, day), minute)
    private val created = at(1, 9 * 60)
    private val due = at(8)
    private fun plan(metric: String = "presence") = Store.obj("id", "math", "name", "复习高数",
        "created", created, "deadline", due, "cycle", "once", "metric", metric,
        "target", 2400, "place", "library", "from", 0, "until", 1440)

    @Before fun fixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
    }

    @Test fun newCrossDayTaskIsVisibleTodayAndInBothOverlappingCalendarWeeks() {
        db.put("plans", plan())
        engine.generate(created)
        val task = db.all("instances").single()
        assertEquals(created, task.optLong("start"))
        assertEquals(task.optString("id"), engine.current(at(1, 10 * 60)).single().optString("id"))
        for (monday in listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5))) {
            assertTrue(task.optLong("start") < Rules.at(monday.plusDays(7), 0))
            assertTrue(task.optLong("due") > Rules.at(monday, 0))
        }
        assertTrue(engine.current(at(8)).isEmpty())
        engine.segment("presence", "library", "", at(1, 10 * 60), at(1, 11 * 60 + 30))
        engine.segment("presence", "library", "", at(2, 9 * 60), at(2, 10 * 60))
        assertEquals(150.0, engine.progress(task, at(2, 12 * 60)), .001)
    }

    @Test fun oldOpenSnapshotIsRepairedWithoutChangingIdentityManualCreditOrSessions() {
        val p = plan("placeFocus")
        db.put("plans", p)
        val id = "math:$due"
        val task = Store.copy(p).put("id", id).put("planId", "math").put("due", due)
            .put("start", at(7)).put("status", "open").put("manual", 10)
        db.put("instances", task)
        engine.segment("focus", "", id, at(1, 10 * 60), at(1, 10 * 60 + 30))
        engine.segment("presence", "library", "", at(1, 9 * 60), at(1, 12 * 60))
        db.put("plans", p.put("name", "后续配置").put("created", at(2)))
        engine.generate(created)
        engine.generate(created)
        val repaired = db.all("instances").single()
        assertEquals(id, repaired.optString("id"))
        assertEquals(created, repaired.optLong("start"))
        assertEquals("复习高数", repaired.optString("name"))
        assertEquals(10, repaired.optInt("manual"))
        assertEquals(2, db.all("sessions").size)
        assertEquals(40.0, engine.progress(repaired, at(1, 12 * 60)), .001)
    }

    @Test fun settledExpiredAndExplicitlyDifferentWindowsStayUnchanged() {
        for ((index, state) in listOf("done", "failed", "review", "leave").withIndex()) {
            db.put("instances", Store.copy(plan()).put("id", "closed-$index").put("planId", "math")
                .put("due", due).put("start", at(7)).put("status", state))
        }
        db.put("instances", Store.copy(plan()).put("id", "expired").put("due", at(2))
            .put("start", at(1)).put("status", "open"))
        db.put("instances", Store.copy(plan()).put("id", "custom").put("due", due)
            .put("start", at(3)).put("status", "open"))
        engine.generate(at(3, 9 * 60))
        for (index in 0..3) assertEquals(at(7), db.one("instances", "closed-$index")!!.optLong("start"))
        assertEquals(at(1), db.one("instances", "expired")!!.optLong("start"))
        assertEquals(at(3), db.one("instances", "custom")!!.optLong("start"))
    }

    @Test fun crossDayCreditStillRespectsDailyWindowAndAllowedWeekdays() {
        val p = plan().put("from", 10 * 60).put("until", 11 * 60)
            .put("days", org.json.JSONArray(listOf(4))) // Thursday, Oct 1.
        db.put("plans", p)
        engine.generate(created)
        engine.segment("presence", "library", "", at(1, 9 * 60), at(2, 12 * 60))
        assertEquals(60.0, engine.progress(db.all("instances").single(), at(2, 12 * 60)), .001)
    }
}
