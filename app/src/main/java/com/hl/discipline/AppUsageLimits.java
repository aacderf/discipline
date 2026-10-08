package com.hl.discipline;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.PowerManager;
import android.app.KeyguardManager;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Reads the system's local usage history for one selected app. */
public final class AppUsageLimits {
  private AppUsageLimits() {}
  static final AtomicInteger debugFreshQueries = new AtomicInteger();
  static final AtomicInteger debugForegroundQueries = new AtomicInteger();

  public static final class Usage {
    public final boolean available;
    public final long todayMillis;
    public final long weekMillis;

    Usage(boolean available, long todayMillis, long weekMillis) {
      this.available = available;
      this.todayMillis = todayMillis;
      this.weekMillis = weekMillis;
    }
  }

  private static final class Cached {
    final long at;
    final Usage usage;
    Cached(long at, Usage usage) { this.at = at; this.usage = usage; }
  }

  private static final Map<String, Cached> cache = new HashMap<>();

  public static synchronized void invalidate() { cache.clear(); }

  /** Best-effort foreground detection for the explicitly enabled fallback service. */
  static String foregroundPackage(Context context, long from, long now, String previous) {
    PowerManager power = context.getSystemService(PowerManager.class);
    KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
    if (power == null || !power.isInteractive() ||
        (keyguard != null && keyguard.isKeyguardLocked()) || !hasAccess(context)) return "";
    UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
    if (manager == null) return "";
    if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
      debugForegroundQueries.incrementAndGet();
    UsageEvents events = manager.queryEvents(Math.max(0, from), now);
    if (events == null) return "";
    String foreground = previous;
    UsageEvents.Event event = new UsageEvents.Event();
    while (events.hasNextEvent()) {
      events.getNextEvent(event);
      if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED &&
          event.getPackageName() != null) foreground = event.getPackageName();
      else if (event.getEventType() == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
          event.getEventType() == UsageEvents.Event.KEYGUARD_SHOWN) foreground = "";
    }
    return foreground;
  }

  public static boolean hasAccess(Context context) {
    AppOpsManager ops = context.getSystemService(AppOpsManager.class);
    if (ops == null) return false;
    int mode = Build.VERSION.SDK_INT >= 29
        ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.getPackageName())
        : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.getPackageName());
    return mode == AppOpsManager.MODE_ALLOWED;
  }

  public static synchronized Usage read(Context context, String pkg, long now) {
    return readCached(context, pkg, now, 5_000);
  }

  /** Use a fresh sample on app switches and near a limit; a reached limit lasts to reset. */
  static synchronized Usage readForLimit(Context context, String pkg, long now,
                                          int dailyMinutes, int weeklyMinutes, boolean switched) {
    if (!hasAccess(context)) return new Usage(false, 0, 0);
    Cached saved = cache.get(pkg);
    if (saved != null && Rules.date(now).equals(Rules.date(saved.at))) {
      if ((dailyMinutes > 0 && saved.usage.todayMillis >= dailyMinutes * Rules.MINUTE) ||
          (weeklyMinutes > 0 && saved.usage.weekMillis >= weeklyMinutes * Rules.MINUTE))
        return saved.usage;
    }
    long remaining = Long.MAX_VALUE;
    if (saved != null && Rules.date(now).equals(Rules.date(saved.at))) {
      if (dailyMinutes > 0) remaining = Math.min(remaining,
          dailyMinutes * Rules.MINUTE - saved.usage.todayMillis);
      if (weeklyMinutes > 0) remaining = Math.min(remaining,
          weeklyMinutes * Rules.MINUTE - saved.usage.weekMillis);
    }
    return readCached(context, pkg, now, switched ? 0 : remaining <= Rules.MINUTE ? 1_000 : 5_000);
  }

  private static Usage readCached(Context context, String pkg, long now, long maxAge) {
    if (!hasAccess(context)) return new Usage(false, 0, 0);
    Cached saved = cache.get(pkg);
    if (saved != null && now >= saved.at && now - saved.at < maxAge
        && Rules.date(now).equals(Rules.date(saved.at)))
      return saved.usage;

    UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
    if (manager == null) return new Usage(false, 0, 0);
    if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
      debugFreshQueries.incrementAndGet();
    LocalDate day = Rules.date(now);
    long todayStart = Rules.at(day, 0);
    long weekStart = Rules.at(Rules.monday(day), 0);
    List<UsageStats> stats = manager.queryUsageStats(
        UsageStatsManager.INTERVAL_DAILY, weekStart, now);
    UsageEvents events = manager.queryEvents(Math.max(0, todayStart - Rules.MINUTE * 60), now);
    if (stats == null || events == null) return new Usage(false, 0, 0);
    long todayFromStats = 0, earlierThisWeek = 0;
    for (UsageStats s : stats) {
      if (!pkg.equals(s.getPackageName())) continue;
      long first = s.getFirstTimeStamp();
      if (first >= todayStart) todayFromStats += s.getTotalTimeInForeground();
      else if (first >= weekStart) earlierThisWeek += s.getTotalTimeInForeground();
    }
    long todayFromEvents = foregroundToday(events, pkg, todayStart, now);
    long today = Math.max(todayFromStats, todayFromEvents);
    Usage usage = new Usage(true, today, earlierThisWeek + today);
    cache.put(pkg, new Cached(now, usage));
    return usage;
  }

  static long dailyBlockEnd(Usage usage, int minutes, long now) {
    return usage.available && minutes > 0 && usage.todayMillis >= minutes * Rules.MINUTE
        ? Rules.at(Rules.date(now).plusDays(1), 0) : 0;
  }

  static long weeklyBlockEnd(Usage usage, int minutes, long now) {
    return usage.available && minutes > 0 && usage.weekMillis >= minutes * Rules.MINUTE
        ? Rules.at(Rules.monday(Rules.date(now)).plusWeeks(1), 0) : 0;
  }

  static long foregroundToday(UsageEvents events, String pkg, long start, long now) {
    Set<String> resumed = new HashSet<>();
    UsageEvents.Event event = new UsageEvents.Event();
    long segment = -1, total = 0;
    while (events.hasNextEvent()) {
      events.getNextEvent(event);
      int kind = event.getEventType();
      long at = Math.min(now, event.getTimeStamp());
      if (kind == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
          kind == UsageEvents.Event.KEYGUARD_SHOWN) {
        if (segment >= 0) total += Math.max(0, at - Math.max(start, segment));
        segment = -1;
        resumed.clear();
        continue;
      }
      if (!pkg.equals(event.getPackageName())) continue;
      String activity = event.getClassName() == null ? pkg : event.getClassName();
      if (kind == UsageEvents.Event.ACTIVITY_RESUMED) {
        if (resumed.isEmpty()) segment = at;
        resumed.add(activity);
      } else if (kind == UsageEvents.Event.ACTIVITY_PAUSED ||
          kind == UsageEvents.Event.ACTIVITY_STOPPED) {
        resumed.remove(activity);
        if (resumed.isEmpty() && segment >= 0) {
          total += Math.max(0, at - Math.max(start, segment));
          segment = -1;
        }
      }
    }
    if (segment >= 0) total += Math.max(0, now - Math.max(start, segment));
    return total;
  }
}
