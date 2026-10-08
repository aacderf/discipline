package com.hl.discipline

import org.junit.Assert.*
import org.junit.Test

class TimetableViewportTest {
    @Test fun smallestGridFitsSevenDaysAndLeavesSpaceForShortNames() {
        for (width in listOf(256f, 296f, 368f)) for (font in listOf(1f, 1.3f)) {
            val geometry = timetableGeometry(width, font, 1f)
            assertEquals(width, geometry.width, .01f)
            // Two periods can contain four scaled short-name lines plus vertical padding.
            assertTrue(geometry.slotHeight * 2 - 4 >= 15 * font * 4 + 10)
            assertTrue(courseShortName("概率论与数理统计").isNotBlank())
        }
    }

    @Test fun pinchKeepsDayAndPeriodUnderFingerAwayFromEdges() {
        val state = TimetableViewportState(2f, 100f, 80f)
        val before = timetableGeometry(296f, 1.3f, state.zoom)
        val day = (state.x + 120f) / before.pitch
        val period = (state.y + 90f) / before.slotHeight
        state.zoomAt(2.8f, 120f, 90f, 296f, 220f, 1.3f)
        val after = timetableGeometry(296f, 1.3f, state.zoom)
        assertEquals(day, (state.x + 120f) / after.pitch, .001f)
        assertEquals(period, (state.y + 90f) / after.slotHeight, .001f)
    }

    @Test fun repeatedZoomOutNeverProducesNegativeOffsetsOrVanishingLayout() {
        val state = TimetableViewportState(4f, 700f, 350f)
        repeat(20) { state.zoomAt(state.zoom - .4f, 148f, 150f, 296f, 300f, 1f) }
        assertEquals(1f, state.zoom, 0f)
        assertEquals(0f, state.x, .01f)
        assertTrue(state.y >= 0f)
        assertTrue(timetableGeometry(296f, 1f, state.zoom).height >= 456f)
    }
}
