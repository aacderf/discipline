package com.hl.discipline;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import java.time.LocalDate;

public class AppUsageLimitsTest {
  @Test public void dailyAndWeeklyLimitsBlockAtThresholdUntilTheirOwnReset() {
    long now = Rules.at(LocalDate.of(2026, 9, 27), 14 * 60);
    AppUsageLimits.Usage usage = new AppUsageLimits.Usage(true,
        30 * Rules.MINUTE, 200 * Rules.MINUTE);
    assertEquals(0, AppUsageLimits.dailyBlockEnd(usage, 31, now));
    assertEquals(Rules.at(LocalDate.of(2026, 9, 28), 0),
        AppUsageLimits.dailyBlockEnd(usage, 30, now));
    assertEquals(0, AppUsageLimits.weeklyBlockEnd(usage, 201, now));
    assertEquals(Rules.at(LocalDate.of(2026, 9, 28), 0),
        AppUsageLimits.weeklyBlockEnd(usage, 200, now));
  }

  @Test public void noAccessOrDisabledLimitNeverBlocks() {
    long now = Rules.at(LocalDate.of(2026, 9, 27), 0);
    AppUsageLimits.Usage unavailable = new AppUsageLimits.Usage(false,
        200 * Rules.MINUTE, 200 * Rules.MINUTE);
    assertEquals(0, AppUsageLimits.dailyBlockEnd(unavailable, 10, now));
    assertEquals(0, AppUsageLimits.weeklyBlockEnd(unavailable, 10, now));
    AppUsageLimits.Usage available = new AppUsageLimits.Usage(true,
        200 * Rules.MINUTE, 200 * Rules.MINUTE);
    assertEquals(0, AppUsageLimits.dailyBlockEnd(available, 0, now));
    assertEquals(0, AppUsageLimits.weeklyBlockEnd(available, 0, now));
  }
}
