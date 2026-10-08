package com.hl.discipline;

import static com.hl.discipline.Store.*;
import java.util.*;
import org.json.JSONObject;

/** Removes unused places; historical references retain a hidden record for statistics and backups. */
final class PlaceDeletion {
  static List<String> blockers(Store db, String id) {
    Set<String> names = new LinkedHashSet<>();
    for (JSONObject plan : db.all("plans"))
      if (id.equals(plan.optString("place")) && !plan.optBoolean("archived"))
        names.add(plan.optString("name", "关联计划"));
    for (JSONObject task : db.all("instances"))
      if (id.equals(task.optString("place")) &&
          Arrays.asList("open", "review").contains(task.optString("status")))
        names.add(task.optString("name", "待处理任务"));
    JSONObject focus = db.one("state", "focus");
    if (focus != null && id.equals(focus.optString("place"))) names.add("当前专注");
    return new ArrayList<>(names);
  }

  static void delete(Store db, String id) {
    synchronized (db) {
      if (db.one("places", id) == null) throw new IllegalStateException("地点不存在");
      if (!blockers(db, id).isEmpty()) throw new IllegalStateException("请先更换或移除关联计划，再删除地点");
      db.atomic(() -> {
        boolean referenced = false;
        for (String table : new String[] {"plans", "instances", "sessions"})
          for (JSONObject row : db.all(table))
            if (id.equals(row.optString("place"))) referenced = true;
        if (referenced) {
          JSONObject place = db.one("places", id); set(place, "archived", true); db.put("places", place);
        } else db.remove("places", id);
        invalidate(db, id, "地点已移除，等待重新定位");
      });
    }
  }

  static void invalidate(Store db, String id, String reason) {
    JSONObject location = db.one("state", "location");
    if (location != null && id.equals(location.optString("place"))) {
      set(location, "place", ""); set(location, "reliable", false);
      set(location, "validUntil", 0); set(location, "status", reason); db.put("state", location);
    }
  }

  static String geometryKey(JSONObject place) {
    return place.optString("coordinateSystem", "WGS84") + ":" + place.optString("shape", "rectangle")
        + ":" + place.optString("north") + ":" + place.optString("south")
        + ":" + place.optString("east") + ":" + place.optString("west")
        + ":" + place.optString("points") + ":" + place.optBoolean("archived");
  }
}
