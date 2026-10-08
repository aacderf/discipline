package com.hl.discipline;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** Pure scheduling and interval arithmetic, shared by counting and settlement. */
public final class Rules {
  public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  public static final long MINUTE = 60_000L;

  public static LocalDate date(long ms) {
    return Instant.ofEpochMilli(ms).atZone(ZONE).toLocalDate();
  }

  public static long at(LocalDate day, int minute) {
    return day.atStartOfDay(ZONE).plusMinutes(minute).toInstant().toEpochMilli();
  }

  public static LocalDate monday(LocalDate day) {
    return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
  }

  public static long overlap(long a, long b, long c, long d) {
    return Math.max(0, Math.min(b, d) - Math.max(a, c));
  }

  public static long union(List<long[]> ranges) {
    if (ranges.isEmpty()) return 0;
    List<long[]> sorted = new ArrayList<>();
    for (long[] r : ranges) if (r[1] > r[0]) sorted.add(r.clone());
    sorted.sort(Comparator.comparingLong(r -> r[0]));
    long sum = 0, a = 0, b = 0;
    for (long[] r : sorted) {
      if (r[0] > b) {
        sum += b - a;
        a = r[0];
        b = r[1];
      } else b = Math.max(b, r[1]);
    }
    return sum + b - a;
  }

  public static List<long[]> intersect(List<long[]> a, List<long[]> b) {
    List<long[]> out = new ArrayList<>();
    for (long[] x : a)
      for (long[] y : b) {
        long s = Math.max(x[0], y[0]), e = Math.min(x[1], y[1]);
        if (e > s) out.add(new long[] {s, e});
      }
    return out;
  }

  public static List<long[]> windows(long start, long end, int from, int until, Set<Integer> days) {
    List<long[]> out = new ArrayList<>();
    if (end <= start) return out;
    for (LocalDate d = date(start); !d.isAfter(date(end - 1)); d = d.plusDays(1)) {
      if (!days.contains(d.getDayOfWeek().getValue())) continue;
      long a = Math.max(start, at(d, from)), b = Math.min(end, at(d, until));
      if (b > a) out.add(new long[] {a, b});
    }
    return out;
  }

  public static double meters(double a, double b, double c, double d) {
    double x = Math.toRadians(c - a), y = Math.toRadians(d - b);
    double h =
        Math.sin(x / 2) * Math.sin(x / 2)
            + Math.cos(Math.toRadians(a))
                * Math.cos(Math.toRadians(c))
                * Math.sin(y / 2)
                * Math.sin(y / 2);
    return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
  }

  /** Axis-aligned bounds in the place's stored coordinate system. */
  public static final class Rect {
    public final double north, south, east, west;

    public Rect(double north, double south, double east, double west) {
      this.north = north;
      this.south = south;
      this.east = east;
      this.west = west;
    }

    public static Rect around(double lat, double lng, double halfMeters) {
      double latitude = halfMeters / 111_195d;
      double longitude = halfMeters / (111_195d * Math.cos(Math.toRadians(lat - latitude)));
      return new Rect(lat + latitude, lat - latitude, lng + longitude, lng - longitude);
    }

    public double height() { return meters(south, west, north, west); }
    public double width() { return meters(south, west, south, east); }

    public boolean valid() {
      return Double.isFinite(north) && Double.isFinite(south)
          && Double.isFinite(east) && Double.isFinite(west)
          && north <= 85 && south >= -85 && east <= 180 && west >= -180
          && north > south && east > west
          && height() >= 19.5 && height() <= 6000.5
          && width() >= 19.5 && width() <= 6000.5;
    }

    /** Positive distance to the nearest edge inside; negative outside. */
    public double margin(double lat, double lng) {
      double northMeters = meters(lat, lng, north, lng);
      double southMeters = meters(lat, lng, south, lng);
      double eastMeters = meters(lat, lng, lat, east);
      double westMeters = meters(lat, lng, lat, west);
      if (lat >= south && lat <= north && lng >= west && lng <= east)
        return Math.min(Math.min(northMeters, southMeters), Math.min(eastMeters, westMeters));
      double ns = lat > north ? northMeters : lat < south ? southMeters : 0;
      double ew = lng > east ? eastMeters : lng < west ? westMeters : 0;
      return -Math.hypot(ns, ew);
    }
  }

  /** A hand-drawn boundary in the same coordinate system as its location fixes. */
  public static final class PolygonArea {
    public final List<double[]> points;
    public final Rect bounds;

    public PolygonArea(List<double[]> points) {
      this.points = new ArrayList<>(points);
      double north = -90, south = 90, east = -180, west = 180;
      for (double[] p : points) {
        if (p == null || p.length != 2) continue;
        north = Math.max(north, p[0]); south = Math.min(south, p[0]);
        east = Math.max(east, p[1]); west = Math.min(west, p[1]);
      }
      bounds = new Rect(north, south, east, west);
    }

    public boolean valid() {
      if (points.size() < 3 || points.size() > 64 || !bounds.valid()) return false;
      for (double[] p : points)
        if (p == null || p.length != 2 || !Double.isFinite(p[0]) || !Double.isFinite(p[1])
            || p[0] < -85 || p[0] > 85 || p[1] < -180 || p[1] > 180) return false;
      double area = 0;
      for (int i = 0; i < points.size(); i++) {
        double[] a = points.get(i), b = points.get((i + 1) % points.size());
        area += a[1] * b[0] - b[1] * a[0];
        if (meters(a[0], a[1], b[0], b[1]) < .5) return false;
      }
      if (Math.abs(area) < (bounds.north - bounds.south) * (bounds.east - bounds.west) * .1)
        return false;
      for (int i = 0; i < points.size(); i++)
        for (int j = i + 1; j < points.size(); j++) {
          if (j == i + 1 || (i == 0 && j == points.size() - 1)) continue;
          if (crosses(points.get(i), points.get((i + 1) % points.size()),
              points.get(j), points.get((j + 1) % points.size()))) return false;
        }
      return true;
    }

    private static double orient(double[] a, double[] b, double[] c) {
      return (b[1] - a[1]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[1] - a[1]);
    }

    private static boolean crosses(double[] a, double[] b, double[] c, double[] d) {
      double x = orient(a, b, c), y = orient(a, b, d);
      double z = orient(c, d, a), w = orient(c, d, b);
      return x * y < 0 && z * w < 0;
    }

    /** Positive distance to the nearest boundary inside, negative outside. */
    public double margin(double lat, double lng) {
      boolean inside = false;
      double nearest = Double.POSITIVE_INFINITY;
      double scale = Math.cos(Math.toRadians(lat));
      for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
        double[] a = points.get(j), b = points.get(i);
        if ((a[0] > lat) != (b[0] > lat)
            && lng < (b[1] - a[1]) * (lat - a[0]) / (b[0] - a[0]) + a[1]) inside = !inside;
        double ax = (a[1] - lng) * scale * 111195, ay = (a[0] - lat) * 111195;
        double bx = (b[1] - lng) * scale * 111195, by = (b[0] - lat) * 111195;
        double dx = bx - ax, dy = by - ay;
        double t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / Math.max(1e-12, dx * dx + dy * dy)));
        nearest = Math.min(nearest, Math.hypot(ax + t * dx, ay + t * dy));
      }
      return inside ? nearest : -nearest;
    }
  }

  /** Fractional minutes are preserved; the existing daily safety cap is applied by Engine. */
  public static long penaltyDuration(String metric, String mode, int fixedMinutes,
                                      double target, double progress) {
    if (metric.equals("presence") && mode.equals("remainingDwell"))
      return (long) Math.ceil(Math.max(0, target - progress) * MINUTE);
    return Math.max(0L, fixedMinutes) * MINUTE;
  }

  /** Two fixes establish arrival. Once arrived, drift has a bounded one-minute grace. */
  public static final class Fence {
    public static final long EXIT_GRACE = 60_000;
    public static final long FIX_TIMEOUT = 120_000;
    public boolean inside = false;
    public int candidate = 0;
    public long last = 0;
    public boolean lastReliable = false;
    private long confirmed = 0, uncertainSince = 0, credited = 0;

    public boolean inGrace() { return inside && uncertainSince > 0; }
    public long validUntil() { return uncertainSince > 0 ? uncertainSince + EXIT_GRACE : confirmed + FIX_TIMEOUT; }

    /** Advance only to the expiry, even if no more location callbacks arrive. */
    public long[] advance(long now) {
      if (!inside) return null;
      long end = Math.min(now, validUntil());
      long[] earned = end > credited ? new long[] {credited, end} : null;
      credited = Math.max(credited, end);
      if (now >= validUntil()) reset();
      return earned;
    }

    public void reset() {
      inside = false; candidate = 0; lastReliable = false;
      last = 0; confirmed = 0; uncertainSince = 0; credited = 0;
    }

    public long[] accept(long now, double distance, double radius, double accuracy) {
      return acceptMargin(now, radius - distance, radius, accuracy);
    }

    public long[] acceptRect(long now, double lat, double lng, Rect rect, double accuracy) {
      return acceptMargin(now, rect.margin(lat, lng), Math.min(rect.width(), rect.height()) / 2,
          accuracy);
    }

    public long[] acceptPolygon(long now, double lat, double lng, PolygonArea area, double accuracy) {
      return acceptMargin(now, area.margin(lat, lng),
          Math.min(area.bounds.width(), area.bounds.height()) / 2, accuracy);
    }

    private long[] acceptMargin(long now, double margin, double scale, double accuracy) {
      if (now < last) { reset(); return null; }
      long[] earned = advance(now);
      boolean definiteInside = Double.isFinite(margin) && Double.isFinite(accuracy)
          && accuracy >= 0 && accuracy <= Math.max(50, scale / 2) && margin >= accuracy;
      if (inside) {
        if (definiteInside) { confirmed = now; uncertainSince = 0; }
        else if (uncertainSince == 0) uncertainSince = now;
      } else if (definiteInside) {
        if (lastReliable && now > last && now - last <= FIX_TIMEOUT) {
          inside = true; candidate = 2; confirmed = now; credited = now;
          if (earned == null) earned = new long[] {last, now};
        } else candidate = 1;
      } else {
        candidate = 0;
      }
      lastReliable = definiteInside;
      last = now;
      return earned;
    }
  }
}
