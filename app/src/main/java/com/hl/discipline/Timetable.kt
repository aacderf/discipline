package com.hl.discipline

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The editable timetable is one small optional record in the existing backed-up state table. */
internal object Timetable {
    val termStart: LocalDate = LocalDate.of(2026, 8, 31)
    private val startTimes = mapOf(1 to "08:30", 2 to "09:15", 3 to "10:10", 4 to "10:55",
        5 to "14:00", 6 to "14:45", 7 to "15:40", 9 to "18:20", 11 to "19:50")
    private val endTimes = mapOf(1 to "09:10", 2 to "09:55", 3 to "10:50", 4 to "11:35",
        5 to "14:40", 6 to "15:25", 8 to "17:00", 10 to "19:40", 12 to "21:10")

    fun state(db: Store): JSONObject = db.one("state", "timetable") ?: seed().also { db.put("state", it) }
    fun weekOf(date: LocalDate): Int = (Math.floorDiv(java.time.temporal.ChronoUnit.DAYS.between(termStart, date), 7) + 1).toInt()
    fun date(week: Int, day: Int): LocalDate = termStart.plusDays(((week - 1) * 7 + day - 1).toLong())
    fun times(row: JSONObject): Pair<String, String> =
        (row.optString("startTime").ifEmpty { startTimes[row.optInt("from")].orEmpty() }) to
            (row.optString("endTime").ifEmpty { endTimes[row.optInt("until")].orEmpty() })
    fun rows(state: JSONObject): List<JSONObject> = state.optJSONArray("meetings")?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } ?: emptyList()
    fun inWeek(row: JSONObject, week: Int): Boolean = row.optJSONArray("weeks")?.let { array ->
        (0 until array.length()).any { array.optInt(it) == week }
    } == true
    fun forDay(state: JSONObject, week: Int, day: Int): List<JSONObject> = rows(state).filter {
        it.optInt("day") == day && inWeek(it, week)
    }.sortedWith(compareBy({ it.optInt("from") }, { it.optString("title") }))

    fun upsert(state: JSONObject, row: JSONObject, onlyWeek: Int? = null): JSONObject {
        val list = rows(state).toMutableList()
        val index = list.indexOfFirst { it.optString("id") == row.optString("id") }
        if (index >= 0 && onlyWeek != null) {
            val prior = list[index]
            val remaining = (1..18).filter { inWeek(prior, it) && it != onlyWeek }
            if (remaining.isEmpty()) list.removeAt(index)
            else Store.set(prior, "weeks", JSONArray(remaining))
            Store.set(row, "id", Store.id())
            Store.set(row, "weeks", JSONArray(listOf(onlyWeek)))
            list.add(row)
        } else if (index >= 0) list[index] = row else list.add(row)
        Store.set(state, "meetings", JSONArray(list))
        return state
    }

    fun delete(state: JSONObject, id: String, onlyWeek: Int? = null): JSONObject {
        val list = rows(state).toMutableList()
        val index = list.indexOfFirst { it.optString("id") == id }
        if (index < 0) return state
        if (onlyWeek == null) list.removeAt(index)
        else {
            val left = (1..18).filter { inWeek(list[index], it) && it != onlyWeek }
            if (left.isEmpty()) list.removeAt(index) else Store.set(list[index], "weeks", JSONArray(left))
        }
        Store.set(state, "meetings", JSONArray(list))
        return state
    }

    private fun seed(): JSONObject {
        // Synthetic examples only. The editable timetable is not part of this repository.
        val examples = listOf(
            Store.obj("id", "sample-odd", "title", "示例课程 A", "room", "示例教室 A",
                "day", 1, "from", 1, "until", 2, "weeks", JSONArray((1..18).filter { it % 2 == 1 })),
            Store.obj("id", "sample-even", "title", "示例课程 B", "room", "示例教室 B",
                "day", 1, "from", 5, "until", 6, "weeks", JSONArray((1..18).filter { it % 2 == 0 && it != 14 })),
            Store.obj("id", "sample-special", "title", "示例调课", "room", "示例教室 C",
                "day", 1, "from", 3, "until", 4, "weeks", JSONArray(listOf(14))),
            Store.obj("id", "sample-short", "title", "示例课程 C", "room", "示例教室 D",
                "day", 2, "from", 7, "until", 8, "weeks", JSONArray((1..16).toList())),
            Store.obj("id", "sample-long", "title", "示例课程 D", "room", "示例教室 E",
                "day", 3, "from", 9, "until", 12, "weeks", JSONArray((1..18).toList()))
        )
        examples.forEach { Store.set(it, "review", false); Store.set(it, "source", "sample") }
        return Store.obj("id", "timetable", "termStart", termStart.format(DateTimeFormatter.ISO_DATE),
            "weekCount", 18, "confirmed", false, "meetings", JSONArray(examples))
    }
    /** CSV: title,room,day,from,until,weeks. Weeks accept 1;3;5 or 1-18/odd. */
    fun parseCsv(text: String): List<JSONObject> {
        val lines = text.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "CSV 为空" }
        val header = csvLine(lines.first()).map { it.trim().lowercase() }
        fun column(name: String) = header.indexOf(name).also { require(it >= 0) { "CSV 缺少 $name 列" } }
        val title = column("title"); val room = column("room"); val day = column("day")
        val from = column("from"); val until = column("until"); val weeks = column("weeks")
        return lines.drop(1).mapIndexed { index, line ->
            val values = csvLine(line)
            fun value(at: Int) = values.getOrNull(at)?.trim().orEmpty()
            val d = value(day).toIntOrNull() ?: 0
            val first = value(from).toIntOrNull() ?: 0
            val last = value(until).toIntOrNull() ?: 0
            require(value(title).isNotBlank() && d in 1..7 && first in 1..12 && last in first..12) {
                "第 ${index + 2} 行的课程或节次无效"
            }
            val spec = value(weeks)
            val numbers = when {
                spec.matches(Regex("\\d+-\\d+/(odd|even)")) -> {
                    val (bounds, parity) = spec.split("/")
                    val (a, b) = bounds.split("-").map(String::toInt)
                    require(a in 1..18 && b in a..18) { "第 ${index + 2} 行周次无效" }
                    (a..b).filter { it % 2 == if (parity == "odd") 1 else 0 }
                }
                else -> spec.split(';').map { it.toIntOrNull() ?: 0 }
            }
            require(numbers.isNotEmpty() && numbers.all { it in 1..18 }) { "第 ${index + 2} 行周次无效" }
            Store.obj("id", Store.id(), "title", value(title), "room", value(room),
                "day", d, "from", first, "until", last, "weeks", JSONArray(numbers.distinct()),
                "review", times(Store.obj("from", first, "until", last)).let { it.first.isEmpty() || it.second.isEmpty() },
                "source", "csv")
        }
    }

    private fun csvLine(line: String): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { cell.append('"'); i++ }
                else quoted = !quoted
            } else if (ch == ',' && !quoted) { out += cell.toString(); cell.clear() }
            else cell.append(ch)
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        out += cell.toString()
        return out
    }
}
