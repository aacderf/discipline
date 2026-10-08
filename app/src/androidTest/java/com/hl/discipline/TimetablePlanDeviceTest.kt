package com.hl.discipline

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetablePlanDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = Store.get(context)

    @Before fun resetEmulatorFixture() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone")) {
            "Only run destructive fixture tests on the emulator"
        }
        db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it, null, null) } }
    }

    @Test fun timetableWeeksAndBackupValidation() {
        val state = Timetable.state(db)
        assertFalse(state.optBoolean("confirmed"))
        assertEquals(1, Timetable.weekOf(Timetable.termStart))
        assertEquals(14, Timetable.weekOf(Timetable.date(14, 3)))
        assertTrue(Timetable.forDay(state, 1, 1).any { it.optString("title") == "示例课程 A" && it.optInt("until") == 2 })
        assertTrue(Timetable.forDay(state, 2, 1).any { it.optInt("from") == 5 && it.optString("title") == "示例课程 B" })
        assertTrue(Timetable.forDay(state, 14, 1).any { it.optString("title").equals("示例调课") })
        assertTrue(Timetable.forDay(state, 16, 2).any { it.optString("title") == "示例课程 C" })
        assertTrue(Timetable.forDay(state, 17, 2).none { it.optString("title") in listOf("示例课程 C") })
        assertTrue(Timetable.rows(state).none { row ->
            row.optString("title") in listOf("示例课程 C") &&
                Timetable.inWeek(row, 18)
        })
        val backup = db.prepareImport(db.exportJson())
        val timetable = backup.getJSONArray("state").let { states ->
            (0 until states.length()).mapNotNull { states.optJSONObject(it) }.first { it.optString("id") == "timetable" }
        }
        timetable.getJSONArray("meetings").getJSONObject(0).put("weeks", JSONArray(listOf(19)))
        assertThrows(IllegalArgumentException::class.java) { db.prepareImport(backup.toString()) }
    }

    @Test fun deletingPlanKeepsChildrenAndSharedPresence() {
        val now = System.currentTimeMillis()
        db.put("plans", Store.obj("id", "parent", "name", "父计划", "metric", "check", "target", 1,
            "cycle", "once", "deadline", now + 3_600_000, "from", 0, "until", 1440))
        db.put("plans", Store.obj("id", "child", "name", "子计划", "parent", "parent", "metric", "check",
            "target", 1, "cycle", "once", "deadline", now + 3_600_000, "from", 0, "until", 1440))
        db.put("instances", Store.obj("id", "parent:1", "planId", "parent", "status", "done"))
        db.put("sessions", Store.obj("id", "focus:1", "kind", "focus", "task", "parent:1",
            "start", now - 60_000, "end", now))
        db.put("sessions", Store.obj("id", "place:1", "kind", "presence", "task", "",
            "start", now - 60_000, "end", now))
        db.put("penalties", Store.obj("id", "penalty:1", "task", "parent:1", "start", now,
            "end", now + 60_000))
        PlanDeletion.delete(context, "parent")
        assertNull(db.one("plans", "parent"))
        assertEquals("", db.one("plans", "child")!!.optString("parent"))
        assertNull(db.one("instances", "parent:1"))
        assertNull(db.one("sessions", "focus:1"))
        assertNotNull(db.one("sessions", "place:1"))
        assertNull(db.one("penalties", "penalty:1"))
    }

    @Test fun remindersUseStartAndDueOffsets() {
        val now = System.currentTimeMillis()
        val start = now + 60 * Rules.MINUTE
        db.put("instances", Store.obj("id", "one", "status", "open", "cycle", "once",
            "name", "测试", "start", now, "plannedStartAt", start,
            "due", now + 90 * Rules.MINUTE, "reminderMode", "sound",
            "startReminderMinutes", 15, "dueReminderMinutes", 30))
        val expected = start - 15 * Rules.MINUTE
        assertEquals(expected, Reminders.next(context, db, now))
    }
}
