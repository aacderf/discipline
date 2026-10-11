package com.hl.discipline

import java.time.LocalDate

internal data class RecordedInterval(val kind: String, val task: String, val place: String, val start: Long, val end: Long)
internal data class ReportTask(val id: String, val planId: String, val status: String, val metric: String, val place: String,
    val target: Double, val manual: Double, val start: Long, val due: Long, val from: Int, val until: Int, val days: Set<Int>)
internal data class ReportPlan(val id: String, val title: String, val parent: String = "")
internal data class ReportPlace(val id: String, val title: String)
internal data class StatisticsInput(val sessions: List<RecordedInterval>, val tasks: List<ReportTask>,
    val plans: List<ReportPlan>, val places: List<ReportPlace>)
internal data class ChartRow(val label: String, val focus: Long)
internal data class DistributionRow(val id: String, val title: String, val millis: Long)
internal data class StatisticsReport(val range: Int, val start: Long, val now: Long,
    val focus: Long, val presence: Long, val focusSegments: Int,
    val ended: Int, val verified: Int, val done: Int, val failed: Int, val review: Int,
    val rows: List<ChartRow>, val goals: List<DistributionRow>, val places: List<DistributionRow>) {
    val empty get() = focus == 0L && presence == 0L && ended == 0

    companion object {
        /** Pure report calculation. No DB reads, writes, services or UI callbacks. */
        fun calculate(input: StatisticsInput, range: Int, now: Long): StatisticsReport {
            val day = Rules.date(now)
            val first = when (range) { 1 -> day; 7 -> Rules.monday(day); else -> day.withDayOfMonth(1) }
            val start = Rules.at(first, 0)
            val window = listOf(longArrayOf(start, now))
            fun ranges(rows: List<RecordedInterval>) = rows.map { longArrayOf(it.start, it.end) }
            val focusRecords = input.sessions.filter { it.kind == "focus" }
            val presenceRecords = input.sessions.filter { it.kind == "presence" }
            val focusRanges = ranges(focusRecords)
            val presenceRanges = ranges(presenceRecords)
            val clippedFocus = Rules.intersect(focusRanges, window)
            val focus = Rules.union(clippedFocus)
            val taskFocus = focusRecords.groupBy { it.task }
            val placePresence = presenceRecords.groupBy { it.place }
            val parents = input.plans.map { it.parent }.filter { it.isNotEmpty() }.toSet()
            val ended = input.tasks.filter { it.due in start..now && it.planId !in parents && it.status != "leave" }
            val verified = ended.filter { it.status != "review" }
            fun completed(task: ReportTask): Boolean {
                if (task.status == "done") return true
                if (task.metric == "check" || task.metric == "count") return task.manual >= task.target
                var intervals = if (task.metric == "presence") ranges(placePresence[task.place].orEmpty())
                    else ranges(taskFocus[task.id].orEmpty())
                if (task.metric == "placeFocus")
                    intervals = Rules.intersect(intervals, ranges(placePresence[task.place].orEmpty()))
                val valid = Rules.windows(task.start, minOf(task.due, now), task.from, task.until, task.days)
                return task.manual + Rules.union(Rules.intersect(intervals, valid)).toDouble() / Rules.MINUTE >= task.target
            }
            val taskPlan = input.tasks.associate { it.id to it.planId }
            val byPlan = focusRecords.groupBy { taskPlan[it.task] }
            val goals = input.plans.map { plan ->
                DistributionRow(plan.id, plan.title, Rules.union(Rules.intersect(ranges(byPlan[plan.id].orEmpty()), window)))
            }.filter { it.millis > 0 }.sortedByDescending { it.millis }
            val places = input.places.map { place ->
                DistributionRow(place.id, place.title, Rules.union(Rules.intersect(ranges(placePresence[place.id].orEmpty()), window)))
            }.filter { it.millis > 0 }.sortedByDescending { it.millis }
            return StatisticsReport(range, start, now, focus,
                Rules.union(Rules.intersect(presenceRanges, window)), countSegments(clippedFocus),
                ended.size, verified.size, verified.count(::completed),
                verified.count { it.status == "failed" }, ended.count { it.status == "review" },
                chartRows(range, first, now, focusRanges), goals, places)
        }

        private fun countSegments(intervals: List<LongArray>): Int {
            var end = Long.MIN_VALUE; var count = 0
            intervals.filter { it[1] > it[0] }.sortedBy { it[0] }.forEach {
                if (it[0] > end) count++
                end = maxOf(end, it[1])
            }
            return count
        }

        private fun chartRows(range: Int, first: LocalDate, now: Long, focus: List<LongArray>): List<ChartRow> {
            val count = when (range) { 1 -> 6; 7 -> 7; else -> first.lengthOfMonth() }
            return (0 until count).map { index ->
                val date = first.plusDays(index.toLong())
                val from = if (range == 1) Rules.at(first, index * 240) else Rules.at(date, 0)
                val to = minOf(now, if (range == 1) Rules.at(first, (index + 1) * 240) else Rules.at(date.plusDays(1), 0))
                val label = if (range == 1) "%02d–%02d时".format(index * 4, (index + 1) * 4) else "${date.monthValue}/${date.dayOfMonth}"
                ChartRow(label, if (to <= from) 0 else Rules.union(Rules.intersect(focus, listOf(longArrayOf(from, to)))))
            }
        }
    }
}
