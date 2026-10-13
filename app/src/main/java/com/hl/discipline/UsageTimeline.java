package com.hl.discipline;

import java.util.*;

/** Interval clipping shared by the local report and quota reads. No Android dependencies. */
public final class UsageTimeline {
  public static final int RESUME=1, PAUSE=2, STOP=3, SCREEN_OFF=4, STARTUP=5;
  public static final class Point {
    public final long at; public final String pkg, activity; public final int kind;
    public Point(long at,String pkg,String activity,int kind) {
      this.at=at; this.pkg=pkg==null?"":pkg; this.activity=activity==null?this.pkg:activity; this.kind=kind;
    }
  }
  public static final class Totals {
    public final long[] millis; public final int[] entries;
    Totals(int days) { millis=new long[days]; entries=new int[days]; }
  }
  private static final class Active {
    long start; final Set<String> activities=new HashSet<>();
  }
  public static Map<String,Totals> calculate(List<Point> points,long[] boundaries,long until) {
    if(boundaries.length<2) throw new IllegalArgumentException("时间范围无效");
    for(int i=1;i<boundaries.length;i++) if(boundaries[i]<=boundaries[i-1]) throw new IllegalArgumentException("日期必须递增");
    Map<String,Totals> result=new HashMap<>(); Map<String,Active> active=new HashMap<>();
    for(Point point:points) {
      if(point.at>until) continue;
      // No closing timestamp survives an abrupt restart; do not invent elapsed use.
      if(point.kind==STARTUP) { active.clear(); continue; }
      if(point.kind==SCREEN_OFF) {
        for(Map.Entry<String,Active> entry:active.entrySet()) add(result,entry.getKey(),entry.getValue().start,point.at,boundaries,until);
        active.clear(); continue;
      }
      if(point.pkg.isEmpty()) continue;
      if(point.kind==RESUME) {
        Active state=active.computeIfAbsent(point.pkg,k->new Active());
        if(state.activities.isEmpty()) {
          state.start=point.at;
          for(int d=0;d<boundaries.length-1;d++) if(point.at>=boundaries[d] && point.at<boundaries[d+1])
            result.computeIfAbsent(point.pkg,k->new Totals(boundaries.length-1)).entries[d]++;
        }
        state.activities.add(point.activity);
      } else if(point.kind==PAUSE || point.kind==STOP) {
        Active state=active.get(point.pkg);
        if(state!=null) {
          state.activities.remove(point.activity);
          if(state.activities.isEmpty()) { add(result,point.pkg,state.start,point.at,boundaries,until); active.remove(point.pkg); }
        }
      }
    }
    for(Map.Entry<String,Active> entry:active.entrySet()) add(result,entry.getKey(),entry.getValue().start,until,boundaries,until);
    return result;
  }
  private static void add(Map<String,Totals> totals,String pkg,long start,long end,long[] days,long until) {
    long stop=Math.min(end,until);
    if(stop<=Math.max(start,days[0])) return;
    Totals total=totals.computeIfAbsent(pkg,k->new Totals(days.length-1));
    for(int d=0;d<total.millis.length;d++) total.millis[d]+=Rules.overlap(start,stop,days[d],days[d+1]);
  }
  public static int lowerPositive(int a,int b) { return a<=0?b:b<=0?a:Math.min(a,b); }
}
