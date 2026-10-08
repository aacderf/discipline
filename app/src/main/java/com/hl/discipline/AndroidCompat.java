package com.hl.discipline;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.Locale;
import org.json.JSONObject;

/** Optional system integrations must not prevent opening the local planner. */
public final class AndroidCompat {
  private AndroidCompat() {}

  public static boolean startService(Context context, Intent intent) {
    try {
      context.startForegroundService(intent);
      return true;
    } catch (RuntimeException error) {
      serviceFailed(context, intent.getComponent().getClassName(), error);
      return false;
    }
  }

  public static boolean promote(Service service, int id, Notification notification, int type) {
    try {
      // specialUse does not exist before API 34. Location types exist from API 29.
      if (Build.VERSION.SDK_INT >= 29 && type != 0)
        service.startForeground(id, notification, type);
      else service.startForeground(id, notification);
      Store.get(service).remove("state", healthId(service.getClass().getName()));
      return true;
    } catch (RuntimeException error) {
      serviceFailed(service, service.getClass().getName(), error);
      service.stopSelf();
      return false;
    }
  }

  public static void serviceFailed(Context context, String service, RuntimeException error) {
    android.util.Log.e("DisciplineCompat", "System refused " + service, error);
    String message = error instanceof SecurityException
        ? "权限不足或后台运行被系统限制，请检查运行与权限"
        : "系统未允许后台服务启动，请在应用内重新开启";
    try {
      Store db = Store.get(context);
      String id = healthId(service);
      JSONObject previous = db.one("state", id);
      if (previous == null || !previous.optString("error").equals(message))
        db.log("gap", service.substring(service.lastIndexOf('.') + 1) + "：" + message);
      db.put("state", Store.obj("id", id, "time", System.currentTimeMillis(), "error", message));
    } catch (RuntimeException storageError) {
      android.util.Log.e("DisciplineCompat", "Could not record service status", storageError);
    }
  }

  private static String healthId(String service) {
    return "serviceHealth:" + service.substring(service.lastIndexOf('.') + 1);
  }

  public static String serviceProblem(Context context, Class<?> service) {
    JSONObject health = Store.get(context).one("state", healthId(service.getName()));
    return health == null ? "" : health.optString("error");
  }

  public static boolean exactAlarmsAllowed(Context context) {
    AlarmManager manager = context.getSystemService(AlarmManager.class);
    if (manager == null) return false;
    try { return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms(); }
    catch (SecurityException error) { return false; }
  }

  public static Context overlayContext(Context context) {
    if (Build.VERSION.SDK_INT < 30 || !Settings.canDrawOverlays(context)) return context;
    try {
      DisplayManager manager = context.getSystemService(DisplayManager.class);
      Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
      if (display != null) return context.createDisplayContext(display)
          .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
    } catch (RuntimeException error) {
      android.util.Log.w("DisciplineCompat", "Using service window context", error);
    }
    return context;
  }

  /** Some OEMs remove or protect individual Settings activities. Fall back to app details. */
  public static boolean openSettings(Context context, Intent requested) {
    Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.getPackageName()));
    for (Intent intent : new Intent[] {requested, details, new Intent(Settings.ACTION_SETTINGS)}) {
      try {
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
      } catch (android.content.ActivityNotFoundException | SecurityException error) {
        android.util.Log.w("DisciplineCompat", "Settings entry unavailable: " + intent.getAction());
      }
    }
    Toast.makeText(context, "请在系统设置中打开自律的应用详情并授权", Toast.LENGTH_LONG).show();
    return false;
  }

  public static String backgroundHint() {
    return backgroundHint(Build.MANUFACTURER + " " + Build.BRAND);
  }

  static String backgroundHint(String manufacturer) {
    String name = manufacturer.toLowerCase(Locale.ROOT);
    if (name.contains("xiaomi") || name.contains("redmi") || name.contains("poco"))
      return "小米 / Redmi / POCO：在应用详情允许自启动，将省电策略设为不限制；使用悬浮球或备用拦截时，还需检查后台弹出界面权限。";
    if (name.contains("vivo") || name.contains("iqoo"))
      return "vivo / iQOO：在应用电池管理允许后台运行，并检查自启动与后台弹出界面权限。";
    if (name.contains("oppo") || name.contains("oneplus") || name.contains("realme"))
      return "OPPO / 一加 / realme：在应用耗电管理允许后台活动，并检查自启动与悬浮窗权限。";
    if (name.contains("huawei") || name.contains("honor"))
      return "华为 / 荣耀的安卓系统：在应用启动管理改为手动管理，允许自启动、关联启动和后台活动。";
    if (name.contains("samsung"))
      return "三星：将自律移出休眠应用名单，按需加入永不休眠应用，并允许后台电池使用。";
    return "在系统应用详情允许后台运行；如有自启动、休眠应用或省电名单，请检查自律未被限制。";
  }
}
