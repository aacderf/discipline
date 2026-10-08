package com.hl.discipline;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import java.util.*;
import org.json.JSONObject;

public final class BlockService extends AccessibilityService {
  static volatile boolean connected;
  static volatile String serviceError = "";
  private WindowManager wm;
  private View overlay;
  private ComposeBlockOverlayHost composeOverlay;
  private Engine engine;
  private Store db;
  private String foreground = "", shown = "";
  private long waitStart = 0;
  private long lastHeartbeat = 0;
  private final Handler h = new Handler();
  private final Runnable pulse =
      new Runnable() {
        public void run() {
          if (engine == null) return;
          try {
            engine.tick();
            refresh();
            serviceError = "";
          } catch (RuntimeException ex) {
            serviceError = ex.getClass().getSimpleName();
            android.util.Log.e("DisciplineBlock", "Restriction service stopped processing", ex);
            hide();
          }
          heartbeat();
          h.postDelayed(this, serviceError.isEmpty() ? 1000 : 5000);
        }
      };

  public static boolean protectedApp(Context c, String pkg) {
    if (pkg == null
        || pkg.isEmpty()
        || pkg.equals(c.getPackageName())
        || pkg.equals("android")
        || pkg.contains("systemui")
        || pkg.contains("permissioncontroller")
        || pkg.contains("packageinstaller")
        || pkg.contains("launcher")
        || pkg.contains("settings")
        || pkg.contains("emergency")
        || pkg.contains("incallui")
        || pkg.contains("telecom")) return true;
    android.telecom.TelecomManager tm = c.getSystemService(android.telecom.TelecomManager.class);
    if (tm != null && pkg.equals(tm.getDefaultDialerPackage())) return true;
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(home, 0);
    return r != null && r.activityInfo != null && pkg.equals(r.activityInfo.packageName);
  }

  @Override
  protected void onServiceConnected() {
    try {
      wm = getSystemService(WindowManager.class);
      db = Store.get(this);
      engine = new Engine(this);
      db.log("permission", "应用限制服务已连接");
      connected = true;
      serviceError = "";
      heartbeat();
      h.post(pulse);
    } catch (RuntimeException ex) {
      connected = false;
      serviceError = ex.getClass().getSimpleName();
      android.util.Log.e("DisciplineBlock", "Service startup failed", ex);
    }
  }

  private void heartbeat() {
    long now = System.currentTimeMillis();
    if (db == null || now - lastHeartbeat < 5_000) return;
    try {
      db.put("state", Store.obj("id", "blockHeartbeat", "time", now,
          "error", serviceError));
      lastHeartbeat = now;
    } catch (RuntimeException ex) {
      android.util.Log.e("DisciplineBlock", "Could not update service state", ex);
    }
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (engine == null || event.getPackageName() == null) return;
    String p = event.getPackageName().toString();
    // Windows-changed also describes overlays/system chrome, not necessarily the foreground app.
    if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED && !p.equals(foreground)) return;
    if (p.equals(getPackageName())) {
      String screen = event.getClassName() == null ? "" : event.getClassName().toString();
      if (screen.equals(ComposeMainActivity.class.getName())
          || screen.equals(ComposePlaceActivity.class.getName())
          || screen.equals(MainActivity.class.getName())) {
        foreground = p;
        hide();
      }
      return;
    }
    String ime =
        android.provider.Settings.Secure.getString(
            getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
    if (ime != null && ime.startsWith(p + "/")) return;
    boolean switched = !foreground.equals(p);
    foreground = p;
    try {
      refresh(switched);
    } catch (RuntimeException ex) {
      serviceError = ex.getClass().getSimpleName();
      android.util.Log.e("DisciplineBlock", "Restriction display failed", ex);
      hide();
    }
  }

  private void refresh() {
    refresh(false);
  }

  private void refresh(boolean switched) {
    JSONObject b =
        protectedApp(this, foreground)
            ? null
            : switched ? engine.blockedOnAppSwitch(foreground, System.currentTimeMillis())
                : engine.blocked(foreground, System.currentTimeMillis());
    if (b == null) {
      hide();
      return;
    }
    if (!shown.equals(foreground) || overlay == null) show(foreground, b);
    if (composeOverlay != null)
      composeOverlay.update(b.optLong("end") - System.currentTimeMillis(),
          waitStart == 0 ? 0 : Math.max(0, 60_000 - (SystemClock.elapsedRealtime() - waitStart)),
          waitStart > 0);
  }

  private void show(String pkg, JSONObject block) {
    hide();
    shown = pkg;
    String label = pkg;
    try {
      label =
          getPackageManager()
              .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
              .toString();
    } catch (Exception ignored) {
    }
    composeOverlay = new ComposeBlockOverlayHost(this, label, block.optString("name"),
        () -> {
              performGlobalAction(GLOBAL_ACTION_HOME);
              foreground = "";
              hide();
        }, reason -> {
              if (waitStart == 0) {
                waitStart = SystemClock.elapsedRealtime();
                return;
              }
              if (SystemClock.elapsedRealtime() - waitStart < 60_000) return;
              db.put(
                  "state",
                  Store.obj(
                      "id",
                      "pass:" + pkg,
                      "until",
                      System.currentTimeMillis() + 10 * Rules.MINUTE));
              db.log("unlock", pkg + "：" + reason);
              hide();
        });
    overlay = composeOverlay.getView();
    WindowManager.LayoutParams lp =
        new WindowManager.LayoutParams(
            -1,
            -1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    wm.addView(overlay, lp);
  }

  private void hide() {
    if (overlay != null) {
      wm.removeView(overlay);
      overlay = null;
    }
    if (composeOverlay != null) { composeOverlay.dispose(); composeOverlay = null; }
    shown = "";
    waitStart = 0;
  }

  @Override
  public void onInterrupt() {
    hide();
  }

  @Override
  public void onDestroy() {
    connected = false;
    h.removeCallbacks(pulse);
    hide();
    if (db != null) {
      try {
        db.put("state", Store.obj("id", "blockHeartbeat", "time", 0,
            "error", ""));
        db.log("permission", "应用限制服务已停止");
      } catch (RuntimeException ex) {
        android.util.Log.e("DisciplineBlock", "Could not record service stop", ex);
      }
    }
    super.onDestroy();
  }
}
