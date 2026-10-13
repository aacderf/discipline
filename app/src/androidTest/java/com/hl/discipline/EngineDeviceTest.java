package com.hl.discipline;

import static com.hl.discipline.Store.*;
import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EngineDeviceTest {
  Context c;
  Store db;
  Engine e;
  long now;

  @Before
  public void setup() {
    c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(
        "Only run destructive fixture tests on the emulator",
        Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    db = Store.get(c);
    db.atomic(
        () -> {
          for (String t : Store.TABLES) db.getWritableDatabase().delete(t, null, null);
        });
    e = new Engine(c);
    now = System.currentTimeMillis();
  }

  JSONObject task(String id, String metric) {
    return obj(
        "id",
        id,
        "planId",
        id,
        "name",
        "测试任务 " + id,
        "metric",
        metric,
        "target",
        1,
        "start",
        now - 600_000,
        "due",
        now - 1000,
        "status",
        "open",
        "from",
        0,
        "until",
        1440,
        "place",
        "",
        "manual",
        0,
        "apps",
        new JSONArray(Arrays.asList("com.android.chrome")),
        "penalty",
        120);
  }

  @Test
  public void completedTaskDoesNotPunish() {
    JSONObject i = task("complete", "check");
    set(i, "manual", 1);
    db.put("instances", i);
    e.tick();
    assertEquals("done", db.one("instances", "complete").optString("status"));
    assertEquals(0, db.all("penalties").size());
  }

  @Test
  public void legacyMissingProgressIsZeroAndMalformedProgressBackupIsRejected() {
    JSONObject legacy = task("legacy-progress", "check");
    legacy.remove("manual"); set(legacy, "due", now + Rules.MINUTE);
    db.put("plans", copy(legacy));
    db.put("instances", legacy);
    assertNotNull(db.prepareImport(db.exportJson()));
    assertEquals(0, e.progress(legacy, now), 0.0001);
    for (Object invalid : Arrays.asList("invalid", -1)) {
      JSONObject backup = parse(db.exportJson());
      set(backup.optJSONArray("instances").optJSONObject(0), "manual", invalid);
      assertThrows(IllegalArgumentException.class, () -> db.prepareImport(backup.toString()));
      assertFalse(db.one("instances", "legacy-progress").has("manual"));
    }
  }

  @Test
  public void settlementIsIdempotentAndTrialIsSnapshot() {
    db.put("instances", task("failed", "check"));
    e.tick();
    e.tick();
    assertEquals(1, db.all("penalties").size());
    assertTrue(db.all("penalties").get(0).optBoolean("trial"));
    JSONObject settings = db.settings();
    set(settings, "trial", false);
    db.put("state", settings);
    assertNull(e.blocked("com.android.chrome", now));
  }

  @Test
  public void missingLocationNeedsReview() {
    JSONObject i = task("missing", "presence");
    set(i, "place", "library");
    db.put("instances", i);
    e.tick();
    assertEquals("review", db.one("instances", "missing").optString("status"));
    assertEquals(0, db.all("penalties").size());
  }

  @Test
  public void completedLocationWinsDespiteOtherGaps() {
    JSONObject i = task("location", "presence");
    set(i, "place", "library");
    db.put("instances", i);
    e.segment("presence", "library", "", now - 100_000, now - 2000);
    e.tick();
    assertEquals("done", db.one("instances", "location").optString("status"));
  }

  @Test
  public void confirmedAbsenceCanPunish() {
    JSONObject i = task("absent", "presence");
    set(i, "place", "library");
    db.put("instances", i);
    e.segment("coverage", "", "", now - 600_000, now - 1000);
    e.tick();
    assertEquals("failed", db.one("instances", "absent").optString("status"));
    assertEquals(1, db.all("penalties").size());
  }

  @Test
  public void placeFocusCountsIntersectionOnly() {
    JSONObject i = task("intersect", "placeFocus");
    set(i, "place", "library");
    e.segment("focus", "library", "intersect", now - 600_000, now - 100_000);
    e.segment("presence", "library", "", now - 300_000, now - 100_000);
    assertEquals(200_000.0 / 60_000, e.progress(i, now), 0.001);
  }

  @Test
  public void overlappingSegmentsDoNotDoubleCount() {
    JSONObject i = task("union", "focus");
    e.segment("focus", "", "union", now - 600_000, now - 200_000);
    e.segment("focus", "", "union", now - 400_000, now - 100_000);
    assertEquals(500_000.0 / 60_000, e.progress(i, now), 0.001);
  }

  @Test
  public void contiguousUpdatesStayOneRecord() {
    e.segment("focus", "", "t", now - 3000, now - 2000);
    e.segment("focus", "", "t", now - 2000, now - 1000);
    assertEquals(1, db.all("sessions").size());
  }

  @Test
  public void mergedRestrictionAndEmergencyPass() {
    JSONArray apps = new JSONArray(Arrays.asList("com.android.chrome"));
    db.put(
        "penalties",
        obj("id", "p1", "apps", apps, "start", now - 1000, "end", now + 60_000, "name", "A"));
    db.put(
        "penalties",
        obj("id", "p2", "apps", apps, "start", now - 1000, "end", now + 120_000, "name", "B"));
    assertEquals(now + 120_000, e.blocked("com.android.chrome", now).optLong("end"));
    assertNotNull(e.blocked("com.android.chrome", now + 70_000));
    db.put("state", obj("id", "pass:com.android.chrome", "until", now + 600_000));
    assertNull(e.blocked("com.android.chrome", now));
    assertNull(e.blocked("com.android.chrome", now + 600_001));
  }

  @Test
  public void dailyAndWeeklyInstancesGeneratedOnce() {
    JSONObject p =
        obj(
            "id", "weekly", "name", "周目标", "metric", "focus", "target", 60, "created", now, "cycle",
            "weekly", "from", 0, "until", 1440);
    db.put("plans", p);
    e.tick();
    int before = db.all("instances").size();
    e.tick();
    assertEquals(before, db.all("instances").size());
    assertTrue(before >= 2);
    for (JSONObject i : db.all("instances"))
      assertEquals(DayOfWeek.MONDAY, Rules.date(i.optLong("due")).getDayOfWeek());
  }

  @Test
  public void parentDoesNotDuplicatePenalty() {
    JSONObject p = obj("id", "childPlan", "parent", "parentPlan");
    db.put("plans", p);
    JSONObject parent = task("parent", "check");
    set(parent, "planId", "parentPlan");
    JSONObject child = task("child", "check");
    set(child, "planId", "childPlan");
    set(child, "parent", "parentPlan");
    db.put("instances", parent);
    db.put("instances", child);
    e.tick();
    assertEquals(1, db.all("penalties").size());
  }

  @Test
  public void dailyCapIsUnionNotSum() {
    JSONObject s = db.settings();
    set(s, "capHours", 1);
    db.put("state", s);
    long midnight = Rules.at(Rules.date(now), 0);
    JSONObject a = task("cap1", "check"), b = task("cap2", "check");
    set(a, "due", midnight + 60000);
    set(b, "due", midnight + 31 * 60000);
    e.fail(a, now);
    e.fail(b, now);
    List<long[]> periods = new ArrayList<>();
    for (JSONObject p : db.all("penalties"))
      periods.add(new long[] {p.optLong("start"), p.optLong("end")});
    assertEquals(60 * Rules.MINUTE, Rules.union(periods));
  }

  @Test
  public void restartAndClockMismatchPauseFocus() {
    e.startFocus("", "", 25, false, new JSONArray());
    JSONObject f = db.one("state", "focus");
    set(f, "boot", -999);
    db.put("state", f);
    e.tick();
    assertEquals("paused", db.one("state", "focus").optString("phase"));
    assertEquals(0, db.all("sessions").size());
  }

  @Test
  public void finishedPomodoroBecomesBreak() {
    e.startFocus("", "", 1, false, new JSONArray());
    JSONObject f = db.one("state", "focus");
    set(f, "checkpoint", now - 61_000);
    set(f, "elapsed", SystemClock.elapsedRealtime() - 61_000);
    db.put("state", f);
    e.tick();
    assertEquals("break", db.one("state", "focus").optString("phase"));
    assertEquals(60_000, db.one("state", "focus").optLong("total"));
    assertNull(e.blocked("com.android.chrome", now));
  }

  @Test
  public void backupRoundTripAndInvalidBackupPreservesData() {
    db.put("plans", task("backup", "count"));
    db.put("instances", task("backup", "count"));
    String backup = db.exportJson();
    db.remove("instances", "backup");
    db.importJson(backup);
    assertNotNull(db.one("instances", "backup"));
    assertNotNull(db.latestRestoreSnapshot());
    assertTrue(db.latestRestoreSnapshot().length() > 0);
    try {
      db.importJson("{\"version\":1}");
      fail("invalid backup should fail");
    } catch (IllegalArgumentException expected) {
    }
    assertNotNull(db.one("instances", "backup"));
  }

  @Test
  public void handDrawnPlaceRoundTripsAndRejectsCorruptBoundary() throws JSONException {
    JSONArray points = new JSONArray();
    points.put(new JSONArray(Arrays.asList(31.2300, 121.4700)));
    points.put(new JSONArray(Arrays.asList(31.2300, 121.4740)));
    points.put(new JSONArray(Arrays.asList(31.2310, 121.4740)));
    points.put(new JSONArray(Arrays.asList(31.2310, 121.4710)));
    points.put(new JSONArray(Arrays.asList(31.2340, 121.4710)));
    points.put(new JSONArray(Arrays.asList(31.2340, 121.4700)));
    JSONObject place = obj("id", "drawn", "name", "手绘图书馆", "lat", 31.232,
        "lng", 121.472, "north", 31.234, "south", 31.230,
        "east", 121.474, "west", 121.470, "shape", "polygon",
        "points", points, "coordinateSystem", "GCJ02");
    db.put("places", place);
    String backup = db.exportJson();
    db.importJson(backup);
    assertTrue(Store.polygon(db.one("places", "drawn")).valid());
    JSONObject broken = parse(backup);
    broken.getJSONArray("places").getJSONObject(0).put("points", new JSONArray());
    try {
      db.importJson(broken.toString());
      fail("corrupt polygon should fail");
    } catch (IllegalArgumentException expected) {
    }
    assertTrue(Store.polygon(db.one("places", "drawn")).valid());
  }

  @Test
  public void legacyBackupRestoresPlaceWithoutChangingItsCoordinates() {
    JSONObject place = obj("id", "library", "name", "图书馆", "lat", 31.23, "lng", 121.47,
        "radius", 200);
    db.put("places", place);
    JSONObject plan = task("atLibrary", "presence");
    set(plan, "place", "library");
    db.put("plans", plan);
    JSONObject backup = parse(db.exportJson());
    set(backup, "version", 1);
    db.importJson(backup.toString());
    JSONObject saved = db.one("places", "library");
    assertEquals("WGS84", saved.optString("coordinateSystem"));
    assertEquals(31.23, saved.optDouble("lat"), 0.000001);
    assertTrue(new Rules.Rect(saved.optDouble("north"), saved.optDouble("south"),
        saved.optDouble("east"), saved.optDouble("west")).valid());
    assertFalse(saved.has("radius"));
    assertNotNull(db.one("plans", "atLibrary"));
  }

  @Test
  public void versionTwoBackupRestoresRectangleAndKeepsPlaceReference() {
    JSONObject place = obj("id", "library2", "name", "阅览室", "lat", 31.23,
        "lng", 121.47, "radius", 300, "coordinateSystem", "GCJ02");
    db.put("places", place);
    JSONObject plan = task("atLibrary2", "presence");
    set(plan, "place", "library2");
    db.put("plans", plan);
    JSONObject backup = parse(db.exportJson());
    set(backup, "version", 2);
    db.importJson(backup.toString());
    JSONObject restored = db.one("places", "library2");
    assertEquals("GCJ02", restored.optString("coordinateSystem"));
    assertTrue(new Rules.Rect(restored.optDouble("north"), restored.optDouble("south"),
        restored.optDouble("east"), restored.optDouble("west")).valid());
    assertEquals("library2", db.one("plans", "atLibrary2").optString("place"));
  }

  @Test
  public void invalidBackupLeavesMonitoringAndRecordsUntouched() {
    JSONObject settings = db.settings();
    set(settings, "monitor", true);
    db.put("state", settings);
    db.put("plans", task("kept", "check"));
    JSONObject backup = parse(db.exportJson());
    JSONArray plans = backup.optJSONArray("plans");
    JSONObject bad = plans.optJSONObject(0);
    set(bad, "metric", "presence");
    set(bad, "place", "missing");
    try {
      db.prepareImport(backup.toString());
      fail("missing place must be rejected before any runtime change");
    } catch (IllegalArgumentException expected) {
    }
    assertTrue(db.settings().optBoolean("monitor"));
    assertNotNull(db.one("plans", "kept"));
  }

  @Test
  public void upgradeMarksOldPlacesWithoutReplacingPlans() {
    c.deleteDatabase("discipline-migration-fixture.db");
    java.io.File file = c.getDatabasePath("discipline-migration-fixture.db");
    try (android.database.sqlite.SQLiteDatabase raw =
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)) {
      raw.execSQL("CREATE TABLE places (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)");
      raw.execSQL("CREATE TABLE plans (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)");
      raw.execSQL("CREATE TABLE state (id TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)");
      raw.execSQL("INSERT INTO places VALUES (?, ?)", new Object[] {"old",
          obj("id", "old", "lat", 31.23, "lng", 121.47, "radius", 200).toString()});
      raw.execSQL("INSERT INTO plans VALUES (?, ?)", new Object[] {"keep",
          obj("id", "keep", "name", "已保存目标").toString()});
      raw.beginTransaction();
      try {
        db.onUpgrade(raw, 1, 3);
        raw.setTransactionSuccessful();
      } finally {
        raw.endTransaction();
      }
      try (android.database.Cursor cursor = raw.rawQuery("SELECT data FROM places WHERE id='old'", null)) {
        assertTrue(cursor.moveToFirst());
        JSONObject migrated = parse(cursor.getString(0));
        assertEquals("WGS84", migrated.optString("coordinateSystem"));
        assertTrue(new Rules.Rect(migrated.optDouble("north"), migrated.optDouble("south"),
            migrated.optDouble("east"), migrated.optDouble("west")).valid());
      }
      try (android.database.Cursor cursor = raw.rawQuery("SELECT count(*) FROM plans", null)) {
        assertTrue(cursor.moveToFirst());
        assertEquals(1, cursor.getInt(0));
      }
    } finally {
      c.deleteDatabase("discipline-migration-fixture.db");
    }
  }

  @Test
  public void crossMidnightKeepsConfiguredDuration() {
    JSONObject s = db.settings();
    set(s, "capHours", 8);
    db.put("state", s);
    JSONObject i = task("midnight", "check");
    long start = Rules.at(Rules.date(now), 23 * 60);
    set(i, "due", start);
    e.fail(i, now);
    JSONObject p = db.all("penalties").get(0);
    assertEquals(120 * Rules.MINUTE, p.optLong("end") - p.optLong("start"));
  }

  @Test
  public void onceLocationTaskStartsAtCreation() {
    long deadline = Rules.at(Rules.date(now).plusDays(4), 0);
    JSONObject p =
        obj(
            "id",
            "dayOnly",
            "name",
            "指定日期到馆",
            "created",
            now,
            "deadline",
            deadline,
            "cycle",
            "once",
            "metric",
            "presence",
            "target",
            60,
            "place",
            "library",
            "from",
            0,
            "until",
            1440);
    db.put("plans", p);
    e.tick();
    JSONObject i = db.all("instances").get(0);
    assertEquals(now, i.optLong("start"));
  }

  @Test
  public void completedSettlementCannotBeRewrittenByStaleDialog() {
    JSONObject i = task("stale", "check");
    db.put("instances", i);
    e.tick();
    try {
      e.manual(i, 1, "stale dialog", false);
      fail("must reject settled edit");
    } catch (IllegalStateException expected) {
    }
    assertEquals("failed", db.one("instances", "stale").optString("status"));
  }
}
