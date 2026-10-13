package com.hl.discipline

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FeatureLogicDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = Store.get(context)
    @Before fun setup() {
        check(context.packageName.endsWith(".qa") &&
            (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone")))
        listOf(LocationService::class.java,FocusCompanionService::class.java,FallbackBlockService::class.java)
            .forEach { context.stopService(Intent(context,it)) }
        db.atomic { Store.TABLES.forEach { db.writableDatabase.delete(it,null,null) } }
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @Test fun zeroLeadRemindersDeliverOnceWithoutReopeningSettledTask() {
        val now = System.currentTimeMillis(); val due = now - 1000
        db.put("instances",Store.obj("id","zero-due","name","到期计划","status","failed","due",due,
            "reminderMode","silent","dueReminderMinutes",0))
        Reminders.deliver(context,db,now)
        assertEquals(due,db.one("instances","zero-due")!!.optLong("remindedDueFor"))
        assertEquals("failed",db.one("instances","zero-due")!!.optString("status"))
        Reminders.deliver(context,db,now+1000)
        assertEquals(1,context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertEquals(Long.MAX_VALUE,Reminders.next(context,db,now+1000))
    }
    private fun row(id: String, day: Int, from: Int, weeks: List<Int>) = Store.obj("id",id,"title",id,"room","教室",
        "day",day,"from",from,"until",from+1,"weeks",JSONArray(weeks))
    @Test fun courseSaveUsesCurrentStateAndPreservesNotificationHistory() {
        db.put("state",Store.obj("id","timetable","confirmed",true,"meetings",JSONArray(listOf(row("first",1,1,listOf(1,2))))))
        val current = Timetable.state(db)
        Store.set(current,"classNotified",JSONArray(listOf("sent-while-editor-open"))); db.put("state",current)
        Timetable.save(db,row("first",2,3,listOf(1,2)),requireExisting=true)
        assertEquals("sent-while-editor-open",Timetable.state(db).getJSONArray("classNotified").getString(0))
        assertTrue(Timetable.state(db).optBoolean("confirmed"))
        Timetable.remove(db,"first",1)
        assertTrue(Timetable.inWeek(Timetable.rows(Timetable.state(db)).single(),2))
        assertFalse(Timetable.inWeek(Timetable.rows(Timetable.state(db)).single(),1))
    }
    @Test fun courseSaveRejectsConflictAndDoesNotResurrectRemovedSeries() {
        db.put("state",Store.obj("id","timetable","meetings",JSONArray(listOf(row("first",1,1,listOf(1,2))))))
        assertThrows(IllegalArgumentException::class.java) { Timetable.save(db,row("second",1,2,listOf(2))) }
        assertEquals(1,Timetable.rows(Timetable.state(db)).size)
        Timetable.remove(db,"first")
        assertThrows(IllegalArgumentException::class.java) { Timetable.save(db,row("first",2,3,listOf(1)),requireExisting=true) }
        assertTrue(Timetable.rows(Timetable.state(db)).isEmpty())
    }
    @Test fun importAndConfirmationPreserveFreshCourseAndReminderState() {
        db.put("state",Store.obj("id","timetable","confirmed",false,
            "meetings",JSONArray(listOf(row("first",1,1,listOf(1,2))))))
        val current = Timetable.state(db)
        Store.set(current,"classNotified",JSONArray(listOf("already-sent"))); db.put("state",current)
        assertEquals(1,Timetable.importCourses(db,listOf(row("conflict",1,2,listOf(2)),row("next",2,3,listOf(1,2)))))
        Timetable.confirm(db)
        val saved = Timetable.state(db)
        assertEquals(2,Timetable.rows(saved).size); assertTrue(saved.optBoolean("confirmed"))
        assertEquals("already-sent",saved.getJSONArray("classNotified").getString(0))
        assertThrows(IllegalArgumentException::class.java) { Timetable.importCourses(db,listOf(row("conflict",1,2,listOf(2)))) }
        assertEquals(2,Timetable.rows(Timetable.state(db)).size)
    }
    @Test fun pausedRestResumesOutsidePlaceButWorkStillRequiresArrival() {
        val engine = Engine(context)
        engine.startFocus("","",1,false,JSONArray())
        val focus = db.one("state","focus")!!
        Store.set(focus,"place","required-place"); Store.set(focus,"phase","paused")
        Store.set(focus,"pausedPhase","break"); Store.set(focus,"remaining",30_000L)
        db.put("state",focus)
        engine.resumeFocus()
        assertEquals("break",db.one("state","focus")!!.optString("phase"))
        assertEquals(30_000L,db.one("state","focus")!!.optLong("remaining"))
        val pausedWork = db.one("state","focus")!!
        Store.set(pausedWork,"phase","paused"); Store.set(pausedWork,"pausedPhase","work")
        db.put("state",pausedWork)
        assertThrows(IllegalStateException::class.java) { engine.resumeFocus() }
        assertEquals("paused",db.one("state","focus")!!.optString("phase"))
    }
    @Test fun focusRejectsStaleCompletedFutureAndExpiredTasks() {
        val engine = Engine(context); val now = System.currentTimeMillis()
        for (task in listOf(
            Store.obj("id","done","status","done","start",now-60_000,"due",now+60_000),
            Store.obj("id","future","status","open","start",now+60_000,"due",now+120_000),
            Store.obj("id","expired","status","open","start",now-120_000,"due",now-60_000))) {
            db.put("instances",task)
            assertThrows(IllegalStateException::class.java) { engine.startFocus(task.optString("id"),"",1,false,JSONArray()) }
            assertNull(db.one("state","focus"))
        }
        assertThrows(IllegalStateException::class.java) { engine.startFocus("removed","",1,false,JSONArray()) }
        db.put("instances",Store.obj("id","valid","status","open","start",now-60_000,"due",now+60_000))
        engine.startFocus("valid","",1,false,JSONArray())
        assertEquals("valid",db.one("state","focus")!!.optString("task"))
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    fun deniedNotificationsDoNotScheduleOneSecondRetries() {
        // Revoke permission before instrumentation; revoking inside a test can kill its process.
        org.junit.Assume.assumeFalse(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        val now = System.currentTimeMillis(); val due = now - 1000
        db.put("instances",Store.obj("id","permission-due","status","failed","due",due,
            "reminderMode","silent","dueReminderMinutes",0))
        assertEquals(Long.MAX_VALUE,Reminders.next(context,db,now))
        Reminders.deliver(context,db,now)
        assertFalse(db.one("instances","permission-due")!!.has("remindedDueFor"))
    }
}
