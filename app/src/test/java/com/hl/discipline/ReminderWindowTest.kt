package com.hl.discipline

import org.junit.Assert.*
import org.junit.Test

class ReminderWindowTest {
    private val anchor = 1_000_000L
    @Test fun zeroLeadAllowsDispatchDelayButExpires() {
        assertTrue(ReminderWindow.active(anchor,anchor,anchor))
        assertTrue(ReminderWindow.active(anchor,anchor,anchor+1000))
        assertFalse(ReminderWindow.active(anchor,anchor,anchor+5*60_000))
    }
    @Test fun advanceReminderKeepsItsOriginalWindow() {
        assertFalse(ReminderWindow.active(anchor-60_000,anchor,anchor-60_001))
        assertTrue(ReminderWindow.active(anchor-60_000,anchor,anchor-1))
        assertFalse(ReminderWindow.active(anchor-60_000,anchor,anchor))
    }
    @Test fun overdueUnsentReminderGetsARecoveryAlarm() {
        assertEquals(anchor,ReminderWindow.next(anchor,anchor,anchor-1))
        assertEquals(anchor+2000,ReminderWindow.next(anchor,anchor,anchor+1000))
        assertEquals(Long.MAX_VALUE,ReminderWindow.next(anchor,anchor,anchor+300_000))
        assertFalse(ReminderWindow.active(0,0,anchor))
    }
    @Test fun completedAndLeaveTasksNeverGetRecoveryReminders() {
        assertTrue(ReminderWindow.eligible("failed",anchor,anchor,false))
        assertTrue(ReminderWindow.eligible("review",anchor,anchor,false))
        assertFalse(ReminderWindow.eligible("done",anchor,anchor,false))
        assertFalse(ReminderWindow.eligible("leave",anchor,anchor,false))
        assertFalse(ReminderWindow.eligible("failed",anchor,anchor,true))
        assertFalse(ReminderWindow.eligible("failed",anchor-1000,anchor,false))
    }
}
