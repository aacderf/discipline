package com.hl.discipline;

import static com.hl.discipline.Store.*;

import android.app.*;
import android.content.*;
import android.location.*;
import android.os.*;
import java.util.*;
import org.json.JSONObject;
import com.amap.api.maps.model.LatLng;
import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;

public final class LocationService extends Service implements LocationListener {
  public static volatile boolean running = false;
  private Store db;
  private Engine engine;
  private LocationManager manager;
  private AMapLocationClient fused;
  private final LocationQuality quality = new LocationQuality();
  private String positionSystem = "";
  private final Handler handler = new Handler();
  private final Map<String, Rules.Fence> fences = new HashMap<>();
  private final Map<String, String> geometryKeys = new HashMap<>();
  private final Map<String, Boundary> boundaries = new HashMap<>();
  private long lastFix = 0;
  private long lastFixElapsed = 0;
  private boolean lastAccurate = false;
  private final Runnable pulse =
      new Runnable() {
        public void run() {
          if (db == null) return;
          long now = System.currentTimeMillis();
          long elapsed = SystemClock.elapsedRealtime();
          syncBoundaries();
          JSONObject l = db.one("state", "location");
          Rules.Fence active = l == null ? null : fences.get(l.optString("place"));
          for (Map.Entry<String, Rules.Fence> entry : fences.entrySet()) {
            long[] earned = entry.getValue().advance(elapsed);
            if (entry.getValue() == active && db.one("places", entry.getKey()) != null)
              credit(entry.getKey(), earned, now, elapsed);
          }
          if (l != null && active != null) {
            boolean reliable = active.inside && db.one("places", l.optString("place")) != null;
            set(l, "time", now);
            set(l, "reliable", reliable);
            set(l, "validUntil", reliable ? now + active.validUntil() - elapsed : now);
            if (!reliable) {
              set(l, "status", "地点暂未确认，计时已暂停");
              set(l, "place", "");
            }
            db.put("state", l);
          }
          engine.tick();
          handler.postDelayed(this, 5_000);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    db = Store.get(this);
    engine = new Engine(this);
    manager = getSystemService(LocationManager.class);
    // Never carry a previously confirmed location across a service restart.
    JSONObject previous = db.one("state", "location");
    if (previous != null) {
      set(previous, "reliable", false);
      db.put("state", previous);
    }
  }

  @Override
  public int onStartCommand(Intent i, int flags, int id) {
    try {
      Scheduler.channels(this);
      if (!AndroidCompat.promote(this, 1, Scheduler.status(this, "等待定位 · 手机留在地点内才会计时"),
          Build.VERSION.SDK_INT >= 29 ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0))
        return START_NOT_STICKY;
      manager.removeUpdates(this);
      if (!PositionSource.consented(this) && fused != null) { PositionSource.close(fused); fused = null; }
      for (String provider :
          new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER})
        if (manager.getAllProviders().contains(provider))
          manager.requestLocationUpdates(provider, 10_000, 0, this);
      if (fused == null && PositionSource.consented(this)) {
        try {
          fused = PositionSource.start(this, false, location -> {
            if (location != null && location.getErrorCode() == 0)
              handler.post(() -> { if (running) acceptPosition(location,
                  "GCJ02".equals(location.getCoordType()), "高德融合定位"); });
          });
        } catch (Exception ex) {
          db.log("gap", "融合定位暂不可用，使用系统定位");
        }
      }
      handler.removeCallbacks(pulse);
      handler.post(pulse);
      JSONObject s = db.settings();
      set(s, "monitor", true);
      db.put("state", s);
      running = true;
    } catch (SecurityException ex) {
      running = false;
      AndroidCompat.serviceFailed(this, getClass().getName(), ex);
      stopSelf();
      return START_NOT_STICKY;
    } catch (RuntimeException ex) {
      running = false;
      AndroidCompat.serviceFailed(this, getClass().getName(), ex);
      stopSelf();
      return START_NOT_STICKY;
    }
    return START_STICKY;
  }

  @Override
  public void onLocationChanged(Location loc) {
    acceptPosition(loc, false, LocationManager.GPS_PROVIDER.equals(loc.getProvider()) ? "GPS" : "系统网络定位");
  }

  private void acceptPosition(Location loc, boolean gcj, String source) {
    long now = System.currentTimeMillis();
    long elapsed = SystemClock.elapsedRealtime();
    long sample = loc instanceof AMapLocation ? elapsed - (now - loc.getTime())
        : loc.getElapsedRealtimeNanos() / 1_000_000;
    if (elapsed - sample > LocationQuality.MAX_AGE || sample > elapsed || sample <= lastFixElapsed) return;
    boolean mock = Build.VERSION.SDK_INT >= 31 ? loc.isMock() : loc.isFromMockProvider();
    if (mock) {
      fences.values().forEach(Rules.Fence::reset); quality.reset(); lastAccurate = false;
      JSONObject previous = db.one("state", "location");
      if (previous != null) { set(previous, "reliable", false); set(previous, "place", "");
        set(previous, "validUntil", 0); set(previous, "status", "模拟定位，未计入地点时长"); db.put("state", previous); }
      return;
    }
    double lat = loc.getLatitude(), lng = loc.getLongitude();
    if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 85 || Math.abs(lng) > 180
        || !loc.hasAccuracy() || !Float.isFinite(loc.getAccuracy()) || loc.getAccuracy() <= 0) return;
    boolean canonicalMap = gcj || PositionSource.consented(this)
        || getSharedPreferences("PlaceActivity", 0).getBoolean("mapPrivacyAccepted", false);
    try {
      if (canonicalMap && !gcj) {
        LatLng point = MapCoordinates.gpsToMap(this, lat, lng); lat = point.latitude; lng = point.longitude;
      }
    } catch (RuntimeException ex) { return; }
    String crs = canonicalMap ? "GCJ02" : "WGS84";
    if (!positionSystem.equals(crs)) {
      quality.reset(); fences.values().forEach(Rules.Fence::reset); boundaries.clear();
      lastAccurate = false; positionSystem = crs;
    }
    LocationQuality.Decision decision = quality.consider(elapsed, sample, lat, lng,
        loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN);
    // Coarse fresh fixes keep the last precise point, but still start the bounded drift grace.
    // A good GPS/fused observation clears that grace; repeated poor fixes cannot extend it.
    boolean accurate =
        loc.hasAccuracy()
            && loc.getAccuracy() <= 100
            && decision == LocationQuality.Decision.ACCEPT;
    long observedAt = now - (elapsed - sample);
    if (accurate && lastAccurate && sample - lastFixElapsed <= 120_000)
      engine.segment("coverage", "", "", lastFix, observedAt);
    lastFix = observedAt;
    lastFixElapsed = sample;
    lastAccurate = accurate;
    String active = "", name = "", status = accurate ? "当前不在计划地点" : "定位精度不足";
    boolean reliable = false;
    if (decision == LocationQuality.Decision.SUSPECT) status = "正在核验位置偏移";
    List<JSONObject> places = syncBoundaries();
    JSONObject previous = db.one("state", "location");
    String priorPlace = previous == null ? "" : previous.optString("place");
    places.sort(Comparator.comparingInt(p -> p.optString("id").equals(priorPlace) ? 0 : 1));
    long priorEnd = 0, validUntil = now;
    for (JSONObject p : places) {
      Rules.Fence f = fences.computeIfAbsent(p.optString("id"), k -> new Rules.Fence());
      long[] earned;
      try {
        Boundary b = boundary(p, canonicalMap);
        earned = b.area != null
            ? f.acceptPolygon(sample, lat, lng, b.area, accurate ? loc.getAccuracy() : 99999)
            : f.acceptRect(sample, lat, lng, b.rect, accurate ? loc.getAccuracy() : 99999);
      } catch (RuntimeException ex) { earned = f.accept(sample, Double.NaN, 0, 99999); }
      long[] delivered = f.advance(elapsed);
      if (delivered != null) earned = earned == null ? delivered : new long[] {earned[0], delivered[1]};
      if (p.optString("id").equals(priorPlace) && earned != null) {
        credit(priorPlace, earned, now, elapsed);
        priorEnd = earned[1];
      }
      if (f.inside && active.isEmpty()) {
        active = p.optString("id");
        name = p.optString("name");
        reliable = true;
        if (!active.equals(priorPlace) && earned != null)
          credit(active, new long[] {Math.max(earned[0], priorEnd), earned[1]}, now, elapsed);
        validUntil = now + f.validUntil() - elapsed;
        status = f.inGrace() ? "定位偏移 · " + name + "，60 秒内继续计时" : "已到达 · " + name;
      }
    }
    db.put(
        "state",
        obj(
            "id",
            "location",
            "time",
            now,
            "lat",
            decision != LocationQuality.Decision.ACCEPT && previous != null ? previous.optDouble("lat", lat) : lat,
            "lng",
            decision != LocationQuality.Decision.ACCEPT && previous != null ? previous.optDouble("lng", lng) : lng,
            "coordinateSystem", crs,
            "observedAt", decision != LocationQuality.Decision.ACCEPT && previous != null
                ? previous.optLong("observedAt") : observedAt,
            "source", source,
            "accuracy",
            loc.getAccuracy(),
            "place",
            active,
            "reliable",
            reliable,
            "validUntil", validUntil,
            "status",
            status));
    engine.tick();
    getSystemService(NotificationManager.class).notify(1, Scheduler.status(this, status));
  }

  private List<JSONObject> syncBoundaries() {
    List<JSONObject> places = db.all("places");
    places.removeIf(p -> p.optBoolean("archived"));
    Set<String> present = new HashSet<>();
    for (JSONObject place : places) {
      String id = place.optString("id"), key = PlaceDeletion.geometryKey(place);
      present.add(id);
      if (geometryKeys.containsKey(id) && !key.equals(geometryKeys.get(id))) {
        fences.remove(id); boundaries.remove(id);
        PlaceDeletion.invalidate(db, id, "范围已调整，等待重新确认地点");
      }
      geometryKeys.put(id, key);
    }
    for (String id : new HashSet<>(geometryKeys.keySet())) if (!present.contains(id)) {
      fences.remove(id); boundaries.remove(id); geometryKeys.remove(id);
      PlaceDeletion.invalidate(db, id, "地点已移除，等待重新定位");
    }
    return places;
  }

  private static final class Boundary { Rules.Rect rect; Rules.PolygonArea area; }
  private Boundary boundary(JSONObject place, boolean mapCoordinates) {
    String id = place.optString("id");
    if (boundaries.containsKey(id)) return boundaries.get(id);
    boolean storedMap = place.optString("coordinateSystem", "WGS84").equals("GCJ02");
    if (storedMap && !mapCoordinates) throw new IllegalStateException("坐标系不一致");
    Boundary b = new Boundary();
    b.rect = new Rules.Rect(place.optDouble("north", Double.NaN), place.optDouble("south", Double.NaN),
        place.optDouble("east", Double.NaN), place.optDouble("west", Double.NaN));
    if (place.optString("shape").equals("polygon")) b.area = Store.polygon(place);
    if (mapCoordinates && !storedMap) {
      if (b.area != null) {
        List<double[]> converted = new ArrayList<>();
        for (double[] v : b.area.points) {
          LatLng point = MapCoordinates.gpsToMap(this, v[0], v[1]);
          converted.add(new double[] {point.latitude, point.longitude});
        }
        b.area = new Rules.PolygonArea(converted); b.rect = b.area.bounds;
      } else {
        LatLng nw = MapCoordinates.gpsToMap(this, b.rect.north, b.rect.west);
        LatLng se = MapCoordinates.gpsToMap(this, b.rect.south, b.rect.east);
        b.rect = new Rules.Rect(nw.latitude, se.latitude, se.longitude, nw.longitude);
      }
    }
    if (!b.rect.valid() || (b.area != null && !b.area.valid())) throw new IllegalStateException("地点范围无效");
    boundaries.put(id, b); return b;
  }

  private void credit(String place, long[] earned, long now, long elapsed) {
    if (earned == null || earned[1] <= earned[0]) return;
    long start = now - (elapsed - earned[0]), end = now - (elapsed - earned[1]);
    engine.segment("presence", place, "", start, end);
    // The bounded grace is an accepted part of the location observation, not an unbounded gap.
    engine.segment("coverage", "", "", start, end);
  }

  @Override
  public void onProviderDisabled(String provider) {
    // One provider can stop while the other still has valid fixes. Let the timeout bound it.
    handler.removeCallbacks(pulse);
    handler.post(pulse);
  }

  @Override
  public void onProviderEnabled(String p) {}

  @Override
  public void onStatusChanged(String p, int s, Bundle b) {}

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onDestroy() {
    running = false;
    handler.removeCallbacks(pulse);
    if (manager != null) try { manager.removeUpdates(this); }
    catch (SecurityException ignored) { /* Location may have been revoked while running. */ }
    PositionSource.close(fused); fused = null;
    if (db != null) {
      JSONObject l = db.one("state", "location");
      if (l != null) {
        set(l, "reliable", false);
        set(l, "status", "地点监测已停止");
        db.put("state", l);
      }
      db.log("gap", "地点监测停止，未确认时间不计入记录");
    }
    super.onDestroy();
  }
}
