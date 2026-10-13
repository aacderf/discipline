package com.hl.discipline;

import static com.hl.discipline.Store.*;
import static org.junit.Assert.*;
import android.content.*;
import android.graphics.Point;
import android.os.Build;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaceRepairDeviceTest {
  private Context context;
  private Store db;
  private UiDevice device; private long oldIdle;

  @Before public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(context.getPackageName().endsWith(".qa"));
    assertTrue(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"));
    context.stopService(new Intent(context, LocationService.class));
    context.stopService(new Intent(context, FallbackBlockService.class));
    context.stopService(new Intent(context, FocusCompanionService.class));
    db = Store.get(context);
    db.atomic(() -> { for (String table : Store.TABLES) db.getWritableDatabase().delete(table, null, null); });
    context.getSharedPreferences("PlaceActivity", 0).edit().putBoolean("mapPrivacyAccepted", true)
        .putBoolean("locationPrivacyAccepted", false).commit();
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    oldIdle = Configurator.getInstance().getWaitForIdleTimeout();
    Configurator.getInstance().setWaitForIdleTimeout(200);
  }

  @After public void restore() { Configurator.getInstance().setWaitForIdleTimeout(oldIdle); }

  private JSONObject place() throws JSONException {
    Rules.Rect r = Rules.Rect.around(31.2304, 121.4737, 100);
    JSONArray points = new JSONArray().put(new JSONArray().put(r.north).put(r.west))
        .put(new JSONArray().put(r.north).put(r.east)).put(new JSONArray().put(r.south).put(r.east))
        .put(new JSONArray().put(r.south).put(r.west));
    return obj("id", "map-repair", "name", "地图校验", "lat", 31.2304, "lng", 121.4737,
        "north", r.north, "south", r.south, "east", r.east, "west", r.west,
        "coordinateSystem", "GCJ02", "shape", "polygon", "points", points);
  }

  private void tap(String text) {
    text(text); UiObject2 target = device.findObjects(By.text(text)).stream().max(java.util.Comparator.comparingInt(item -> item.getVisibleBounds().centerY())).orElse(null);
    assertNotNull(text, target); target.click();
  }

  private UiObject2 text(String text) {
    InstrumentationRegistry.getInstrumentation().getUiAutomation().clearCache();
    UiObject2 result = device.wait(Until.findObject(By.textContains(text)), 6000);
    if (result == null) { device.takeScreenshot(new File(context.getExternalCacheDir(), "map-failure.png"));
      try { device.dumpWindowHierarchy(new File(context.getExternalCacheDir(), "map-failure.xml")); } catch (Exception ignored) { } }
    assertNotNull(text, result); return result;
  }

  @Test public void mapTapAndSaveKeepOnePlaceThenDelete() throws Exception {
    db.put("places", place());
    Intent intent = new Intent(context, ComposePlaceActivity.class).putExtra("id", "map-repair");
    try (ActivityScenario<ComposePlaceActivity> scenario = ActivityScenario.launch(intent)) {
      assertTrue(device.wait(Until.hasObject(By.text("确认范围")), 20_000));
      device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
      text("确认范围"); // A plain map tap must not erase the saved boundary.
      if (!projectionReady(scenario)) {
        tap("重画"); text("地图尚未准备好"); text("确认范围");
        assertTrue(Store.polygon(db.one("places", "map-repair")).valid());
      }
      scenario.recreate(); text("确认范围");
      tap("确认范围"); tap("保存修改");
      device.waitForIdle();
      assertEquals(1, db.all("places").size());
      assertNotNull(db.one("places", "map-repair"));
      assertTrue(Store.polygon(db.one("places", "map-repair")).valid());
      assertNotNull(db.prepareImport(db.exportJson()));
    }
    try (ActivityScenario<ComposePlaceActivity> scenario = ActivityScenario.launch(intent)) {
      UiObject2 delete = device.wait(Until.findObject(By.desc("删除当前地点")), 6000);
      assertNotNull(delete); delete.click(); tap("删除地点"); device.waitForIdle();
      assertNull(db.one("places", "map-repair"));
    }
  }

  private boolean projectionReady(ActivityScenario<ComposePlaceActivity> scenario) {
    java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
    scenario.onActivity(activity -> {
      try {
        java.lang.reflect.Method method = ComposePlaceActivity.class.getDeclaredMethod("projectionReady");
        method.setAccessible(true); ready.set((boolean) method.invoke(activity));
      } catch (Exception e) { throw new AssertionError(e); }
    });
    return ready.get();
  }

  @Test public void vertexDeletionClearAndUndoOnlyChangeDraftUntilSave() throws Exception {
    db.put("places", place());
    try (ActivityScenario<ComposePlaceActivity> scenario = ActivityScenario.launch(
        new Intent(context, ComposePlaceActivity.class).putExtra("id", "map-repair"))) {
      text("确认范围");
      UiObject2 next = device.wait(Until.findObject(By.desc("下一个边界点")), 6000);
      assertNotNull(next); next.click(); tap("删除选点"); text("选点已删除");
      device.takeScreenshot(new File(context.getExternalCacheDir(), "map-edit-0.11.8.png"));
      assertEquals(4, Store.polygon(db.one("places", "map-repair")).points.size());
      scenario.recreate(); text("确认范围");
      tap("撤销选区修改"); text("已撤销选区修改");
      tap("清空选区"); text("选区已清空"); scenario.recreate(); text("选区已清空");
      scenario.onActivity(activity -> {
        try {
          java.lang.reflect.Field bounds = ComposePlaceActivity.class.getDeclaredField("rect");
          bounds.setAccessible(true); assertNull(bounds.get(activity));
        } catch (Exception e) { throw new AssertionError(e); }
      });
      tap("撤销选区修改"); text("确认范围");
      next = device.wait(Until.findObject(By.desc("下一个边界点")), 6000); assertNotNull(next);
      next.click(); tap("删除选点"); tap("确认范围"); tap("保存修改"); device.waitForIdle();
      assertEquals(3, Store.polygon(db.one("places", "map-repair")).points.size());
      assertNotNull(db.prepareImport(db.exportJson()));
    }
  }

  @Test public void savedPlaceListHasDirectDeletionAndExplainsActiveReferences() throws Exception {
    db.put("places", place());
    db.put("plans", obj("id", "linked", "name", "到场计划", "place", "map-repair"));
    try (ActivityScenario<ComposePlaceActivity> scenario = ActivityScenario.launch(new Intent(context, ComposePlaceActivity.class))) {
      text("已存地点"); tap("已存地点");
      UiObject2 delete = device.wait(Until.findObject(By.desc("删除地点地图校验")), 6000);
      assertNotNull(delete); delete.click(); text("地点仍在使用"); text("到场计划"); tap("知道了");
      assertNotNull(db.one("places", "map-repair")); db.remove("plans", "linked");
      tap("已存地点"); delete = device.wait(Until.findObject(By.desc("删除地点地图校验")), 6000);
      assertNotNull(delete); delete.click(); tap("删除地点"); text("还没有保存地点");
      assertNull(db.one("places", "map-repair"));
    }
  }

  @Test public void nativeMapRedrawCancelRotationAndStroke() throws Exception {
    db.put("places", place());
    Intent intent = new Intent(context, ComposePlaceActivity.class).putExtra("id", "map-repair");
    try (ActivityScenario<ComposePlaceActivity> scenario = ActivityScenario.launch(intent)) {
      text("确认范围");
      org.junit.Assume.assumeTrue("Native AMap projection unavailable on this emulator", projectionReady(scenario));
      tap("重画"); tap("取消绘制"); text("已恢复原范围");
      tap("重画");
      scenario.recreate(); text("确认范围"); // Rotation during redraw restores its original snapshot.
      tap("重画"); text("取消绘制");
      int x = device.getDisplayWidth() / 2, y = device.getDisplayHeight() / 2;
      int radius = device.getDisplayWidth() / 7;
      Point[] stroke = new Point[33];
      for (int i = 0; i < stroke.length; i++) stroke[i] = new Point(
          x + (int) (radius * Math.cos(i * Math.PI / 16)), y + (int) (radius * Math.sin(i * Math.PI / 16)));
      device.swipe(stroke, 3);
      text("确认范围");
      device.takeScreenshot(new File(context.getExternalCacheDir(), "map-repaired.png"));
      tap("确认范围"); tap("保存修改");
      device.waitForIdle();
      assertEquals(1, db.all("places").size());
      assertNotNull(db.one("places", "map-repair"));
      assertTrue(Store.polygon(db.one("places", "map-repair")).valid());
      assertNotNull(db.prepareImport(db.exportJson()));
    }
  }

  @Test public void deletionProtectsActivePlansAndRetainsHistoryAndBackup() throws Exception {
    db.put("places", place());
    db.put("plans", obj("id", "plan", "name", "关联计划", "place", "map-repair", "metric", "presence", "target", 10));
    try { PlaceDeletion.delete(db, "map-repair"); fail("An active plan must retain its place"); }
    catch (IllegalStateException expected) { assertNotNull(db.one("places", "map-repair")); }
    db.remove("plans", "plan");
    long now = System.currentTimeMillis();
    db.put("sessions", obj("id", "arrival", "kind", "presence", "place", "map-repair", "task", "", "start", now - 60_000, "end", now));
    db.put("state", obj("id", "location", "place", "map-repair", "reliable", true, "validUntil", now + 60_000));
    PlaceDeletion.delete(db, "map-repair");
    assertTrue(db.one("places", "map-repair").optBoolean("archived"));
    assertNotNull(db.one("sessions", "arrival"));
    assertFalse(db.one("state", "location").optBoolean("reliable"));
    assertNotNull(db.prepareImport(db.exportJson()));
  }
}
