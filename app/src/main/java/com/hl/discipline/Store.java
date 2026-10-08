package com.hl.discipline;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Small local database. Each table has a single versioned JSON record per id. */
public final class Store extends SQLiteOpenHelper {
  public static final int VERSION = 4;
  private static Store instance;
  private final File contextFiles;
  public static final String[] TABLES = {
    "plans", "instances", "places", "sessions", "penalties", "events", "state"
  };

  public static synchronized Store get(Context c) {
    if (instance == null) instance = new Store(c.getApplicationContext());
    return instance;
  }

  private Store(Context c) {
    super(c, "discipline.db", null, VERSION);
    contextFiles = c.getFilesDir();
  }

  @Override
  public void onCreate(SQLiteDatabase d) {
    for (String t : TABLES)
      d.execSQL("CREATE TABLE " + t + " (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    if (a == 1 && b >= 2) {
      try (Cursor c = d.query("places", new String[] {"id", "data"}, null, null, null, null, null)) {
        while (c.moveToNext()) {
          JSONObject place = parse(c.getString(1));
          if (!place.has("coordinateSystem")) {
            set(place, "coordinateSystem", "WGS84");
            ContentValues values = new ContentValues();
            values.put("data", place.toString());
            if (d.update("places", values, "id=?", new String[] {c.getString(0)}) != 1)
              throw new IllegalStateException("地点迁移失败");
          }
        }
      }
      a = 2;
    }
    if (a == 2 && b >= 3) {
      try (Cursor c = d.query("places", new String[] {"id", "data"}, null, null, null, null, null)) {
        while (c.moveToNext()) {
          JSONObject place = parse(c.getString(1));
          migrateCircle(place);
          ContentValues values = new ContentValues();
          values.put("data", place.toString());
          if (d.update("places", values, "id=?", new String[] {c.getString(0)}) != 1)
            throw new IllegalStateException("地点迁移失败");
        }
      }
      ContentValues notice = new ContentValues();
      notice.put("id", "rectangleMigrationNotice");
      notice.put("data", obj("id", "rectangleMigrationNotice", "pending", true).toString());
      d.insertWithOnConflict("state", null, notice, SQLiteDatabase.CONFLICT_REPLACE);
      a = 3;
    }
    // v4 adds optional polygon vertices inside the existing JSON place record.
    // Existing rectangular places keep their exact geometry and identifiers.
    if (a == 3 && b >= 4) a = 4;
    if (a != b) throw new IllegalStateException("不支持的数据版本");
  }

  public static Rules.PolygonArea polygon(JSONObject place) {
    JSONArray vertices = place.optJSONArray("points");
    if (vertices == null) return null;
    List<double[]> points = new ArrayList<>();
    for (int i = 0; i < vertices.length(); i++) {
      JSONArray pair = vertices.optJSONArray(i);
      if (pair == null || pair.length() != 2) return null;
      points.add(new double[] {pair.optDouble(0, Double.NaN), pair.optDouble(1, Double.NaN)});
    }
    return new Rules.PolygonArea(points);
  }

  private static void migrateCircle(JSONObject place) {
    if (place.has("north") && place.has("south") && place.has("east") && place.has("west"))
      return;
    double lat = place.optDouble("lat", Double.NaN);
    double lng = place.optDouble("lng", Double.NaN);
    int radius = place.optInt("radius", -1);
    if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 84
        || Math.abs(lng) > 179 || radius < 100 || radius > 3000)
      throw new IllegalArgumentException("旧地点数据无效");
    Rules.Rect r = Rules.Rect.around(lat, lng, radius);
    if (!r.valid()) throw new IllegalArgumentException("旧地点范围无法迁移");
    set(place, "north", r.north);
    set(place, "south", r.south);
    set(place, "east", r.east);
    set(place, "west", r.west);
    place.remove("radius");
  }

  private static void table(String t) {
    if (!Arrays.asList(TABLES).contains(t)) throw new IllegalArgumentException(t);
  }

  public static JSONObject obj(Object... args) {
    JSONObject o = new JSONObject();
    try {
      for (int i = 0; i < args.length; i += 2) o.put((String) args[i], args[i + 1]);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }

  public static JSONObject copy(JSONObject o) {
    try {
      return new JSONObject(o.toString());
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static void set(JSONObject o, String k, Object v) {
    try {
      o.put(k, v);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public synchronized void put(String t, JSONObject o) {
    table(t);
    if (o.optString("id").isEmpty()) throw new IllegalArgumentException("缺少记录标识");
    ContentValues v = new ContentValues();
    v.put("id", o.optString("id"));
    v.put("data", o.toString());
    if (getWritableDatabase().insertWithOnConflict(t, null, v, SQLiteDatabase.CONFLICT_REPLACE) == -1)
      throw new SQLiteException("保存记录失败");
  }

  public synchronized JSONObject one(String t, String id) {
    table(t);
    if (id == null) return null;
    try (Cursor c =
        getReadableDatabase()
            .query(t, new String[] {"data"}, "id=?", new String[] {id}, null, null, null)) {
      return c.moveToFirst() ? parse(c.getString(0)) : null;
    }
  }

  public synchronized List<JSONObject> all(String t) {
    table(t);
    List<JSONObject> list = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase().query(t, new String[] {"data"}, null, null, null, null, "rowid")) {
      while (c.moveToNext()) list.add(parse(c.getString(0)));
    }
    return list;
  }

  public synchronized void remove(String t, String id) {
    table(t);
    getWritableDatabase().delete(t, "id=?", new String[] {id});
  }

  public synchronized void atomic(Runnable r) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      r.run();
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public static JSONObject parse(String s) {
    try {
      return new JSONObject(s);
    } catch (JSONException e) {
      throw new IllegalStateException("数据无法读取", e);
    }
  }

  public synchronized JSONObject settings() {
    JSONObject o = one("state", "settings");
    if (o == null) {
      o =
          obj(
              "id",
              "settings",
              "trial",
              true,
              "trialSince",
              System.currentTimeMillis(),
              "capHours",
              8,
              "monitor",
              false,
              "quotes",
              "把今天能做的事，认真做完。",
              "rotate",
              true,
              "focusMinutes",
              25,
              "shortBreak",
              5,
              "longBreak",
              15,
              "cycles",
              4,
              "themeMode",
              "system",
              "planReminderMode",
              "sound",
              "classReminderMode",
              "sound",
              "startReminderMinutes",
              15,
              "dueReminderMinutes",
              30,
              "classReminderMinutes",
              10,
              "defaultApps",
              new JSONArray());
      put("state", o);
    }
    return o;
  }

  public void log(String type, String text) {
    put("events", obj("id", id(), "time", System.currentTimeMillis(), "type", type, "text", text));
  }

  public synchronized String exportJson() {
    JSONObject out = obj("version", VERSION, "exportedAt", System.currentTimeMillis());
    for (String t : TABLES) set(out, t, new JSONArray(all(t)));
    return out.toString();
  }

  public synchronized JSONObject prepareImport(String text) {
    JSONObject root = parse(text);
    int version = root.optInt("version");
    if (version < 1 || version > VERSION) throw new IllegalArgumentException("备份版本不支持");
    Map<String, Set<String>> keys = new HashMap<>();
    for (String t : TABLES) {
      JSONArray a = root.optJSONArray(t);
      if (a == null) throw new IllegalArgumentException("备份缺少 " + t);
      Set<String> ids = new HashSet<>();
      keys.put(t, ids);
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.optJSONObject(i);
        if (o == null || o.optString("id").isEmpty() || !ids.add(o.optString("id")))
          throw new IllegalArgumentException("备份记录无效");
        if ((t.equals("plans") || t.equals("instances"))
            && (!Arrays.asList("check", "count", "focus", "presence", "placeFocus")
                    .contains(o.optString("metric"))
                || !Double.isFinite(o.optDouble("target"))
                || o.optDouble("target") <= 0
                || !Arrays.asList("fixed", "remainingDwell").contains(o.optString("penaltyMode", "fixed"))
                || o.optString("penaltyMode").equals("remainingDwell") && !o.optString("metric").equals("presence")
                || o.optInt("from", 0) < 0
                || o.optInt("until", 1440) > 1440
                || o.optInt("from", 0) >= o.optInt("until", 1440)))
          throw new IllegalArgumentException("备份中的计划格式无效");
        if (t.equals("places")) {
          if (!Double.isFinite(o.optDouble("lat")) || !Double.isFinite(o.optDouble("lng"))
              || Math.abs(o.optDouble("lat")) > 85 || Math.abs(o.optDouble("lng")) > 180)
            throw new IllegalArgumentException("备份中的地点格式无效");
          String system = o.optString("coordinateSystem", "WGS84");
          if (!system.equals("WGS84") && !system.equals("GCJ02"))
            throw new IllegalArgumentException("备份中的地点坐标系无效");
          if (!o.has("coordinateSystem")) set(o, "coordinateSystem", "WGS84");
          if (version < 3) migrateCircle(o);
          Rules.Rect rect = new Rules.Rect(o.optDouble("north", Double.NaN),
              o.optDouble("south", Double.NaN), o.optDouble("east", Double.NaN),
              o.optDouble("west", Double.NaN));
          if (o.optString("shape", "rectangle").equals("polygon")) {
            Rules.PolygonArea area = polygon(o);
            if (version < 4 || area == null || !area.valid()
                || Math.abs(area.bounds.north - rect.north) > 1e-7
                || Math.abs(area.bounds.south - rect.south) > 1e-7
                || Math.abs(area.bounds.east - rect.east) > 1e-7
                || Math.abs(area.bounds.west - rect.west) > 1e-7)
              throw new IllegalArgumentException("备份中的手绘范围无效");
          } else if (!o.optString("shape", "rectangle").equals("rectangle") || !rect.valid())
            throw new IllegalArgumentException("备份中的矩形范围无效");
        }
        if (t.equals("sessions")
            && (o.optLong("start") <= 0 || o.optLong("end") < o.optLong("start")))
          throw new IllegalArgumentException("备份中的计时片段无效");
        if (t.equals("penalties")
            && (o.optLong("start") <= 0 || o.optLong("end") <= o.optLong("start")))
          throw new IllegalArgumentException("备份中的限制时间无效");
        if (t.equals("state")
            && o.optString("id").equals("settings")
            && (o.optInt("cycles", 4) < 1
                || o.optInt("cycles", 4) > 12
                || o.optInt("capHours", 8) < 1
                || o.optInt("capHours", 8) > 24
                || o.optInt("classReminderMinutes", 10) < 0
                || o.optInt("classReminderMinutes", 10) > 120
                || !Arrays.asList("system", "light", "dark")
                    .contains(o.optString("themeMode", "system"))))
          throw new IllegalArgumentException("备份中的设置无效");
        if (t.equals("state") && o.optString("id").equals("timetable")) {
          if (!o.optString("termStart").equals("2026-08-31") || o.optInt("weekCount") != 18)
            throw new IllegalArgumentException("备份中的课表学期无效");
          JSONArray meetings = o.optJSONArray("meetings");
          if (meetings == null || meetings.length() > 500)
            throw new IllegalArgumentException("备份中的课表记录无效");
          Set<String> meetingIds = new HashSet<>();
          for (int row = 0; row < meetings.length(); row++) {
            JSONObject meeting = meetings.optJSONObject(row);
            if (meeting == null || meeting.optString("id").isEmpty()
                || !meetingIds.add(meeting.optString("id"))
                || meeting.optString("title").trim().isEmpty()
                || meeting.optInt("day") < 1 || meeting.optInt("day") > 7
                || meeting.optInt("from") < 1 || meeting.optInt("from") > 12
                || meeting.optInt("until") < meeting.optInt("from")
                || meeting.optInt("until") > 12)
              throw new IllegalArgumentException("备份中的课程无效");
            JSONArray weeks = meeting.optJSONArray("weeks");
            if (weeks == null || weeks.length() == 0 || weeks.length() > 18)
              throw new IllegalArgumentException("备份中的课程周次无效");
            Set<Integer> numbers = new HashSet<>();
            for (int week = 0; week < weeks.length(); week++) {
              int number = weeks.optInt(week);
              if (number < 1 || number > 18 || !numbers.add(number))
                throw new IllegalArgumentException("备份中的课程周次无效");
            }
            if (meeting.has("startTime") || meeting.has("endTime")) {
              try {
                java.time.LocalTime start = java.time.LocalTime.parse(meeting.getString("startTime"));
                java.time.LocalTime end = java.time.LocalTime.parse(meeting.getString("endTime"));
                if (!start.isBefore(end)) throw new IllegalArgumentException("备份中的课程时间无效");
              } catch (org.json.JSONException | java.time.format.DateTimeParseException ex) {
                throw new IllegalArgumentException("备份中的课程时间无效", ex);
              }
            }
          }
        }
        if (t.equals("state") && o.optString("id").equals("settings") && o.has("appLimits")) {
          JSONArray limits = o.optJSONArray("appLimits");
          if (limits == null || limits.length() > 100)
            throw new IllegalArgumentException("备份中的应用时长规则无效");
          Set<String> packages = new HashSet<>();
          for (int index = 0; index < limits.length(); index++) {
            JSONObject limit = limits.optJSONObject(index);
            if (limit == null || limit.optString("package").isEmpty()
                || !packages.add(limit.optString("package"))
                || limit.optInt("dailyMinutes") < 0 || limit.optInt("dailyMinutes") > 1440
                || limit.optInt("weeklyMinutes") < 0 || limit.optInt("weeklyMinutes") > 10080
                || (limit.optInt("dailyMinutes") == 0
                    && limit.optInt("weeklyMinutes") == 0))
              throw new IllegalArgumentException("备份中的应用时长规则无效");
          }
        }
      }
    }
    for (String t : new String[] {"plans", "instances"}) {
      JSONArray a = root.optJSONArray(t);
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.optJSONObject(i);
        if ((o.optString("metric").equals("presence") || o.optString("metric").equals("placeFocus"))
            && !keys.get("places").contains(o.optString("place")))
          throw new IllegalArgumentException("备份中的任务引用了不存在的地点");
        if (t.equals("instances") && !keys.get("plans").contains(o.optString("planId")))
          throw new IllegalArgumentException("备份中的任务引用了不存在的计划");
        if (!o.optString("parent").isEmpty() && !keys.get("plans").contains(o.optString("parent")))
          throw new IllegalArgumentException("备份中的上级目标不存在");
      }
    }
    Map<String, String> parents = new HashMap<>();
    JSONArray plans = root.optJSONArray("plans");
    for (int i = 0; i < plans.length(); i++) {
      JSONObject p = plans.optJSONObject(i);
      parents.put(p.optString("id"), p.optString("parent"));
    }
    for (String id : parents.keySet()) {
      Set<String> visited = new HashSet<>();
      String cursor = id;
      while (!cursor.isEmpty()) {
        if (!visited.add(cursor)) throw new IllegalArgumentException("备份中的目标层级存在循环");
        cursor = parents.getOrDefault(cursor, "");
      }
    }
    if (version < 3 && root.optJSONArray("places").length() > 0
        && !keys.get("state").contains("rectangleMigrationNotice"))
      root.optJSONArray("state").put(obj("id", "rectangleMigrationNotice", "pending", true));
    return root;
  }

  public synchronized void importJson(String text) {
    importPrepared(prepareImport(text));
  }

  public synchronized void importPrepared(JSONObject root) {
    try {
      File file = new File(getContextFiles(), "restore-before-" + System.currentTimeMillis() + ".json");
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(exportJson().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
    } catch (IOException e) {
      throw new IllegalStateException("恢复前备份失败，原数据未更改", e);
    }
    atomic(
        () -> {
          long now = System.currentTimeMillis();
          for (String t : TABLES) {
            getWritableDatabase().delete(t, null, null);
            JSONArray a = root.optJSONArray(t);
            for (int i = 0; i < a.length(); i++) {
              JSONObject record = a.optJSONObject(i);
              if (t.equals("plans")) {
                String cycle = record.optString("cycle", "once");
                if (cycle.equals("once") && record.optLong("deadline") <= now)
                  set(record, "restoreReview", true);
                if (cycle.equals("daily")) {
                  java.time.LocalDate day = Rules.date(now);
                  if (Rules.at(day, record.optInt("until", 1440)) <= now) day = day.plusDays(1);
                  set(record, "generated", Math.max(record.optLong("generated"),
                      Rules.at(day, 0)));
                }
                if (cycle.equals("weekly"))
                  set(record, "generated", Math.max(record.optLong("generated"),
                      Rules.at(Rules.monday(Rules.date(now)), 0)));
              }
              put(t, record);
            }
          }
          remove("state", "focus");
          for (JSONObject o : all("state"))
            if (o.optString("id").startsWith("pass:")
                || o.optString("id").startsWith("tail:")
                || o.optString("id").equals("location")
                || o.optString("id").equals("focusSelection"))
              remove("state", o.optString("id"));
          for (JSONObject o : all("instances"))
            if (o.optString("status").equals("open") && o.optLong("due") <= now) {
              set(o, "status", "review");
              set(o, "note", "恢复备份后待核验，未自动执行限制");
              put("instances", o);
            }
          JSONObject s = settings();
          set(s, "monitor", false);
          put("state", s);
          log("restore", "已恢复备份，计时已暂停，请重新检查权限并开启地点监测");
        });
  }

  private File getContextFiles() {
    return contextFiles;
  }

  public File latestRestoreSnapshot() {
    File[] files = contextFiles.listFiles((dir, name) ->
        name.startsWith("restore-before-") && name.endsWith(".json"));
    if (files == null || files.length == 0) return null;
    Arrays.sort(files, Comparator.comparing(File::getName));
    return files[files.length - 1];
  }
}
