package com.hl.discipline;

import java.util.*;

/** Simplifies the actual stroke rather than taking every nth sample, especially for narrow areas. */
final class TraceBoundary {
  static List<double[]> simplify(List<double[]> stroke) {
    List<double[]> points = new ArrayList<>();
    for (double[] point : stroke)
      if (points.isEmpty() || distance(points.get(points.size() - 1), point) >= .75) points.add(point);
    if (points.size() > 3 && distance(points.get(0), points.get(points.size() - 1)) < .75)
      points.remove(points.size() - 1);
    if (points.size() <= 3) return points;
    double tolerance = .75;
    List<double[]> reduced;
    do {
      boolean[] keep = new boolean[points.size()]; keep[0] = true; keep[points.size() - 1] = true;
      reduce(points, 0, points.size() - 1, tolerance, keep);
      reduced = new ArrayList<>();
      for (int i = 0; i < points.size(); i++) if (keep[i]) reduced.add(points.get(i));
      tolerance *= 1.5;
    } while (reduced.size() > 64);
    return reduced;
  }

  private static double distance(double[] a, double[] b) { return Rules.meters(a[0], a[1], b[0], b[1]); }
  private static void reduce(List<double[]> p, int from, int to, double tolerance, boolean[] keep) {
    if (to <= from + 1) return;
    double[] a = p.get(from), b = p.get(to);
    double scale = Math.cos(Math.toRadians(a[0])) * 111195;
    double dx = (b[1] - a[1]) * scale, dy = (b[0] - a[0]) * 111195;
    double max = 0; int furthest = from;
    for (int i = from + 1; i < to; i++) {
      double x = (p.get(i)[1] - a[1]) * scale, y = (p.get(i)[0] - a[0]) * 111195;
      double t = Math.max(0, Math.min(1, (x * dx + y * dy) / Math.max(1e-12, dx * dx + dy * dy)));
      double d = Math.hypot(x - t * dx, y - t * dy);
      if (d > max) { max = d; furthest = i; }
    }
    if (max > tolerance) {
      keep[furthest] = true;
      reduce(p, from, furthest, tolerance, keep); reduce(p, furthest, to, tolerance, keep);
    }
  }
}
