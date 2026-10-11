package com.hl.discipline

internal data class CourseSlot(val day: Int, val from: Int, val until: Int, val weeks: List<Int>) {
    fun overlaps(other: CourseSlot) = day == other.day && from <= other.until && until >= other.from &&
        weeks.any { it in other.weeks }
}

internal data class ImportedCourse(val title: String, val room: String, val slot: CourseSlot)

/** Pure CSV parsing and batch conflict selection, independent of Android and persistence. */
internal object TimetableCsv {
    fun acceptedIndices(existing: List<CourseSlot>, incoming: List<CourseSlot>): List<Int> {
        val occupied = existing.toMutableList()
        return incoming.indices.filter { index ->
            val candidate = incoming[index]
            if (occupied.any { it.overlaps(candidate) }) false
            else { occupied += candidate; true }
        }
    }

    fun parse(text: String): List<ImportedCourse> {
        val records = records(text.removePrefix("\uFEFF"))
        require(records.size > 1) { "CSV 没有课程，请在列名下添加课程行" }
        val header = records.first().second.map { it.trim().lowercase(java.util.Locale.ROOT) }
        require(header.distinct().size == header.size) { "CSV 列名不能重复" }
        fun column(name: String) = header.indexOf(name).also { require(it >= 0) { "CSV 缺少 $name 列" } }
        val title = column("title"); val room = column("room"); val day = column("day")
        val from = column("from"); val until = column("until"); val weeks = column("weeks")
        require(records.size - 1 <= 500) { "一次最多导入 500 条课程" }
        return records.drop(1).map { (line, cells) ->
            require(cells.size == header.size) { "第 $line 行列数与列名不一致" }
            fun value(at: Int) = cells[at].trim()
            val d = value(day).toIntOrNull() ?: 0
            val first = value(from).toIntOrNull() ?: 0
            val last = value(until).toIntOrNull() ?: 0
            require(value(title).isNotEmpty() && d in 1..7 && first in 1..12 && last in first..12) {
                "第 $line 行的课程或节次无效"
            }
            ImportedCourse(value(title), value(room), CourseSlot(d, first, last, weeks(value(weeks), line)))
        }
    }

    fun weeks(value: String, line: Int = 1): List<Int> {
        val range = Regex("(\\d+)-(\\d+)(?:/(odd|even))?")
        val numbers = value.lowercase(java.util.Locale.ROOT).split(';').flatMap { part ->
            val match = range.matchEntire(part.trim())
            if (match == null) listOf(part.trim().toIntOrNull() ?: 0)
            else {
                val first = match.groupValues[1].toIntOrNull() ?: 0
                val last = match.groupValues[2].toIntOrNull() ?: 0
                require(first in 1..18 && last in first..18) { "第 $line 行周次无效" }
                val parity = match.groupValues[3]
                (first..last).filter { parity.isEmpty() || it % 2 == if (parity == "odd") 1 else 0 }
            }
        }
        require(numbers.isNotEmpty() && numbers.all { it in 1..18 }) { "第 $line 行周次无效" }
        return numbers.distinct().sorted()
    }

    private fun records(text: String): List<Pair<Int, List<String>>> {
        val out = mutableListOf<Pair<Int, List<String>>>()
        val cells = mutableListOf<String>(); val cell = StringBuilder()
        var quoted = false; var closed = false; var line = 1; var startLine = 1; var index = 0
        fun finishCell() { cells += cell.toString(); cell.clear(); closed = false }
        fun finishRow() {
            finishCell()
            if (cells.any { it.isNotBlank() }) out += startLine to cells.toList()
            cells.clear()
        }
        while (index < text.length) {
            val ch = text[index]
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') { cell.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else {
                    cell.append(ch)
                    if (ch == '\n') line++
                }
            } else when (ch) {
                ',' -> finishCell()
                '\r', '\n' -> {
                    finishRow()
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    line++; startLine = line
                }
                '"' -> {
                    require(!closed && cell.isBlank()) { "第 $line 行引号位置无效" }
                    cell.clear(); quoted = true
                }
                else -> {
                    require(!closed || ch.isWhitespace()) { "第 $line 行引号后有多余内容" }
                    if (!closed) cell.append(ch)
                }
            }
            index++
        }
        require(!quoted) { "第 $startLine 行 CSV 引号未闭合" }
        if (cell.isNotEmpty() || cells.isNotEmpty() || closed) finishRow()
        return out
    }
}
