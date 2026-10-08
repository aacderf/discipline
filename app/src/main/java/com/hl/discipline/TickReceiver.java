package com.hl.discipline;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;

public final class TickReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent intent) {
    Engine e = new Engine(c);
    Store db = Store.get(c);
    String action = intent.getAction();
    if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
      JSONObject f = db.one("state", "focus");
      if (f != null) {
        if (!f.optString("phase").equals("paused"))
          Store.set(f, "pausedPhase", f.optString("phase"));
        Store.set(f, "phase", "paused");
        db.put("state", f);
      }
      db.log("gap", "系统重启或时间变化，未确认的计时已暂停");
    }
    e.tick();
    long now = System.currentTimeMillis();
    Reminders.deliver(c, db, now);
    Scheduler.schedule(c);
    if (Intent.ACTION_BOOT_COMPLETED.equals(action) && db.settings().optBoolean("monitor")) {
      boolean fine =
          c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
              == PackageManager.PERMISSION_GRANTED;
      boolean bg =
          Build.VERSION.SDK_INT < 29
              || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                  == PackageManager.PERMISSION_GRANTED;
      if (fine && bg) {
        if (!AndroidCompat.startService(c, new Intent(c, LocationService.class))) {
          Scheduler.notify(c, 302, "请恢复地点监测", "打开自律后继续监测");
        }
      } else Scheduler.notify(c, 302, "请恢复地点监测", "定位授权不足，请打开自律检查权限");
    }
  }
}
