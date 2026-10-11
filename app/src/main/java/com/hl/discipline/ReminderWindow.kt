package com.hl.discipline

/** Zero-lead reminders must tolerate the alarm's dispatch delay without replaying old events. */
internal object ReminderWindow {
    private const val ON_TIME_GRACE = 5 * 60_000L
    fun active(at: Long, anchor: Long, now: Long): Boolean = at > 0 && anchor > 0 && at <= anchor &&
        now >= at && if (at == anchor) now - anchor < ON_TIME_GRACE else now < anchor

    fun eligible(status: String, at: Long, anchor: Long, isStart: Boolean): Boolean =
        status == "open" || (!isStart && at == anchor && status in listOf("failed", "review"))

    fun next(at: Long, anchor: Long, now: Long): Long = when {
        at <= 0 || anchor <= 0 || at > anchor -> Long.MAX_VALUE
        at > now -> at
        active(at, anchor, now) -> now + 1000
        else -> Long.MAX_VALUE
    }
}
