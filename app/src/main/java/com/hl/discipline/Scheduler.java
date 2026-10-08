package com.hl.discipline;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.JSONObject;

public final class Scheduler {
  private static long lastScheduled = -1;
  private static boolean lastExact;

  public static void channels(Context c) {
    NotificationManager m = c.getSystemService(NotificationManager.class);
    m.createNotificationChannel(
        new NotificationChannel("status", "地点监测", NotificationManager.IMPORTANCE_LOW));
    m.createNotificationChannel(
        new NotificationChannel("tasks", "任务与专注提醒", NotificationManager.IMPORTANCE_DEFAULT));
    m.createNotificationChannel(
        new NotificationChannel("plan_sound", "计划有声提醒", NotificationManager.IMPORTANCE_DEFAULT));
    m.createNotificationChannel(
        new NotificationChannel("plan_silent", "计划静音提醒", NotificationManager.IMPORTANCE_LOW));
    m.createNotificationChannel(
        new NotificationChannel("class_sound", "课程有声提醒", NotificationManager.IMPORTANCE_DEFAULT));
    m.createNotificationChannel(
        new NotificationChannel("class_silent", "课程静音提醒", NotificationManager.IMPORTANCE_LOW));
    m.createNotificationChannel(
        new NotificationChannel("app_limits", "应用时长备用拦截", NotificationManager.IMPORTANCE_LOW));
    m.createNotificationChannel(
        new NotificationChannel("focus_phases", "专注与休息结束提示", NotificationManager.IMPORTANCE_HIGH));
    m.createNotificationChannel(
        new NotificationChannel("focus_popup_sound", "阶段弹窗声音", NotificationManager.IMPORTANCE_DEFAULT));
    m.createNotificationChannel(
        new NotificationChannel("focus_companion", "专注计时与悬浮球", NotificationManager.IMPORTANCE_LOW));
  }

  public static void focusNotice(Context c, String title, String text, boolean hasPopup) {
    channels(c);
    try {
      c.getSystemService(NotificationManager.class).notify(301,
          new Notification.Builder(c, hasPopup ? "focus_popup_sound" : "focus_phases").setSmallIcon(R.drawable.ic_app)
              .setContentTitle(title).setContentText(text)
              .setStyle(new Notification.BigTextStyle().bigText(text))
              .setCategory(Notification.CATEGORY_ALARM).setContentIntent(open(c))
              .setAutoCancel(true).build());
    } catch (SecurityException ignored) { /* In-app dialog remains available. */ }
  }

  public static PendingIntent open(Context c) {
    return PendingIntent.getActivity(
        c,
        0,
        new Intent(c, ComposeMainActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  public static Notification status(Context c, String text) {
    channels(c);
    return new Notification.Builder(c, "status")
        .setSmallIcon(com.hl.discipline.R.drawable.ic_app)
        .setContentTitle("自律 · 地点监测中")
        .setContentText(text)
        .setContentIntent(open(c))
        .setOngoing(true)
        .build();
  }

  public static Notification appLimitsStatus(Context c) {
    channels(c);
    return new Notification.Builder(c, "app_limits")
        .setSmallIcon(R.drawable.ic_app)
        .setContentTitle("自律 · 应用限制备用拦截")
        .setContentText("无障碍服务不可用时尽力处理应用限额、专注与任务限制")
        .setContentIntent(open(c))
        .setOngoing(true)
        .build();
  }

  public static void notify(Context c, int id, String title, String text) {
    channels(c);
    try {
      c.getSystemService(NotificationManager.class)
          .notify(
              id,
              new Notification.Builder(c, "tasks")
                  .setSmallIcon(R.drawable.ic_app)
                  .setContentTitle(title)
                  .setContentText(text)
                  .setContentIntent(open(c))
                  .setAutoCancel(true)
                  .build());
    } catch (SecurityException ignored) {
      /* Health page shows the denied permission. */
    }
  }

  public static boolean remind(Context c, int id, String title, String text, boolean course,
      boolean sound) {
    channels(c);
    String channel = (course ? "class" : "plan") + (sound ? "_sound" : "_silent");
    NotificationManager manager = c.getSystemService(NotificationManager.class);
    if (!manager.areNotificationsEnabled()
        || manager.getNotificationChannel(channel).getImportance() == NotificationManager.IMPORTANCE_NONE)
      return false;
    try {
      manager.notify(id, new Notification.Builder(c, channel)
              .setSmallIcon(R.drawable.ic_app)
              .setContentTitle(title)
              .setContentText(text)
              .setContentIntent(open(c))
              .setCategory(Notification.CATEGORY_REMINDER)
              .setAutoCancel(true)
              .build());
      return true;
    } catch (SecurityException ignored) {
      /* The permissions page reports disabled notifications. */
      return false;
    }
  }

  public static void schedule(Context c) {
    Store db = Store.get(c);
    long now = System.currentTimeMillis(),
        next = (now / (15 * Rules.MINUTE) + 1) * (15 * Rules.MINUTE);
    for (JSONObject i : db.all("instances"))
      if (i.optString("status").equals("open") && i.optLong("due") > now)
        next = Math.min(next, i.optLong("due"));
    next = Math.min(next, Reminders.next(c, db, now));
    JSONObject f = db.one("state", "focus");
    if (f != null
        && (f.optString("phase").equals("work") && !f.optBoolean("forward")
            || f.optString("phase").equals("break")))
      next = Math.min(next, f.optLong("checkpoint") + f.optLong("remaining"));
    for (JSONObject p : db.all("penalties")) {
      if (p.optLong("start") > now) next = Math.min(next, p.optLong("start"));
      if (p.optLong("end") > now) next = Math.min(next, p.optLong("end"));
    }
    AlarmManager a = c.getSystemService(AlarmManager.class);
    PendingIntent pi =
        PendingIntent.getBroadcast(
            c,
            1,
            new Intent(c, TickReceiver.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    next = Math.max(now + 1000, next);
    boolean exact = Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms();
    if (next == lastScheduled && exact == lastExact) return;
    lastScheduled = next;
    lastExact = exact;
    if (exact) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
    else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
  }
}
