package com.hl.discipline;

import static com.hl.discipline.Store.*;
import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.util.Arrays;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocationPenaltyDeviceTest {
  private Context c;
  private Store db;
  private Engine engine;

  @Before public void setup() {
    c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(c.getPackageName().endsWith(".qa"));
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    c.stopService(new Intent(c, FallbackBlockService.class));
    c.stopService(new Intent(c, FocusCompanionService.class));
    c.stopService(new Intent(c, LocationService.class));
    db = Store.get(c);
    db.atomic(() -> { for (String table : Store.TABLES) db.getWritableDatabase().delete(table, null, null); });
    engine = new Engine(c);
  }

  @Test public void dwellSettlementIncludesBoundedLiveTimeAndSurvivesBackup() throws Exception {
    long now = System.currentTimeMillis(), due = now - 1_000, start = due - 60 * Rules.MINUTE;
    JSONObject settings = db.settings();
    set(settings, "trial", false);
    db.put("state", settings);
    Rules.Rect rect = Rules.Rect.around(31, 121, 200);
    db.put("places", obj("id", "library", "name", "图书馆", "lat", 31, "lng", 121, "north", rect.north,
        "south", rect.south, "east", rect.east, "west", rect.west));
    JSONObject task = obj("id", "dwell:" + due, "planId", "dwell", "name", "地点测试",
        "metric", "presence", "place", "library", "target", 60, "manual", 0,
        "created", start, "start", start, "due", due, "deadline", due, "cycle", "once",
        "status", "open", "from", 0, "until", 1440, "penalty", 0,
        "penaltyMode", "remainingDwell", "apps", new JSONArray(Arrays.asList("com.android.chrome")));
    JSONObject plan = copy(task); set(plan, "id", "dwell"); plan.remove("planId"); set(plan, "archived", true);
    db.put("plans", plan); db.put("instances", task);
    engine.segment("presence", "library", "", start, start + 15 * Rules.MINUTE);
    engine.segment("coverage", "", "", start, due);
    db.put("state", obj("id", "location", "time", due - 30_000, "place", "library",
        "reliable", true, "validUntil", due + 30_000));
    assertEquals(15.5, engine.progress(task, due), .00001);
    engine.settle(task, now); engine.settle(task, now);
    assertEquals(1, db.all("penalties").size());
    JSONObject penalty = db.all("penalties").get(0);
    assertEquals(44 * Rules.MINUTE + 30_000, penalty.optLong("end") - penalty.optLong("start"));
    assertEquals(due, penalty.optLong("start"));
    assertNotNull(engine.blocked("com.android.chrome", now));
    JSONObject backup = db.prepareImport(db.exportJson());
    db.importPrepared(backup);
    assertEquals("remainingDwell", db.one("plans", "dwell").optString("penaltyMode"));
    assertEquals("remainingDwell", db.one("instances", task.optString("id")).optString("penaltyMode"));
    assertEquals(1, db.all("penalties").size());
  }

  @Test public void fallbackTaskPenaltyReblocksImmediateReentryWithoutQuota() throws Exception {
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    long oldIdle = Configurator.getInstance().getWaitForIdleTimeout();
    Configurator.getInstance().setWaitForIdleTimeout(100);
    String oldServices = device.executeShellCommand("settings get secure enabled_accessibility_services").trim();
    String oldEnabled = device.executeShellCommand("settings get secure accessibility_enabled").trim();
    device.executeShellCommand("settings delete secure enabled_accessibility_services");
    device.executeShellCommand("settings put secure accessibility_enabled 0");
    device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
    device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    device.wakeUp(); device.pressHome();
    try {
      long now = System.currentTimeMillis();
      JSONObject settings = db.settings(); set(settings, "fallbackEnabled", true); set(settings, "appLimits", new JSONArray());
      db.put("state", settings);
      db.put("penalties", obj("id", "reentry", "name", "地点测试", "start", now - 1000,
          "end", now + 10 * Rules.MINUTE, "trial", false,
          "apps", new JSONArray(Arrays.asList("com.android.chrome"))));
      assertTrue(FallbackBlockService.hasRule(c));
      FallbackBlockService.sync(c);
      Intent chrome = c.getPackageManager().getLaunchIntentForPackage("com.android.chrome");
      assertNotNull(chrome);
      for (int attempt = 0; attempt < 2; attempt++) {
        long opened = SystemClock.elapsedRealtime();
        c.startActivity(new Intent(chrome).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull(device.wait(Until.findObject(By.text("应用已受限").pkg(c.getPackageName())), 5_000));
        System.out.println("ReentryBlocking attempt=" + attempt + " visibleMs=" + (SystemClock.elapsedRealtime() - opened));
        assertNotNull(device.wait(Until.findObject(By.text("返回桌面").pkg(c.getPackageName())), 8000));
        device.findObject(By.text("返回桌面").pkg(c.getPackageName())).click();
        assertTrue(device.wait(Until.gone(By.text("应用已受限").pkg(c.getPackageName())), 2000));
        // Reopen well within the former ten-second exemption.
        SystemClock.sleep(400);
      }
      db.put("state", obj("id", "pass:com.android.chrome", "until", System.currentTimeMillis() + Rules.MINUTE));
      c.startActivity(new Intent(chrome).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      SystemClock.sleep(1500);
      assertNull(device.findObject(By.text("应用已受限").pkg(c.getPackageName())));
    } finally {
      Configurator.getInstance().setWaitForIdleTimeout(oldIdle);
      JSONObject settings = db.settings(); set(settings, "fallbackEnabled", false); db.put("state", settings);
      FallbackBlockService.sync(c); device.pressHome();
      device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS default");
      device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW default");
      device.executeShellCommand(oldServices.equals("null") ? "settings delete secure enabled_accessibility_services"
          : "settings put secure enabled_accessibility_services " + oldServices);
      device.executeShellCommand(oldEnabled.equals("null") ? "settings delete secure accessibility_enabled"
          : "settings put secure accessibility_enabled " + oldEnabled);
    }
  }
}
