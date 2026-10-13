package com.hl.discipline

import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate

internal data class UsageApp(val pkg:String,val label:String,val days:List<Long>,val entries:Int,val canLimit:Boolean) {
    val total:Long get()=days.sum()
}
internal data class UsageReport(val start:Long,val end:Long,val dates:List<LocalDate>,val apps:List<UsageApp>,val eventStart:Long,
    val availableDays:List<Boolean> = dates.map { true }) {
    val total:Long get()=apps.sumOf { it.total }
    val daily:List<Long> get()=dates.indices.map { d->apps.sumOf { it.days[d] } }
}

/** On-demand local report; no periodic worker, upload, or persistent usage-history copy. */
internal object UsageInsightsRepository {
    private var cached:UsageReport?=null
    @Synchronized fun load(context:Context,now:Long,force:Boolean=false):UsageReport {
        check(AppUsageLimits.hasAccess(context)) { "请先授权使用情况访问" }
        cached?.let { if(!force && now>=it.end && now-it.end<60_000 && Rules.date(now)==Rules.date(it.end)) return it }
        val dates=(0..6).map { Rules.date(now).minusDays((6-it).toLong()) }
        val bounds=(dates+dates.last().plusDays(1)).map { Rules.at(it,0) }.toLongArray()
        val manager=context.getSystemService(UsageStatsManager::class.java)
        val stats=manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,bounds[0],now)
            ?: error("系统暂未提供使用汇总，请稍后刷新")
        val nativeEvents=manager.queryEvents(bounds[0]-24*60*Rules.MINUTE,now)
            ?: error("系统暂未提供使用记录，请稍后刷新")
        val points=AppUsageLimits.points(nativeEvents)
        val events=UsageTimeline.calculate(points,bounds,now)
        val daily=mutableMapOf<String,LongArray>()
        val available=BooleanArray(7)
        stats.forEach { row ->
            val date=Rules.date(row.firstTimeStamp); val day=dates.indexOf(date)
            if(day>=0) { available[day]=true; daily.getOrPut(row.packageName){LongArray(7)}[day]+=row.totalTimeInForeground.coerceAtLeast(0) }
        }
        events.forEach { (pkg,totals) ->
            val days=daily.getOrPut(pkg){LongArray(7)}
            days.indices.forEach { day->days[day]=maxOf(days[day],totals.millis[day]); if(totals.millis[day]>0) available[day]=true }
        }
        val packages=context.packageManager
        val apps=daily.mapNotNull { (pkg,values) ->
            if(pkg==context.packageName || values.sum()<=0) return@mapNotNull null
            try {
                if(packages.getLaunchIntentForPackage(pkg)==null) return@mapNotNull null
                val info=packages.getApplicationInfo(pkg,0)
                UsageApp(pkg,packages.getApplicationLabel(info).toString(),values.map { it.coerceAtMost(24*60*Rules.MINUTE) },
                    events[pkg]?.entries?.sum()?:0,!BlockService.protectedApp(context,pkg))
            } catch (_:android.content.pm.PackageManager.NameNotFoundException) { null }
        }.sortedByDescending { it.total }
        return UsageReport(bounds[0],now,dates,apps,points.firstOrNull { it.at>=bounds[0] }?.at?:0,available.toList())
            .also { cached=it }
    }
}
