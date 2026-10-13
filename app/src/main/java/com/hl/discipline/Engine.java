package com.hl.discipline;

import static com.hl.discipline.Store.*;

import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.*;
import java.util.*;
import org.json.*;

public final class Engine {
  final Context context;
  final Store db;

  public Engine(Context c) {
    context = c.getApplicationContext();
    db = Store.get(c);
  }

  public static Set<Integer> days(JSONObject p) {
    Set<Integer> s = new HashSet<>();
    JSONArray a = p.optJSONArray("days");
    if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optInt(i));
    else for (int i = 1; i <= 7; i++) s.add(i);
    return s;
  }

  public static List<long[]> windows(JSONObject i, long until) {
    return Rules.windows(
        i.optLong("start"),
        Math.min(i.optLong("due"), until),
        i.optInt("from", 0),
        i.optInt("until", 1440),
        days(i));
  }

  public void tick() {
    long now = System.currentTimeMillis();
    synchronized (db) {
      db.atomic(
          () -> {
            updateFocus(now);
            generate(now);
            for (JSONObject i : db.all("instances"))
              if (i.optString("status").equals("open")) {
                if (i.optLong("due") <= now) settle(i, now);
              }
          });
    }
    Scheduler.schedule(context);
  }

  void generate(long now) {
    repairOpenOnceWindows(now);
    LocalDate horizon = Rules.date(now).plusDays(7);
    for (JSONObject p : db.all("plans")) {
      if (p.optBoolean("archived")) continue;
      String cycle = p.optString("cycle", "once");
      if (cycle.equals("once")) {
        make(p, p.optLong("created"), p.optLong("deadline"));
        continue;
      }
      LocalDate day =
          Rules.date(Math.max(p.optLong("generated", p.optLong("created")), p.optLong("created")));
      if (cycle.equals("weekly")) day = Rules.monday(day);
      while (!day.isAfter(horizon)) {
        if (cycle.equals("weekly")) {
          make(p, Math.max(Rules.at(day, 0), p.optLong("created")), Rules.at(day.plusDays(7), 0));
          day = day.plusWeeks(1);
        } else {
          if (days(p).contains(day.getDayOfWeek().getValue()))
            make(
                p,
                Math.max(Rules.at(day, p.optInt("from", 0)), p.optLong("created")),
                Rules.at(day, p.optInt("until", 1440)));
          day = day.plusDays(1);
        }
      }
      long generated = Rules.at(day, 0);
      if (generated != p.optLong("generated")) {
        set(p, "generated", generated);
        db.put("plans", p);
      }
    }
  }

  /** Repair only the old deadline-day window on still-open, unexpired task snapshots. */
  private void repairOpenOnceWindows(long now) {
    for (JSONObject task : db.all("instances")) {
      String metric = task.optString("metric");
      if (!task.optString("status").equals("open")
          || !task.optString("cycle", "once").equals("once")
          || !(metric.equals("presence") || metric.equals("placeFocus"))) continue;
      long created = task.optLong("created"), due = task.optLong("due");
      if (created <= 0 || due <= now || created >= due) continue;
      long legacyStart = Math.max(created, Rules.at(Rules.date(due - 1), task.optInt("from", 0)));
      if (task.optLong("start") == legacyStart && legacyStart > created) {
        set(task, "start", created);
        db.put("instances", task);
      }
    }
  }

  void make(JSONObject p, long start, long due) {
    if (due <= start) return;
    String id = p.optString("id") + ":" + due;
    if (db.one("instances", id) != null) return;
    JSONObject i = copy(p);
    set(i, "id", id);
    set(i, "planId", p.optString("id"));
    set(i, "start", start);
    set(i, "due", due);
    set(i, "status", p.optBoolean("restoreReview") && due <= System.currentTimeMillis()
        ? "review" : "open");
    set(i, "manual", 0);
    db.put("instances", i);
  }

  public List<JSONObject> current(long now) {
    List<JSONObject> result = new ArrayList<>();
    for (JSONObject i : db.all("instances"))
      if (i.optLong("start") <= now
          && (i.optLong("due") > Rules.at(Rules.date(now), 0)
              || i.optString("status").equals("review"))) result.add(i);
    result.sort(Comparator.comparingLong(i -> i.optLong("due")));
    return result;
  }

  public List<long[]> ranges(String kind, String place, String task) {
    List<long[]> result = new ArrayList<>();
    for (JSONObject s : db.all("sessions")) {
      if (!s.optString("kind").equals(kind)) continue;
      if (place != null && !s.optString("place").equals(place)) continue;
      if (task != null && !s.optString("task").equals(task)) continue;
      result.add(new long[] {s.optLong("start"), s.optLong("end")});
    }
    if (kind.equals("presence") || kind.equals("coverage")) {
      JSONObject location = db.one("state", "location");
      if (location != null && location.optBoolean("reliable")
          && (place == null || place.equals(location.optString("place")))) {
        long start = location.optLong("time");
        long end = Math.min(System.currentTimeMillis(), location.optLong("validUntil", start + 120_000));
        if (end > start) result.add(new long[] {start, end});
      }
    }
    return result;
  }

  public double progress(JSONObject i, long now) {
    String metric = i.optString("metric");
    double manual = i.optDouble("manual", 0);
    if (metric.equals("check") || metric.equals("count")) return manual;
    String place = i.optString("place");
    List<long[]> r =
        metric.equals("presence")
            ? ranges("presence", place, null)
            : ranges("focus", null, i.optString("id"));
    if (metric.equals("placeFocus")) r = Rules.intersect(r, ranges("presence", place, null));
    return manual + Rules.union(Rules.intersect(r, windows(i, now))) / (double) Rules.MINUTE;
  }

  public void segment(String kind, String place, String task, long start, long end) {
    if (end <= start) return;
    String p = place == null ? "" : place, t = task == null ? "" : task;
    synchronized (db) {
      String tailId = "tail:" + kind + ":" + p + ":" + t;
      JSONObject tail = db.one("state", tailId);
      JSONObject last = tail == null ? null : db.one("sessions", tail.optString("session"));
      if (last != null && last.optLong("end") == start) {
        set(last, "end", end);
        db.put("sessions", last);
        return;
      }
      String sid = id();
      db.put(
          "sessions",
          obj("id", sid, "kind", kind, "place", p, "task", t, "start", start, "end", end));
      db.put("state", obj("id", tailId, "session", sid));
    }
  }

  public boolean hasChildren(JSONObject i) {
    for (JSONObject p : db.all("plans"))
      if (p.optString("parent").equals(i.optString("planId"))) return true;
    return false;
  }

  public boolean canParent(String childId, String parentId) {
    Set<String> visited = new HashSet<>();
    String cursor = parentId;
    while (!cursor.isEmpty()) {
      if (cursor.equals(childId) || !visited.add(cursor)) return false;
      JSONObject parent = db.one("plans", cursor);
      if (parent == null) return true;
      cursor = parent.optString("parent");
    }
    return true;
  }

  public boolean childrenDone(JSONObject i) {
    return childrenDone(i, new HashSet<>());
  }

  private boolean childrenDone(JSONObject i, Set<String> ancestors) {
    if (!ancestors.add(i.optString("planId"))) return false;
    boolean any = false;
    for (JSONObject p : db.all("plans"))
      if (p.optString("parent").equals(i.optString("planId"))) {
        any = true;
        boolean found = false;
        for (JSONObject child : db.all("instances"))
          if (child.optString("planId").equals(p.optString("id"))
              && child.optLong("start") < i.optLong("due")
              && child.optLong("due") > i.optLong("start")) {
            found = true;
            boolean done =
                child.optString("status").equals("done")
                    || child.optString("status").equals("leave");
            if (!done)
              done =
                  hasChildren(child)
                      ? childrenDone(child, new HashSet<>(ancestors))
                      : progress(child, System.currentTimeMillis()) >= child.optDouble("target", 1);
            if (!done) return false;
          }
        if (!found) return false;
      }
    return any;
  }

  void settle(JSONObject i, long now) {
    if (hasChildren(i)) {
      set(i, "status", childrenDone(i) ? "done" : "failed");
      db.put("instances", i);
      return;
    }
    double value = progress(i, i.optLong("due"));
    set(i, "settledProgress", value);
    if (value + 0.0001 >= i.optDouble("target", 1)) {
      set(i, "status", "done");
      db.put("instances", i);
      return;
    }
    if (!i.optString("place").isEmpty()) {
      List<long[]> w = windows(i, i.optLong("due"));
      long expected = Rules.union(w),
          covered = Rules.union(Rules.intersect(ranges("coverage", null, null), w));
      if (expected - covered > 180_000 && Rules.locationNeedsReview(
          i.optString("coveragePolicy"), i.optLong("start"), i.optLong("due"))) {
        set(i, "status", "review");
        set(i, "note", "定位记录存在缺口，请核验后结算");
        db.put("instances", i);
        Scheduler.notify(
            context, Math.abs(i.optString("id").hashCode()), "任务需要核验", i.optString("name"));
        return;
      }
      if (expected - covered > 180_000)
        set(i, "note", "定位记录有缺口，缺口未计入停留；按已记录进度结算");
    }
    fail(i, now);
  }

  public void fail(JSONObject i, long now) {
    set(i, "status", "failed");
    db.put("instances", i);
    punish(i, now);
  }

  void punish(JSONObject i, long now) {
    String pid = "penalty:" + i.optString("id");
    long duration = penaltyDuration(i);
    if (db.one("penalties", pid) != null || duration <= 0) return;
    JSONArray apps = i.optJSONArray("apps");
    if (apps == null || apps.length() == 0) return;
    long requested = i.optLong("due");
    int next = i.optInt("penaltyAt", -1);
    long start = Rules.penaltyStart(requested, next, now);
    long cap = db.settings().optLong("capHours", 8) * 60 * Rules.MINUTE;
    boolean trial = db.settings().optBoolean("trial", true);
    long end = start, requestedEnd = start + duration;
    while (end < requestedEnd) {
      long dayStart = Rules.at(Rules.date(end), 0),
          dayEnd = Rules.at(Rules.date(end).plusDays(1), 0),
          segmentEnd = Math.min(requestedEnd, dayEnd);
      List<long[]> existing = new ArrayList<>();
      for (JSONObject p : db.all("penalties"))
        if (p.optBoolean("trial") == trial)
          existing.add(
              new long[] {
                Math.max(dayStart, p.optLong("start")), Math.min(dayEnd, p.optLong("end"))
              });
      long partStart = end, lo = end, hi = segmentEnd;
      while (lo < hi) {
        long mid = lo + (hi - lo + 1) / 2;
        List<long[]> all = new ArrayList<>(existing);
        all.add(new long[] {partStart, mid});
        if (Rules.union(all) <= cap) lo = mid;
        else hi = mid - 1;
      }
      end = lo;
      if (end < segmentEnd) break;
    }
    db.put(
        "penalties",
        obj(
            "id",
            pid,
            "task",
            i.optString("id"),
            "name",
            i.optString("name"),
            "apps",
            apps,
            "start",
            start,
            "end",
            end,
            "requestedMillis", duration,
            "settledAt", now,
            "due", requested,
            "mode", i.optString("penaltyMode", "fixed"),
            "trial",
            trial));
    db.log("penalty", (trial ? "试运行记录：" : end > start ? "已安排限制：" : "每日限制上限已用尽：") + i.optString("name"));
    Scheduler.notify(
        context, 300, "任务未完成", i.optString("name") + (trial ? " · 本次仅记录"
            : end > start ? " · 已按约定安排应用限制" : " · 每日限制上限已用尽，本次未拦截"));
  }

  public void confirmFailure(String taskId, long now) {
    synchronized (db) {
      db.atomic(() -> {
        JSONObject current = db.one("instances", taskId);
        if (current == null || !current.optString("status").equals("review"))
          throw new IllegalStateException("任务状态已变化，请重新查看");
        fail(current, now);
      });
    }
    Scheduler.schedule(context);
  }

  long penaltyDuration(JSONObject task) {
    return Rules.penaltyDuration(task.optString("metric"), task.optString("penaltyMode", "fixed"),
        task.optInt("penalty", 120), task.optDouble("target", 1),
        progress(task, task.optLong("due")));
  }

  public void manual(JSONObject supplied, double amount, String reason, boolean leave) {
    if (reason.trim().isEmpty()) throw new IllegalArgumentException("请填写原因");
    if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("进度必须是非负数");
    tick();
    db.atomic(
        () -> {
          // Read and validate under the same transaction as the write; a tick may settle it.
          JSONObject i = db.one("instances", supplied.optString("id"));
          if (i == null
              || !i.optString("status").equals("open") && !i.optString("status").equals("review"))
            throw new IllegalStateException("本周期已结算，无法补改；后续计划可重新安排");
          set(i, "note", reason);
          set(i, "manual", amount);
          set(
              i,
              "status",
              leave
                  ? "leave"
                  : (progress(i, System.currentTimeMillis()) >= i.optDouble("target", 1)
                      ? "done"
                      : "open"));
          db.put("instances", i);
          db.log(leave ? "leave" : "manual", i.optString("name") + "：" + reason);
          if (!leave
              && i.optLong("due") <= System.currentTimeMillis()
              && !i.optString("status").equals("done")) fail(i, System.currentTimeMillis());
        });
    tick();
  }

  int boot() {
    return Settings.Global.getInt(context.getContentResolver(), "boot_count", 0);
  }

  public void startFocus(String task, String place, int minutes, boolean forward, JSONArray apps) {
    JSONObject settings = db.settings();
    startFocus(task, place, minutes, forward, apps, settings.optInt("shortBreak", 5),
        settings.optInt("longBreak", 15), settings.optInt("cycles", 4));
  }

  public void startFocus(String task, String place, int minutes, boolean forward, JSONArray apps,
      int shortBreak, int longBreak, int cycles) {
    if (minutes < 1 || minutes > 720 || shortBreak < 1 || shortBreak > 120
        || longBreak < 1 || longBreak > 180 || cycles < 1 || cycles > 12)
      throw new IllegalArgumentException("专注或休息配置超出范围");
    synchronized (db) {
    if (db.one("state", "focus") != null) throw new IllegalStateException("请先结束当前专注");
    long now = System.currentTimeMillis();
    if (!task.isEmpty() && !canFocus(db.one("instances", task), now))
      throw new IllegalStateException("关联任务已完成、未开始或已失效，请重新选择");
    if (!place.isEmpty() && !inside(place, now))
      throw new IllegalStateException("请先开启地点监测并确认已到达地点");
    db.put(
        "state",
        obj(
            "id",
            "focus",
            "phase",
            "work",
            "task",
            task,
            "place",
            place,
            "start",
            now,
            "checkpoint",
            now,
            "elapsed",
            SystemClock.elapsedRealtime(),
            "boot",
            boot(),
            "minutes",
            minutes,
            "shortBreak",
            shortBreak,
            "longBreak",
            longBreak,
            "cycles",
            cycles,
            "remaining",
            minutes * Rules.MINUTE,
            "forward",
            forward,
            "total",
            0,
            "cycle",
            1,
            "apps",
            apps));
    }
    Scheduler.schedule(context);
  }

  public boolean inside(String place, long now) {
    JSONObject l = db.one("state", "location");
    return l != null
        && now - l.optLong("time") < 120_000
        && now < l.optLong("validUntil", l.optLong("time") + 120_000)
        && l.optString("place").equals(place)
        && l.optBoolean("reliable");
  }

  /** The same eligibility is used by the picker and the authoritative start check. */
  public boolean canFocus(JSONObject task, long now) {
    return task != null && task.optString("status").equals("open")
        && task.optLong("start") <= now && task.optLong("due") > now && !hasChildren(task);
  }

  void updateFocus(long now) {
    JSONObject f = db.one("state", "focus");
    if (f == null) return;
    String phase = f.optString("phase");
    if (!phase.equals("work") && !phase.equals("break")) return;
    if (f.optInt("boot") != boot()
        || Math.abs(
                (now - f.optLong("checkpoint"))
                    - (SystemClock.elapsedRealtime() - f.optLong("elapsed")))
            > 120_000) {
      set(f, "pausedPhase", phase);
      set(f, "phase", "paused");
      db.put("state", f);
      db.log("gap", "计时因重启或系统时间变化暂停，未确认时间不计入专注");
      return;
    }
    if (phase.equals("break")) {
      long remain = f.optLong("remaining") - (now - f.optLong("checkpoint"));
      set(f, "remaining", Math.max(0, remain));
      set(f, "checkpoint", now);
      set(f, "elapsed", SystemClock.elapsedRealtime());
      if (remain <= 0) {
        set(f, "cycle", f.optInt("cycle") + 1);
        set(f, "remaining", f.optLong("minutes") * Rules.MINUTE);
        boolean waitingForPlace = !f.optString("place").isEmpty()
            && !inside(f.optString("place"), now);
        set(f, "phase", waitingForPlace ? "paused" : "work");
        set(f, "pausedPhase", "work");
        db.put("state", f);
        FocusEvents.publish(context, "rest-ended", "休息结束",
            waitingForPlace ? "地点尚未确认，下一轮已暂停。返回指定地点后继续。"
                : "第 " + f.optInt("cycle") + " 轮专注已自动开始。");
        return;
      }
      db.put("state", f);
      return;
    }
    long end = now;
    if (!f.optBoolean("forward"))
      end = Math.min(end, f.optLong("checkpoint") + f.optLong("remaining"));
    String place = f.optString("place");
    boolean lost = !place.isEmpty() && !inside(place, now);
    if (lost) {
      JSONObject loc = db.one("state", "location");
      end = Math.min(end, loc == null ? f.optLong("checkpoint") : loc.optLong("time"));
    }
    long delta = Math.max(0, end - f.optLong("checkpoint"));
    segment("focus", place, f.optString("task"), f.optLong("checkpoint"), end);
    set(f, "total", f.optLong("total") + delta);
    set(f, "remaining", Math.max(0, f.optLong("remaining") - delta));
    set(f, "checkpoint", now);
    set(f, "elapsed", SystemClock.elapsedRealtime());
    if (lost) {
      set(f, "pausedPhase", "work");
      set(f, "phase", "paused");
      db.log("focus", "地点状态不确定或已经离开，专注已暂停");
      Scheduler.notify(context, 301, "专注已暂停", "请确认已返回指定地点后继续");
    } else if (!f.optBoolean("forward") && f.optLong("remaining") == 0) {
      set(f, "phase", "break");
      int cycles = Math.max(1, f.optInt("cycles", db.settings().optInt("cycles", 4)));
      int rest = f.optInt(f.optInt("cycle") % cycles == 0 ? "longBreak" : "shortBreak",
          f.optInt("cycle") % cycles == 0 ? 15 : 5);
      set(f, "remaining", rest * Rules.MINUTE);
      db.put("state", f);
      FocusEvents.publish(context, "work-ended", "本轮专注完成",
          "休息 " + rest + " 分钟，结束后自动开始下一轮。");
      return;
    }
    db.put("state", f);
  }

  public void pauseFocus() {
    synchronized (db) {
      updateFocus(System.currentTimeMillis());
      JSONObject f = db.one("state", "focus");
      if (f != null && (f.optString("phase").equals("work") || f.optString("phase").equals("break"))) {
        set(f, "pausedPhase", f.optString("phase"));
        set(f, "phase", "paused");
        db.put("state", f);
      }
    }
    Scheduler.schedule(context);
  }

  public void resumeFocus() {
    synchronized (db) {
    JSONObject f = db.one("state", "focus");
    if (f == null) return;
    if (!f.optString("phase").equals("ready") && !f.optString("phase").equals("paused"))
      throw new IllegalStateException("当前阶段无需继续");
    long now = System.currentTimeMillis();
    boolean resumeBreak = f.optString("phase").equals("paused")
        && f.optString("pausedPhase").equals("break");
    if (!resumeBreak && !f.optString("place").isEmpty() && !inside(f.optString("place"), now))
      throw new IllegalStateException("尚未确认返回指定地点");
    if (f.optString("phase").equals("ready")) {
      set(f, "cycle", f.optInt("cycle") + 1);
      set(f, "remaining", f.optLong("minutes") * Rules.MINUTE);
    }
    set(
        f,
        "phase",
        resumeBreak
            ? "break"
            : "work");
    set(f, "checkpoint", now);
    set(f, "elapsed", SystemClock.elapsedRealtime());
    set(f, "boot", boot());
    db.put("state", f);
    }
    Scheduler.schedule(context);
  }

  public void skipBreak() {
    synchronized (db) {
      JSONObject f = db.one("state", "focus");
      if (f == null || !(f.optString("phase").equals("break") ||
          f.optString("phase").equals("paused") && f.optString("pausedPhase").equals("break")))
        throw new IllegalStateException("当前没有可跳过的休息");
      set(f, "phase", "ready");
      set(f, "remaining", 0);
      db.put("state", f);
    }
    Scheduler.schedule(context);
  }

  public void endFocus() {
    synchronized (db) {
      updateFocus(System.currentTimeMillis());
      JSONObject f = db.one("state", "focus");
      if (f != null) db.log("focus", "结束专注，本次累计 " + f.optLong("total") / Rules.MINUTE + " 分钟");
      db.remove("state", "focus");
    }
    Scheduler.schedule(context);
  }

  public JSONObject blocked(String pkg, long now) {
    return blocked(pkg, now, false, false);
  }

  JSONObject blockedOnAppSwitch(String pkg, long now) {
    return blocked(pkg, now, false, true);
  }

  JSONObject blockedForFallback(String pkg, long now, boolean switchedApp) {
    return blocked(pkg, now, true, switchedApp);
  }

  private JSONObject blocked(String pkg, long now, boolean fallback, boolean switchedApp) {
    JSONObject pass = db.one("state", "pass:" + pkg);
    if (pass != null && pass.optLong("until") > now) return null;
    long end = 0;
    String name = "";
    for (JSONObject p : db.all("penalties"))
      if (!p.optBoolean("trial")
          && p.optLong("start") <= now
          && p.optLong("end") > now
          && contains(p.optJSONArray("apps"), pkg)) {
        end = Math.max(end, p.optLong("end"));
        name = p.optString("name");
      }
    JSONObject f = db.one("state", "focus");
    if (f != null && f.optString("phase").equals("work") && contains(f.optJSONArray("apps"), pkg)) {
      end =
          Math.max(
              end,
              f.optBoolean("forward")
                  ? now + Rules.MINUTE
                  : f.optLong("checkpoint") + f.optLong("remaining"));
      name = "正在专注";
    }
    JSONArray limits = UsageWeekPlans.effectiveLimits(db, now);
    if (limits != null && limits.length() > 0 && !BlockService.protectedApp(context, pkg)) {
      for (int index = 0; index < limits.length(); index++) {
        JSONObject limit = limits.optJSONObject(index);
        if (limit == null || !limit.optBoolean("enabled", true)
            || !pkg.equals(limit.optString("package"))) continue;
        int daily = limit.optInt("dailyMinutes"), weekly = limit.optInt("weeklyMinutes");
        if (daily <= 0 && weekly <= 0) continue;
        AppUsageLimits.Usage usage = fallback
            ? AppUsageLimits.readForLimit(context, pkg, now, daily, weekly, switchedApp)
            : AppUsageLimits.readForLimit(context, pkg, now, daily, weekly, switchedApp);
        if (!usage.available) break;
        long dailyEnd = AppUsageLimits.dailyBlockEnd(usage, daily, now);
        if (dailyEnd > end) { end = dailyEnd; name = "每日使用已达上限"; }
        long weeklyEnd = AppUsageLimits.weeklyBlockEnd(usage, weekly, now);
        if (weeklyEnd > end) { end = weeklyEnd; name = "本周使用已达上限"; }
        break;
      }
    }
    return end > now ? obj("end", end, "name", name) : null;
  }

  public static boolean contains(JSONArray a, String v) {
    if (a != null) for (int i = 0; i < a.length(); i++) if (a.optString(i).equals(v)) return true;
    return false;
  }
}
