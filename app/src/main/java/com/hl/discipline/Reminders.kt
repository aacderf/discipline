package com.hl.discipline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** One-shot reminders are derived from saved plans and timetable rows. */
internal object Reminders {
    private fun mode(task: JSONObject, settings: JSONObject): String =
        task.optString("reminderMode", settings.optString("planReminderMode", "sound"))

    private fun start(task: JSONObject): Long {
        return when (task.optString("cycle", "once")) {
            "once" -> task.optLong("plannedStartAt")
            "daily" -> if (task.optInt("from", 0) > 0) task.optLong("start") else 0L
            else -> 0L
        }
    }

    private fun lead(task: JSONObject, settings: JSONObject, start: Boolean): Long =
        task.optInt(if (start) "startReminderMinutes" else "dueReminderMinutes",
            settings.optInt(if (start) "startReminderMinutes" else "dueReminderMinutes",
                if (start) 15 else 30)).coerceIn(0, 1440) * Rules.MINUTE

    private fun reminderTime(task: JSONObject, settings: JSONObject, start: Boolean): Long {
        val anchor = if (start) start(task) else task.optLong("due")
        if (anchor <= 0) return 0L
        return anchor - lead(task, settings, start)
    }

    private fun classStart(row: JSONObject, week: Int): Long {
        val time = Timetable.times(row).first
        if (time.isBlank()) return 0L
        val parsed = try { LocalTime.parse(time) } catch (_: Exception) { return 0L }
        return Rules.at(Timetable.date(week, row.optInt("day")), parsed.hour * 60 + parsed.minute)
    }

    private fun sent(state: JSONObject): Set<String> = state.optJSONArray("classNotified")?.let { array ->
        (0 until array.length()).map { array.optString(it) }.toSet()
    } ?: emptySet()

    private fun classKey(row: JSONObject, week: Int, start: Long) =
        "${row.optString("id")}:$week:$start"

    @JvmStatic fun next(context: Context, db: Store, now: Long): Long {
        val settings = db.settings()
        var next = Long.MAX_VALUE
        db.all("instances").forEach { task ->
            if (mode(task, settings) == "off") return@forEach
            listOf(true, false).forEach { isStart ->
                val at = reminderTime(task, settings, isStart)
                val anchor = if (isStart) start(task) else task.optLong("due")
                val key = if (isStart) "remindedStartFor" else "remindedDueFor"
                if (ReminderWindow.eligible(task.optString("status"), at, anchor, isStart) && task.optLong(key) != at)
                    next = minOf(next, ReminderWindow.next(at, anchor, now))
            }
        }
        val timetable = db.one("state", "timetable")
        if (timetable?.optBoolean("confirmed") == true &&
            settings.optString("classReminderMode", "sound") != "off") {
            val sent = sent(timetable)
            val currentWeek = Timetable.weekOf(Rules.date(now)).coerceIn(1, 18)
            for (week in currentWeek..minOf(18, currentWeek + 1)) {
                Timetable.rows(timetable).forEach { row ->
                    if (row.optBoolean("review") || !Timetable.inWeek(row, week)) return@forEach
                    val start = classStart(row, week)
                    val at = start - settings.optInt("classReminderMinutes", 10).coerceIn(0, 120) * Rules.MINUTE
                    if (start > 0 && classKey(row, week, start) !in sent)
                        next = minOf(next, ReminderWindow.next(at, start, now))
                }
            }
        }
        return next
    }

    @JvmStatic fun deliver(context: Context, db: Store, now: Long) = synchronized(db) {
        deliverLocked(context, db, now)
    }

    private fun deliverLocked(context: Context, db: Store, now: Long) {
        val settings = db.settings()
        db.all("instances").forEach { task ->
            if (mode(task, settings) == "off") return@forEach
            listOf(true, false).forEach { isStart ->
                val at = reminderTime(task, settings, isStart)
                val key = if (isStart) "remindedStartFor" else "remindedDueFor"
                val anchor = if (isStart) start(task) else task.optLong("due")
                if (ReminderWindow.eligible(task.optString("status"), at, anchor, isStart) &&
                    ReminderWindow.active(at, anchor, now) && task.optLong(key) != at) {
                    if (Scheduler.remind(context,
                        Math.abs((task.optString("id") + if (isStart) ":start" else ":due").hashCode()),
                        if (now >= anchor) if (isStart) "计划开始" else "计划已到期"
                            else if (isStart) "计划即将开始" else "计划即将截止",
                        task.optString("name"), false, mode(task, settings) == "sound")) {
                        Store.set(task, key, at)
                        db.put("instances", task)
                    }
                }
            }
        }
        val timetable = db.one("state", "timetable") ?: return
        if (!timetable.optBoolean("confirmed")) return
        val mode = settings.optString("classReminderMode", "sound")
        if (mode == "off") return
        val sent = sent(timetable).toMutableSet()
        val before = sent.size
        val currentWeek = Timetable.weekOf(Rules.date(now)).coerceIn(1, 18)
        for (week in currentWeek..minOf(18, currentWeek + 1)) Timetable.rows(timetable).forEach { row ->
            if (row.optBoolean("review") || !Timetable.inWeek(row, week)) return@forEach
            val start = classStart(row, week)
            val at = start - settings.optInt("classReminderMinutes", 10).coerceIn(0, 120) * Rules.MINUTE
            val key = classKey(row, week, start)
            if (ReminderWindow.active(at, start, now) && key !in sent) {
                if (Scheduler.remind(context, Math.abs(key.hashCode()), if (now >= start) "课程已开始" else "课程即将开始",
                    "${row.optString("title")} · ${row.optString("room")}", true, mode == "sound")) sent += key
            }
        }
        if (sent.size != before) {
            Store.set(timetable, "classNotified", JSONArray(sent.toList()))
            db.put("state", timetable)
        }
    }
}
