package com.hl.discipline;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class TraceBoundaryTest {
  @Test public void denseNarrowClosedStrokeRemainsAValidBoundary() {
    List<double[]> stroke = new ArrayList<>();
    for (int i = 0; i <= 360; i++) {
      double angle = Math.toRadians(i);
      stroke.add(new double[] {31 + 15 * Math.cos(angle) / 111195,
          121 + 12 * Math.sin(angle) / (111195 * Math.cos(Math.toRadians(31)))});
    }
    Rules.PolygonArea area = new Rules.PolygonArea(TraceBoundary.simplify(stroke));
    assertTrue(area.valid()); assertTrue(area.points.size() <= 64);
    assertTrue(area.margin(31, 121) > 10);
  }

  @Test public void crossedStrokeIsNotTurnedIntoAnAcceptableArea() {
    Rules.Rect r = Rules.Rect.around(31, 121, 40);
    List<double[]> stroke = Arrays.asList(new double[] {r.north, r.west}, new double[] {r.south, r.east},
        new double[] {r.north, r.east}, new double[] {r.south, r.west}, new double[] {r.north, r.west});
    assertFalse(new Rules.PolygonArea(TraceBoundary.simplify(stroke)).valid());
  }
}
