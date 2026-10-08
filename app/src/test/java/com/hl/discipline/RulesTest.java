package com.hl.discipline;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class RulesTest {
  @Test
  public void overlappingIntervalsCountOnce() {
    assertEquals(
        25,
        Rules.union(
            Arrays.asList(
                new long[] {10, 20},
                new long[] {15, 30},
                new long[] {30, 35},
                new long[] {15, 16})));
  }

  @Test
  public void invalidAndEmptyIntervalsDoNotCount() {
    assertEquals(0, Rules.union(Arrays.asList(new long[] {8, 7}, new long[] {8, 8})));
    assertEquals(0, Rules.overlap(1, 2, 3, 4));
  }

  @Test
  public void dailyWindowsClipBothEnds() {
    LocalDate d = LocalDate.of(2026, 9, 21);
    List<long[]> w =
        Rules.windows(
            Rules.at(d, 10 * 60),
            Rules.at(d.plusDays(1), 10 * 60),
            8 * 60,
            22 * 60,
            new HashSet<>(Arrays.asList(1, 2)));
    assertEquals(14 * 60 * Rules.MINUTE, Rules.union(w));
  }

  @Test
  public void weeklyStartsOnMonday() {
    assertEquals(LocalDate.of(2026, 9, 21), Rules.monday(LocalDate.of(2026, 9, 27)));
  }

  @Test
  public void intersectDoesNotCountRestOrOutside() {
    List<long[]> a = Arrays.asList(new long[] {0, 100}, new long[] {200, 300});
    List<long[]> b = Arrays.asList(new long[] {50, 250});
    assertEquals(100, Rules.union(Rules.intersect(a, b)));
  }

  @Test
  public void fenceRequiresTwoFixesAndStopsAfterOneMinuteOutside() {
    Rules.Fence f = new Rules.Fence();
    assertNull(f.accept(1000, 50, 200, 20));
    assertArrayEquals(new long[] {1000, 31000}, f.accept(31000, 50, 200, 20));
    assertArrayEquals(new long[] {31000, 61000}, f.accept(61000, 300, 200, 20));
    assertTrue(f.inside);
    assertTrue(f.inGrace());
    assertArrayEquals(new long[] {61000, 121000}, f.advance(121000));
    assertFalse(f.inside);
    assertNull(f.accept(122000, 50, 200, 20));
  }

  @Test
  public void fenceDoesNotBridgeDriftOrLostSignal() {
    Rules.Fence f = new Rules.Fence();
    f.accept(1000, 50, 200, 20);
    assertNull(f.accept(31000, 190, 200, 30));
    assertNull(f.accept(61000, 50, 200, 20));
    assertNull(f.accept(300000, 50, 200, 20));
  }

  @Test
  public void distanceIsMeters() {
    assertEquals(0, Rules.meters(31, 121, 31, 121), 0.001);
    assertEquals(111195, Rules.meters(0, 0, 1, 0), 200);
  }

  @Test public void rectangleBoundsAndAccuracyStopCountingAtEdge() {
    Rules.Rect rect = Rules.Rect.around(31.2304, 121.4737, 200);
    assertTrue(rect.valid());
    assertEquals(400, rect.height(), 3);
    assertEquals(400, rect.width(), 3);
    Rules.Fence fence = new Rules.Fence();
    assertNull(fence.acceptRect(1000, 31.2304, 121.4737, rect, 20));
    assertArrayEquals(new long[] {1000, 31000},
        fence.acceptRect(31000, 31.2304, 121.4737, rect, 20));
    assertArrayEquals(new long[] {31000, 61000}, fence.acceptRect(61000, rect.north - .00005, 121.4737, rect, 20));
    assertArrayEquals(new long[] {61000, 91000}, fence.acceptRect(91000, rect.north + .001, 121.4737, rect, 20));
    assertArrayEquals(new long[] {91000, 121000}, fence.advance(200000));
    assertFalse(fence.inside);
  }

  @Test public void rectangleRejectsTooSmallOrOversizedBounds() {
    assertTrue(Rules.Rect.around(31, 121, 20).valid());
    assertFalse(Rules.Rect.around(31, 121, 8).valid());
    assertFalse(Rules.Rect.around(31, 121, 3100).valid());
  }

  @Test public void narrowPlaceNeedsAccuracyInsideItsBoundary() {
    Rules.Rect place = Rules.Rect.around(31, 121, 15);
    assertTrue(place.valid());
    Rules.Fence fence = new Rules.Fence();
    assertNull(fence.acceptRect(1000, 31, 121, place, 20));
    assertNull(fence.acceptRect(31000, 31, 121, place, 20));
    assertNull(fence.acceptRect(61000, 31, 121, place, 5));
    assertArrayEquals(new long[] {61000, 91000},
        fence.acceptRect(91000, 31, 121, place, 5));
  }

  @Test public void handDrawnConcaveAreaDoesNotCountItsBoundingBox() {
    Rules.PolygonArea area = new Rules.PolygonArea(Arrays.asList(
        new double[] {0, 0}, new double[] {0, .004}, new double[] {.001, .004},
        new double[] {.001, .001}, new double[] {.004, .001}, new double[] {.004, 0}));
    assertTrue(area.valid());
    assertTrue(area.margin(.0005, .003) > 20);
    assertTrue(area.margin(.003, .0005) > 20);
    assertTrue(area.margin(.003, .003) < -20);
    Rules.Fence fence = new Rules.Fence();
    assertNull(fence.acceptPolygon(1000, .0005, .003, area, 10));
    assertArrayEquals(new long[] {1000, 31000},
        fence.acceptPolygon(31000, .0005, .003, area, 10));
    assertArrayEquals(new long[] {31000, 61000}, fence.acceptPolygon(61000, .003, .003, area, 10));
    fence.advance(121000);
    assertFalse(fence.inside);
  }

  @Test public void driftRecoveryWithinGraceKeepsOneContinuousInterval() {
    Rules.Fence fence = new Rules.Fence();
    fence.accept(1000, 50, 200, 20);
    fence.accept(31000, 50, 200, 20);
    assertArrayEquals(new long[] {31000, 61000}, fence.accept(61000, 50, 200, 99999));
    assertArrayEquals(new long[] {61000, 91000}, fence.advance(91000));
    assertArrayEquals(new long[] {91000, 120999}, fence.accept(120999, 50, 200, 20));
    assertTrue(fence.inside);
    assertFalse(fence.inGrace());
    assertArrayEquals(new long[] {120999, 150000}, fence.advance(150000));
    assertNull(fence.advance(150000)); // No double credit on the next pulse.
  }

  @Test public void graceDoesNotRestartOnRepeatedBadFixesOrSilentSignal() {
    Rules.Fence fence = new Rules.Fence();
    fence.accept(1000, 50, 200, 20);
    fence.accept(31000, 50, 200, 20);
    fence.accept(61000, 190, 200, 30);
    fence.accept(91000, 300, 200, 20);
    assertEquals(121000, fence.validUntil());
    assertArrayEquals(new long[] {91000, 121000}, fence.advance(300000));
    assertFalse(fence.inside);
    assertNull(fence.advance(400000));
    fence.accept(401000, 50, 200, 20);
    fence.accept(431000, 50, 200, 20);
    assertArrayEquals(new long[] {431000, 551000}, fence.advance(700000));
    assertFalse(fence.inside);
  }

  @Test public void remainingDwellPenaltyUsesMissingTimeAndPreservesFixedRules() {
    assertEquals(60 * Rules.MINUTE, Rules.penaltyDuration("presence", "remainingDwell", 0, 120, 60));
    assertEquals(30_000, Rules.penaltyDuration("presence", "remainingDwell", 0, 120, 119.5));
    assertEquals(0, Rules.penaltyDuration("presence", "remainingDwell", 120, 120, 125));
    assertEquals(5 * Rules.MINUTE, Rules.penaltyDuration("presence", "fixed", 5, 120, 60));
    assertEquals(5 * Rules.MINUTE, Rules.penaltyDuration("check", "remainingDwell", 5, 1, 0));
  }

  @Test public void handDrawnAreaRejectsCrossingAndTinyBoundary() {
    assertFalse(new Rules.PolygonArea(Arrays.asList(
        new double[] {0, 0}, new double[] {.004, .004},
        new double[] {0, .004}, new double[] {.004, 0})).valid());
    assertTrue(new Rules.PolygonArea(Arrays.asList(
        new double[] {0, 0}, new double[] {0, .0004},
        new double[] {.0004, .0004}, new double[] {.0004, 0})).valid());
    assertFalse(new Rules.PolygonArea(Arrays.asList(
        new double[] {0, 0}, new double[] {0, .0001},
        new double[] {.0001, .0001}, new double[] {.0001, 0})).valid());
  }
}
