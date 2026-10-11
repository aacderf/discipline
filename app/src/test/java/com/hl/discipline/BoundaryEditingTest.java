package com.hl.discipline;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class BoundaryEditingTest {
  private List<double[]> rectangle() {
    Rules.Rect r = Rules.Rect.around(31, 121, 50);
    return Arrays.asList(new double[] {r.north, r.west}, new double[] {r.north, r.east},
        new double[] {r.south, r.east}, new double[] {r.south, r.west});
  }
  @Test public void deletingOnePointKeepsValidDraftAndOriginalUntouched() {
    List<double[]> source = rectangle(), next = BoundaryEditing.remove(source, 0);
    assertEquals(4, source.size()); assertEquals(3, next.size());
    assertTrue(new Rules.PolygonArea(next).valid());
    next.get(0)[0] = 0; assertNotEquals(0, source.get(1)[0], 0);
  }
  @Test public void triangleAndMissingSelectionCannotBeDeleted() {
    List<double[]> triangle = BoundaryEditing.remove(rectangle(), 0);
    assertThrows(IllegalArgumentException.class, () -> BoundaryEditing.remove(triangle, 0));
    assertThrows(IllegalArgumentException.class, () -> BoundaryEditing.remove(rectangle(), -1));
  }
  @Test public void invalidResultDoesNotChangeOriginal() {
    List<double[]> points = rectangle();
    points.set(2, points.get(1).clone());
    assertThrows(IllegalArgumentException.class, () -> BoundaryEditing.remove(points, 3));
    assertEquals(4, points.size());
  }
}
