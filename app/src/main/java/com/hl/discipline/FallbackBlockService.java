package com.hl.discipline;

import android.app.Service;
import android.app.KeyguardManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Optional, less immediate quota enforcement when accessibility is disconnected. */
public final class FallbackBlockService extends Service {
  private static final int NOTIFICATION_ID = 1202;
  static final AtomicInteger debugScans = new AtomicInteger();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean reading = new AtomicBoolean();
  private Store db;
  private Engine engine;
  private WindowManager windows;
  private View overlay;
  private ComposeBlockOverlayHost overlayHost;
  private String shown = "", error = "";
  private volatile String observed = "";
  private volatile long lastScan;
  private volatile boolean preciseHealthy;
  private long lastHeartbeat, waitStart, lastEngineTick;
  private volatile int generation;

  private void trace(String stage) {
    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
      android.util.Log.d("FallbackTiming", SystemClock.elapsedRealtime() + " " + stage);
  }

  public static boolean requested(Context context) {
    return Store.get(context).settings().optBoolean("fallbackEnabled");
  }

  public static boolean ready(Context context) {
    return AppUsageLimits.hasAccess(context) && Settings.canDrawOverlays(context);
  }

  static boolean hasRule(Context context) {
    Store store = Store.get(context);
    JSONArray limits = UsageWeekPlans.effectiveLimits(store, System.currentTimeMillis());
    for (int i = 0; limits != null && i < limits.length(); i++) {
      JSONObject rule = limits.optJSONObject(i);
      if (rule != null && rule.optBoolean("enabled", true) &&
          (rule.optInt("dailyMinutes") > 0 || rule.optInt("weeklyMinutes") > 0)) return true;
    }
    // The backup guard must also run for focus and task penalties, without requiring a quota rule.
    long now = System.currentTimeMillis();
    JSONObject focus = store.one("state", "focus");
    if (focus != null && focus.optJSONArray("apps") != null && focus.optJSONArray("apps").length() > 0) return true;
    for (JSONObject p : store.all("penalties"))
      if (!p.optBoolean("trial") && p.optLong("end") > now && p.optJSONArray("apps") != null
          && p.optJSONArray("apps").length() > 0) return true;
    if (!store.settings().optBoolean("trial", true))
      for (JSONObject task : store.all("instances"))
        if (task.optString("status").equals("open") && task.optJSONArray("apps") != null
            && task.optJSONArray("apps").length() > 0 && (task.optInt("penalty", 120) > 0
                || task.optString("penaltyMode").equals("remainingDwell"))) return true;
    return false;
  }

  public static void sync(Context context) {
    AppUsageLimits.invalidate();
    Intent service = new Intent(context, FallbackBlockService.class);
    if (requested(context) && ready(context) && hasRule(context)) {
      AndroidCompat.startService(context, service);
    } else context.stopService(service);
  }

  @Override public void onCreate() {
    super.onCreate();
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    Scheduler.channels(this);
    if (!AndroidCompat.promote(this, NOTIFICATION_ID, Scheduler.appLimitsStatus(this),
        Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0))
      return START_NOT_STICKY;
    if (!requested(this) || !ready(this) || !hasRule(this)) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (db == null) {
      db = Store.get(this);
      engine = new Engine(this);
      windows = getSystemService(WindowManager.class);
    }
    handler.removeCallbacks(pulse);
    generation++;
    preciseHealthy = false;
    handler.post(pulse);
    return START_NOT_STICKY;
  }

  private final Runnable pulse = new Runnable() {
    @Override public void run() {
      if (!requested(FallbackBlockService.this) || !ready(FallbackBlockService.this) ||
          !hasRule(FallbackBlockService.this)) {
        stopSelf();
        return;
      }
      heartbeat();
      PowerManager power = getSystemService(PowerManager.class);
      KeyguardManager keyguard = getSystemService(KeyguardManager.class);
      if (power == null || !power.isInteractive() ||
          (keyguard != null && keyguard.isKeyguardLocked())) {
        observed = "";
        lastScan = 0;
        preciseHealthy = false;
        hide();
        handler.postDelayed(this, 2_000);
        return;
      }
        if (reading.compareAndSet(false, true)) worker.execute(() -> {
          int scanGeneration = generation;
          try {
            trace("scan");
            if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
              debugScans.incrementAndGet();
          long now = System.currentTimeMillis();
          JSONObject precise = db.one("state", "blockHeartbeat");
          boolean preciseConnected = precise != null && precise.optLong("time") > 0 &&
              now - precise.optLong("time") < 12_000 && precise.optString("error").isEmpty();
          if (preciseConnected) {
            AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
            preciseConnected = accessibility != null && accessibility
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .stream().anyMatch(service -> service.getResolveInfo().serviceInfo.packageName
                    .equals(getPackageName()));
          }
          if (preciseConnected) {
            preciseHealthy = true;
            handler.post(() -> hide());
          } else {
            preciseHealthy = false;
            if (now - lastEngineTick >= 1_000) { engine.tick(); lastEngineTick = now; }
            String pkg = AppUsageLimits.foregroundPackage(FallbackBlockService.this,
                lastScan == 0 ? now - 12 * 60 * 60_000L : lastScan - 15_000, now, observed);
            trace("foreground " + pkg);
            boolean switched = !pkg.equals(observed);
            lastScan = now;
            observed = pkg;
            JSONObject block = pkg.isEmpty() || BlockService.protectedApp(FallbackBlockService.this, pkg)
                ? null : engine.blockedForFallback(pkg, now, switched);
            trace("decision " + (block != null));
            handler.post(() -> {
              if (scanGeneration == generation) display(pkg, block);
            });
          }
          error = "";
        } catch (RuntimeException ex) {
          preciseHealthy = false;
          error = ex.getClass().getSimpleName();
          android.util.Log.e("DisciplineFallback", "Guard check failed", ex);
          handler.post(() -> hide());
        } finally { reading.set(false); }
      });
      handler.postDelayed(this, preciseHealthy ? 3_000 : 750);
    }
  };

  private void heartbeat() {
    long now = System.currentTimeMillis();
    if (now - lastHeartbeat < 5_000) return;
    try {
      db.put("state", Store.obj("id", "fallbackHeartbeat", "time", now, "error", error));
      lastHeartbeat = now;
    } catch (RuntimeException ex) {
      android.util.Log.e("DisciplineFallback", "Could not update status", ex);
    }
  }

  private void display(String pkg, JSONObject block) {
    PowerManager power = getSystemService(PowerManager.class);
    KeyguardManager keyguard = getSystemService(KeyguardManager.class);
    if (power == null || !power.isInteractive() ||
        (keyguard != null && keyguard.isKeyguardLocked())) { hide(); return; }
    if (block == null) {
      hide();
      return;
    }
    if (overlay == null || !pkg.equals(shown)) {
      try { show(pkg, block); }
      catch (RuntimeException ex) {
        error = ex.getClass().getSimpleName();
        android.util.Log.e("DisciplineFallback", "Could not show overlay", ex);
        hide();
        return;
      }
    }
    if (overlayHost != null) overlayHost.update(block.optLong("end") - System.currentTimeMillis(),
        waitStart == 0 ? 0 : Math.max(0, 60_000 - (SystemClock.elapsedRealtime() - waitStart)),
        waitStart != 0);
  }

  private void show(String pkg, JSONObject block) {
    hide();
    shown = pkg;
    String label = pkg;
    try { label = getPackageManager().getApplicationLabel(
        getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
    catch (Exception ignored) { }
    overlayHost = new ComposeBlockOverlayHost(this, label, block.optString("name"), () -> {
      // Cancel the in-flight decision, not enforcement for the next ten seconds.
      generation++;
      observed = "";
      lastScan = System.currentTimeMillis() - 15_000;
      hide();
      Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      try { startActivity(home); }
      catch (RuntimeException ex) {
        android.util.Log.e("DisciplineFallback", "Could not return home", ex);
      }
    }, reason -> {
      if (waitStart == 0) { waitStart = SystemClock.elapsedRealtime(); return; }
      if (SystemClock.elapsedRealtime() - waitStart < 60_000) return;
      db.put("state", Store.obj("id", "pass:" + pkg,
          "until", System.currentTimeMillis() + 10 * Rules.MINUTE));
      db.log("unlock", pkg + "：" + reason);
      hide();
    });
    overlay = overlayHost.getView();
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    windows.addView(overlay, params);
    trace("overlay-added");
    if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
      View drawnView = overlay;
      drawnView.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
        @Override public boolean onPreDraw() {
          drawnView.getViewTreeObserver().removeOnPreDrawListener(this);
          trace("overlay-first-draw");
          return true;
        }
      });
    }
  }

  private void hide() {
    if (overlay != null) {
      try { windows.removeView(overlay); }
      catch (IllegalArgumentException ignored) { }
      overlay = null;
    }
    if (overlayHost != null) { overlayHost.dispose(); overlayHost = null; }
    shown = "";
    waitStart = 0;
  }

  @Override public void onDestroy() {
    generation++;
    handler.removeCallbacks(pulse);
    hide();
    worker.shutdownNow();
    if (db != null) try {
      db.put("state", Store.obj("id", "fallbackHeartbeat", "time", 0, "error", ""));
    } catch (RuntimeException ex) {
      android.util.Log.e("DisciplineFallback", "Could not record service stop", ex);
    }
    super.onDestroy();
  }

  @Override public IBinder onBind(Intent intent) { return null; }
}
