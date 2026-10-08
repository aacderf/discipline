package com.hl.discipline;

import android.app.NotificationManager;
import android.content.Context;
import java.util.*;
import org.json.JSONObject;

/** Deletes a plan and its attributable history without touching shared place coverage. */
public final class PlanDeletion {
  private PlanDeletion() {}

  public static void delete(Context context, String planId) {
    Store db = Store.get(context);
    synchronized (db) {
      JSONObject plan = db.one("plans", planId);
      if (plan == null) throw new IllegalArgumentException("计划不存在");
      List<JSONObject> instances = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (JSONObject task : db.all("instances")) {
        if (planId.equals(task.optString("planId"))) {
          instances.add(task);
          ids.add(task.optString("id"));
        }
      }
      JSONObject focus = db.one("state", "focus");
      if (focus != null && ids.contains(focus.optString("task")))
        throw new IllegalStateException("请先结束关联的专注，再删除计划");
      db.atomic(() -> {
        // Children remain valid independent plans and keep their own history.
        for (JSONObject child : db.all("plans")) {
          if (planId.equals(child.optString("parent"))) {
            Store.set(child, "parent", "");
            db.put("plans", child);
          }
        }
        for (JSONObject task : db.all("instances")) {
          if (planId.equals(task.optString("parent")) && !ids.contains(task.optString("id"))) {
            Store.set(task, "parent", "");
            db.put("instances", task);
          }
        }
        Set<String> sessions = new HashSet<>();
        for (JSONObject session : db.all("sessions")) {
          if (ids.contains(session.optString("task"))) {
            sessions.add(session.optString("id"));
            db.remove("sessions", session.optString("id"));
          }
        }
        for (JSONObject state : db.all("state")) {
          if (sessions.contains(state.optString("session")))
            db.remove("state", state.optString("id"));
        }
        for (JSONObject penalty : db.all("penalties")) {
          if (ids.contains(penalty.optString("task")))
            db.remove("penalties", penalty.optString("id"));
        }
        JSONObject selection = db.one("state", "focusSelection");
        if (selection != null && ids.contains(selection.optString("task")))
          db.remove("state", "focusSelection");
        for (JSONObject task : instances) db.remove("instances", task.optString("id"));
        db.remove("plans", planId);
      });
      NotificationManager notifications = context.getSystemService(NotificationManager.class);
      for (JSONObject task : instances) {
        notifications.cancel(Math.abs(task.optString("id").hashCode()));
        notifications.cancel(Math.abs((task.optString("id") + ":start").hashCode()));
        notifications.cancel(Math.abs((task.optString("id") + ":due").hashCode()));
      }
      Scheduler.schedule(context);
    }
  }
}
