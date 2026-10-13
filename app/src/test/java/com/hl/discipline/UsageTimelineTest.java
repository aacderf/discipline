package com.hl.discipline;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class UsageTimelineTest {
  private UsageTimeline.Point point(long at,String activity,int kind) { return new UsageTimeline.Point(at,"app",activity,kind); }
  @Test public void clipsAcrossMidnightAndAtReportEnd() {
    UsageTimeline.Totals t=UsageTimeline.calculate(Arrays.asList(point(150,"A",1),point(280,"A",2)),new long[]{100,200,300},260).get("app");
    assertArrayEquals(new long[]{50,60},t.millis); assertArrayEquals(new int[]{1,0},t.entries);
  }
  @Test public void overlappingActivitiesCountOneSession() {
    UsageTimeline.Totals t=UsageTimeline.calculate(Arrays.asList(point(110,"A",1),point(130,"B",1),point(150,"A",2),point(180,"B",3)),new long[]{100,200},190).get("app");
    assertEquals(70,t.millis[0]); assertEquals(1,t.entries[0]);
  }
  @Test public void screenOffEndsAllForegroundIntervals() {
    UsageTimeline.Totals t=UsageTimeline.calculate(Arrays.asList(point(110,"A",1),point(150,"",4)),new long[]{100,200},190).get("app");
    assertEquals(40,t.millis[0]);
  }
  @Test public void lookbackSessionDoesNotAddAnOpeningInsideReport() {
    UsageTimeline.Totals t=UsageTimeline.calculate(Arrays.asList(point(50,"A",1),point(180,"A",2)),new long[]{100,200},190).get("app");
    assertEquals(80,t.millis[0]); assertEquals(0,t.entries[0]);
  }
  @Test public void stoppedAfterPauseDoesNotCountTwice() {
    UsageTimeline.Totals t=UsageTimeline.calculate(Arrays.asList(point(110,"A",1),point(140,"A",2),point(160,"A",3)),new long[]{100,200},190).get("app");
    assertEquals(30,t.millis[0]);
  }
  @Test public void stricterQuotaIgnoresAnUnsetLimit() {
    assertEquals(30,UsageTimeline.lowerPositive(0,30)); assertEquals(20,UsageTimeline.lowerPositive(20,30));
    assertEquals(20,UsageTimeline.lowerPositive(20,0));
  }
  @Test public void lateSettlementStartsNowButFutureClockIsPreserved() {
    long due=Rules.at(java.time.LocalDate.of(2026,10,12),600),now=due+2*Rules.MINUTE;
    assertEquals(now,Rules.penaltyStart(due,-1,now));
    assertEquals(Rules.at(Rules.date(due),1200),Rules.penaltyStart(due,1200,now));
    assertEquals(now,Rules.penaltyStart(due-24*60*Rules.MINUTE,1200,now));
  }
  @Test public void longLocationTaskUsesRecordedProgressWhileDailyStillReviews() {
    assertFalse(Rules.locationNeedsReview("",0,2*24*60*Rules.MINUTE));
    assertTrue(Rules.locationNeedsReview("",0,60*Rules.MINUTE));
    assertTrue(Rules.locationNeedsReview("review",0,2*24*60*Rules.MINUTE));
    assertFalse(Rules.locationNeedsReview("recorded",0,60*Rules.MINUTE));
  }
}
