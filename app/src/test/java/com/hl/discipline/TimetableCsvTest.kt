package com.hl.discipline

import org.junit.Assert.*
import org.junit.Test

class TimetableCsvTest {
    private val header = "title,room,day,from,until,weeks\n"

    @Test fun rangesParityAndMixedWeeksAreParsed() {
        val rows = TimetableCsv.parse(header + "课程,教室,1,1,2,1-18\n单周课,教室,2,3,4,1-18/odd\n混合课,教室,3,5,6,2-6/even;9;9")
        assertEquals((1..18).toList(), rows[0].slot.weeks)
        assertEquals(listOf(1,3,5,7,9,11,13,15,17), rows[1].slot.weeks)
        assertEquals(listOf(2,4,6,9), rows[2].slot.weeks)
    }

    @Test fun quotedCommaEscapesAndNewlineKeepOneCourse() {
        val row = TimetableCsv.parse("\uFEFF" + header + "\"课程,\"\"实验\"\"\",\"教学楼\nA101\",1,1,2,1-2\n").single()
        assertEquals("课程,\"实验\"", row.title)
        assertEquals("教学楼\nA101", row.room)
    }

    @Test fun invalidWeeksColumnsAndQuotesAreRejected() {
        listOf("课,教室,1,1,2,18-1", "课,教室,1,1,2,19", "课,教室,1,1,2,1,多余列", "\"课,教室,1,1,2,1")
            .forEach { bad -> assertThrows(IllegalArgumentException::class.java) { TimetableCsv.parse(header + bad) } }
        assertThrows(IllegalArgumentException::class.java) { TimetableCsv.parse(header) }
    }

    @Test fun batchConflictKeepsFirstAndPreservesOtherWeeksAndDays() {
        val first = CourseSlot(1,1,2,listOf(1,3))
        val duplicate = CourseSlot(1,2,4,listOf(3))
        val otherWeek = CourseSlot(1,1,2,listOf(2))
        val otherDay = CourseSlot(2,1,2,listOf(3))
        assertEquals(listOf(0,2,3), TimetableCsv.acceptedIndices(emptyList(), listOf(first,duplicate,otherWeek,otherDay)))
        assertEquals(listOf(2,3), TimetableCsv.acceptedIndices(listOf(first), listOf(first,duplicate,otherWeek,otherDay)))
    }
}
