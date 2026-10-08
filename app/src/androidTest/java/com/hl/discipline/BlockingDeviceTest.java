package com.hl.discipline;

import static com.hl.discipline.Store.*;
import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BlockingDeviceTest {
  @Test
  public void fallbackPausesWithScreenOffAndYieldsToPreciseService() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    Configurator.getInstance()
        .setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    Store db = Store.get(c);
    device.executeShellCommand("settings delete secure enabled_accessibility_services");
    device.executeShellCommand("settings put secure accessibility_enabled 0");
    device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
    device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    device.wakeUp();
    try {
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", true);
      set(settings, "appLimits", new JSONArray().put(obj("package", "com.android.chrome",
          "dailyMinutes", 180, "weeklyMinutes", 0, "enabled", true)));
      db.put("state", settings);
      FallbackBlockService.sync(c);
      long connectedBy = SystemClock.elapsedRealtime() + 10_000;
      while (!fallbackConnected(db) && SystemClock.elapsedRealtime() < connectedBy)
        SystemClock.sleep(250);
      assertTrue(fallbackConnected(db));
      SystemClock.sleep(2_000);
      int beforeSleep = AppUsageLimits.debugForegroundQueries.get();
      device.sleep();
      SystemClock.sleep(3_000);
      assertTrue("Screen off should stop foreground queries",
          AppUsageLimits.debugForegroundQueries.get() - beforeSleep <= 1);
      device.wakeUp();
      SystemClock.sleep(2_000);
      assertTrue("Foreground queries should resume after wake",
          AppUsageLimits.debugForegroundQueries.get() > beforeSleep + 1);
      device.executeShellCommand("settings put secure enabled_accessibility_services " +
          c.getPackageName() + "/com.hl.discipline.BlockService");
      device.executeShellCommand("settings put secure accessibility_enabled 1");
      long boundBy = SystemClock.elapsedRealtime() + 15_000;
      while (!serviceConnected(db) && SystemClock.elapsedRealtime() < boundBy)
        SystemClock.sleep(250);
      assertTrue("Precise service must connect", serviceConnected(db));
      SystemClock.sleep(3_500);
      int preciseQueries = AppUsageLimits.debugForegroundQueries.get();
      SystemClock.sleep(4_000);
      assertEquals("Fallback must yield foreground scans", preciseQueries,
          AppUsageLimits.debugForegroundQueries.get());
      device.executeShellCommand("settings delete secure enabled_accessibility_services");
      device.executeShellCommand("settings put secure accessibility_enabled 0");
      SystemClock.sleep(4_000);
      assertTrue("Fallback must resume after precise service disconnects",
          AppUsageLimits.debugForegroundQueries.get() > preciseQueries);
    } finally {
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", false);
      db.put("state", settings);
      FallbackBlockService.sync(c);
      device.executeShellCommand("settings delete secure enabled_accessibility_services");
      device.executeShellCommand("settings put secure accessibility_enabled 0");
      device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS default");
      device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW default");
      device.pressHome();
    }
  }

  @Test
  public void fallbackThirtyMinuteCostProbe() throws Exception {
    org.junit.Assume.assumeTrue("Run only when explicitly requested",
        "true".equals(InstrumentationRegistry.getArguments().getString("longRun")));
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    Store db = Store.get(c);
    String oldScreenTimeout = device.executeShellCommand("settings get system screen_off_timeout").trim();
    device.executeShellCommand("settings put system screen_off_timeout 3600000");
    device.wakeUp();
    device.executeShellCommand("settings delete secure enabled_accessibility_services");
    device.executeShellCommand("settings put secure accessibility_enabled 0");
    device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
    device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    try {
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", true);
      set(settings, "appLimits", new JSONArray().put(obj("package", "com.android.chrome",
          "dailyMinutes", 180, "weeklyMinutes", 0, "enabled", true)));
      db.put("state", settings);
      FallbackBlockService.sync(c);
      long connectedBy = SystemClock.elapsedRealtime() + 10_000;
      while (!fallbackConnected(db) && SystemClock.elapsedRealtime() < connectedBy)
        SystemClock.sleep(250);
      assertTrue(fallbackConnected(db));
      Intent chrome = c.getPackageManager().getLaunchIntentForPackage("com.android.chrome");
      assertNotNull(chrome);
      c.startActivity(chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      SystemClock.sleep(2_000);
      int scans = FallbackBlockService.debugScans.get();
      int queries = AppUsageLimits.debugFreshQueries.get();
      long cpu = android.os.Process.getElapsedCpuTime();
      long start = SystemClock.elapsedRealtime();
      SystemClock.sleep(30 * 60_000L);
      System.out.println("FallbackCost elapsedMs=" + (SystemClock.elapsedRealtime() - start) +
          " processCpuMs=" + (android.os.Process.getElapsedCpuTime() - cpu) +
          " scans=" + (FallbackBlockService.debugScans.get() - scans) +
          " freshUsageQueries=" + (AppUsageLimits.debugFreshQueries.get() - queries));
      assertTrue("Fallback service must stay connected", fallbackConnected(db));
    } finally {
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", false);
      db.put("state", settings);
      FallbackBlockService.sync(c);
      device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS default");
      device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW default");
      if (oldScreenTimeout.equals("null")) device.executeShellCommand("settings delete system screen_off_timeout");
      else device.executeShellCommand("settings put system screen_off_timeout " + oldScreenTimeout);
      device.pressHome();
    }
  }

  @Test
  public void fallbackLatencySeries() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    Store db = Store.get(c);
    String oldScreenTimeout = device.executeShellCommand("settings get system screen_off_timeout").trim();
    device.executeShellCommand("settings put system screen_off_timeout 1800000");
    device.wakeUp();
    device.executeShellCommand("settings delete secure enabled_accessibility_services");
    device.executeShellCommand("settings put secure accessibility_enabled 0");
    device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
    device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    Intent chrome = c.getPackageManager().getLaunchIntentForPackage("com.android.chrome");
    assertNotNull(chrome);
    try {
      if (AppUsageLimits.read(c, "com.android.chrome", System.currentTimeMillis()).todayMillis < Rules.MINUTE) {
        c.startActivity(chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(65_000);
        device.pressHome();
      }
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", true);
      set(settings, "appLimits", new JSONArray().put(obj("package", "com.android.chrome",
          "dailyMinutes", 1, "weeklyMinutes", 0, "enabled", true)));
      db.put("state", settings);
      FallbackBlockService.sync(c);
      long connectedBy = SystemClock.elapsedRealtime() + 10_000;
      while (!fallbackConnected(db) && SystemClock.elapsedRealtime() < connectedBy)
        SystemClock.sleep(250);
      assertTrue(fallbackConnected(db));
      long[] latency = new long[20];
      for (int i = 0; i < latency.length; i++) {
        device.wakeUp();
        device.pressHome();
        assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5_000));
        SystemClock.sleep(300);
        long opened = SystemClock.elapsedRealtime();
        c.startActivity(chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull("Trial " + i, device.wait(Until.findObject(By.text("先完成约定")), 20_000));
        latency[i] = SystemClock.elapsedRealtime() - opened;
        System.out.println("FallbackTiming trial=" + i + " opened=" + opened + " visible=" +
            SystemClock.elapsedRealtime() + " latency=" + latency[i]);
      }
      Arrays.sort(latency);
      System.out.println("FallbackTiming summary p50=" + latency[9] + " p95=" + latency[18]);
    } finally {
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", false);
      db.put("state", settings);
      FallbackBlockService.sync(c);
      device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS default");
      device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW default");
      if (oldScreenTimeout.equals("null")) device.executeShellCommand("settings delete system screen_off_timeout");
      else device.executeShellCommand("settings put system screen_off_timeout " + oldScreenTimeout);
      device.pressHome();
    }
  }

  @Test
  public void fallbackQuotaOverlayWithoutAccessibility() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    String oldScreenTimeout = device.executeShellCommand("settings get system screen_off_timeout").trim();
    device.executeShellCommand("settings put system screen_off_timeout 1800000");
    device.wakeUp();
    Store db = Store.get(c);
    device.executeShellCommand("settings delete secure enabled_accessibility_services");
    device.executeShellCommand("settings put secure accessibility_enabled 0");
    device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS allow");
    device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    try {
      assertTrue("Usage access is required for the fallback", AppUsageLimits.hasAccess(c));
      assertTrue("System overlay access is required for the fallback",
          android.provider.Settings.canDrawOverlays(c));
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", true);
      set(settings, "appLimits", new JSONArray().put(obj("package", "com.android.chrome",
          "dailyMinutes", 1, "weeklyMinutes", 0, "enabled", true)));
      db.put("state", settings);
      FallbackBlockService.sync(c);
      long connectedBy = SystemClock.elapsedRealtime() + 10_000;
      while (!fallbackConnected(db) && SystemClock.elapsedRealtime() < connectedBy)
        SystemClock.sleep(250);
      assertTrue("Fallback service must start with its own notification", fallbackConnected(db));
      Intent chrome = c.getPackageManager().getLaunchIntentForPackage("com.android.chrome");
      assertNotNull(chrome);
      long openedAt = SystemClock.elapsedRealtime();
      c.startActivity(chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      UiObject2 blocked = device.wait(Until.findObject(By.text("先完成约定")), 90_000);
      assertNotNull("Fallback must eventually block Chrome after the one-minute quota", blocked);
      System.out.println("Fallback daily overlay after opening Chrome: " +
          (SystemClock.elapsedRealtime() - openedAt) + " ms");
      assertNotNull(device.findObject(By.text("原因：每日使用已达上限")));
      device.findObject(By.text("返回桌面")).click();
      assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5_000));
      set(settings, "appLimits", new JSONArray().put(obj("package", "com.android.chrome",
          "dailyMinutes", 0, "weeklyMinutes", 1, "enabled", true)));
      db.put("state", settings);
      SystemClock.sleep(10_500);
      c.startActivity(chrome);
      assertNotNull(device.wait(Until.findObject(By.text("原因：本周使用已达上限")), 15_000));
      UiObject2 reason = device.findObject(By.clazz("android.widget.EditText"));
      assertNotNull(reason);
      reason.setText("QA fallback emergency");
      device.pressBack();
      device.findObject(By.text("申请应急解锁")).click();
      assertNotNull(device.wait(Until.findObject(By.text("临时放行 10 分钟")), 65_000));
      device.findObject(By.text("临时放行 10 分钟")).click();
      assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5_000));
      assertNotNull(db.one("state", "pass:com.android.chrome"));
      db.remove("state", "pass:com.android.chrome");
      assertNotNull(device.wait(Until.findObject(By.text("先完成约定")), 5_000));
      set(settings.optJSONArray("appLimits").getJSONObject(0), "enabled", false);
      db.put("state", settings);
      FallbackBlockService.sync(c);
      assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5_000));
      set(settings.optJSONArray("appLimits").getJSONObject(0), "enabled", true);
      db.put("state", settings);
      FallbackBlockService.sync(c);
      assertNotNull(device.wait(Until.findObject(By.text("先完成约定")), 10_000));
      device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS default");
      assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5_000));
    } finally {
      JSONObject settings = db.settings();
      set(settings, "fallbackEnabled", false);
      db.put("state", settings);
      FallbackBlockService.sync(c);
      device.executeShellCommand("appops set " + c.getPackageName() + " GET_USAGE_STATS default");
      device.executeShellCommand("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW default");
      if (oldScreenTimeout.equals("null")) device.executeShellCommand("settings delete system screen_off_timeout");
      else device.executeShellCommand("settings put system screen_off_timeout " + oldScreenTimeout);
      device.pressHome();
    }
  }

  @Test
  public void actualOverlayEmergencyAndExpiry() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    Configurator.getInstance()
        .setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    Store db = Store.get(c);
    db.atomic(
        () -> {
          for (String t : Store.TABLES) db.getWritableDatabase().delete(t, null, null);
        });
    device.executeShellCommand(
        "settings put secure enabled_accessibility_services " + c.getPackageName() + "/com.hl.discipline.BlockService");
    device.executeShellCommand("settings put secure accessibility_enabled 1");
    try {
      long bindDeadline = SystemClock.elapsedRealtime() + 15_000;
      while (!serviceConnected(db) && SystemClock.elapsedRealtime() < bindDeadline)
        SystemClock.sleep(250);
      assertTrue("Restriction service must bind before the target app opens",
          serviceConnected(db));
      long now = System.currentTimeMillis();
      JSONObject p =
          obj(
              "id",
              "qa-block",
              "name",
              "测试限制",
              "apps",
              new JSONArray(Arrays.asList("com.android.chrome")),
              "start",
              now - 1000,
              "end",
              now + 600000,
              "trial",
              false);
      db.put("penalties", p);
      assertNotNull("The restriction fixture must be active", new Engine(c).blocked("com.android.chrome", now));
      Intent chrome = c.getPackageManager().getLaunchIntentForPackage("com.android.chrome");
      assertNotNull(chrome);
      c.startActivity(chrome.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      File dir = new File(c.getExternalFilesDir(null), "qa");
      dir.mkdirs();
      UiObject2 blockedTitle = device.wait(Until.findObject(By.text("先完成约定")), 30000);
      if (blockedTitle == null) device.takeScreenshot(new File(dir, "10-app-block-missing.png"));
      assertNotNull(
          "Block overlay must appear; service error: " +
              (db.one("state", "blockHeartbeat") == null ? "no heartbeat" :
                  db.one("state", "blockHeartbeat").optString("error")), blockedTitle);
      device.takeScreenshot(new File(dir, "10-app-block.png"));
      UiObject2 reason = device.findObject(By.clazz("android.widget.EditText"));
      assertNotNull(reason);
      reason.setText("QA emergency");
      device.pressBack();
      device.findObject(By.text("申请应急解锁")).click();
      assertNotNull(device.wait(Until.findObject(By.textStartsWith("请等待")), 3000));
      // Wait for the actual UI countdown, without changing production time or bypassing the guard.
      assertNotNull(device.wait(Until.findObject(By.text("临时放行 10 分钟")), 65000));
      device.findObject(By.text("临时放行 10 分钟")).click();
      assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5000));
      assertNotNull(db.one("state", "pass:com.android.chrome"));
      db.remove("state", "pass:com.android.chrome");
      assertNotNull(device.wait(Until.findObject(By.text("先完成约定")), 5000));
      set(p, "end", System.currentTimeMillis() - 1);
      db.put("penalties", p);
      assertTrue(device.wait(Until.gone(By.text("先完成约定")), 5000));
      assertTrue(db.all("events").stream().anyMatch(e -> e.optString("type").equals("unlock")));
    } finally {
      device.executeShellCommand("settings delete secure enabled_accessibility_services");
      device.executeShellCommand("settings put secure accessibility_enabled 0");
      device.pressHome();
    }
  }

  private static boolean serviceConnected(Store db) {
    JSONObject state = db.one("state", "blockHeartbeat");
    return state != null && state.optString("error").isEmpty()
        && System.currentTimeMillis() - state.optLong("time") < 15_000;
  }

  private static boolean fallbackConnected(Store db) {
    JSONObject state = db.one("state", "fallbackHeartbeat");
    return state != null && state.optString("error").isEmpty()
        && System.currentTimeMillis() - state.optLong("time") < 15_000;
  }
}
