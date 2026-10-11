package com.hl.discipline

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StatisticsReportTest {
    private val day = LocalDate.of(2026,10,11)
    private val start = Rules.at(day,0)
    private val now = Rules.at(day,12*60)
    private fun segment(from: Int, until: Int, task: String = "task", kind: String = "focus", place: String = "") =
        RecordedInterval(kind,task,place,Rules.at(day,from),Rules.at(day,until))
    private fun task(id: String = "task", plan: String = "plan", status: String = "done", metric: String = "check",
        target: Double = 1.0, manual: Double = 1.0, place: String = "") =
        ReportTask(id,plan,status,metric,place,target,manual,start,now-1,0,1440,(1..7).toSet())
    private fun input(sessions: List<RecordedInterval> = emptyList(), tasks: List<ReportTask> = emptyList(),
        plans: List<ReportPlan> = listOf(ReportPlan("plan","计划")), places: List<ReportPlace> = emptyList()) =
        StatisticsInput(sessions,tasks,plans,places)

    @Test fun continuousAndOverlappingFragmentsCountOnce() {
        val report = StatisticsReport.calculate(input(listOf(segment(30,40),segment(40,50),segment(35,45),segment(60,70))),1,now)
        assertEquals(30 * Rules.MINUTE,report.focus)
        assertEquals(2,report.focusSegments)
        assertEquals(report.focus,report.rows.sumOf { it.focus })
    }

    @Test fun intervalsClipToPeriodAndFutureIsExcluded() {
        val report = StatisticsReport.calculate(input(listOf(
            RecordedInterval("focus","task","",start - 10*Rules.MINUTE,start + 10*Rules.MINUTE),
            segment(13*60,14*60))),1,now)
        assertEquals(10*Rules.MINUTE,report.focus)
        assertEquals(1,report.focusSegments)
    }

    @Test fun reviewLeaveAndParentsDoNotLowerVerifiedCompletionRate() {
        val report = StatisticsReport.calculate(input(tasks = listOf(task(),task("review",status="review",manual=0.0),
            task("leave",status="leave"),task("parent",plan="parent",status="failed",manual=0.0)),
            plans = listOf(ReportPlan("plan","子计划","parent"),ReportPlan("parent","父计划"))),1,now)
        assertEquals(2,report.ended)
        assertEquals(1,report.verified)
        assertEquals(1,report.done)
        assertEquals(1,report.review)
    }

    @Test fun planDistributionsUseStableIdsAndPlaceFocusRequiresOverlap() {
        val report = StatisticsReport.calculate(input(listOf(segment(60,100),segment(80,110,kind="presence",place="library")),
            listOf(task(status="failed",metric="placeFocus",target=30.0,manual=0.0,place="library")),
            plans = listOf(ReportPlan("plan","同名计划"),ReportPlan("other","同名计划")),
            places = listOf(ReportPlace("library","图书馆"))),1,now)
        assertEquals(0,report.done)
        assertEquals(1,report.failed)
        assertEquals("plan",report.goals.single().id)
        assertEquals(40*Rules.MINUTE,report.goals.single().millis)
        assertEquals(30*Rules.MINUTE,report.places.single().millis)
    }

    @Test fun emptyPeriodsKeepPredictableChartAxes() {
        val daily = StatisticsReport.calculate(input(),1,now)
        assertTrue(daily.empty)
        assertEquals(6,daily.rows.size)
        assertEquals(7,StatisticsReport.calculate(input(),7,now).rows.size)
        assertEquals(31,StatisticsReport.calculate(input(),30,now).rows.size)
    }
}
