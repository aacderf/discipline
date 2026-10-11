package com.hl.discipline

/** Capture each table once, then release the DB lock before report calculations. */
internal object StatisticsRepository {
    fun load(db: Store, range: Int, now: Long): StatisticsReport {
        val input = synchronized(db) {
            val sessions = db.all("sessions").map { row ->
                RecordedInterval(row.optString("kind"), row.optString("task"), row.optString("place"),
                    row.optLong("start"), row.optLong("end"))
            }.toMutableList()
            val location = db.one("state", "location")
            if (location?.optBoolean("reliable") == true) {
                val start = location.optLong("time")
                val end = minOf(now, location.optLong("validUntil", start + 120_000))
                if (end > start) sessions += RecordedInterval("presence", "", location.optString("place"), start, end)
            }
            StatisticsInput(sessions,
                db.all("instances").map { row ->
                    ReportTask(row.optString("id"), row.optString("planId"), row.optString("status"),
                        row.optString("metric"), row.optString("place"), row.optDouble("target", 1.0),
                        row.optDouble("manual"), row.optLong("start"), row.optLong("due"),
                        row.optInt("from", 0), row.optInt("until", 1440), Engine.days(row))
                }, db.all("plans").map { ReportPlan(it.optString("id"), it.optString("name"), it.optString("parent")) },
                db.all("places").map { ReportPlace(it.optString("id"), it.optString("name")) })
        }
        return StatisticsReport.calculate(input, range, now)
    }
}
