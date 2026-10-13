package com.hl.discipline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Reviewed, dated quotas reuse the existing limit algorithm and remain separate from permanent rules. */
internal object UsageWeekPlans {
    @JvmStatic fun plans(db:Store):List<JSONObject> = db.one("state","usageWeekPlans")?.optJSONArray("plans")?.let { array ->
        (0 until array.length()).mapNotNull(array::optJSONObject)
    }.orEmpty()
    fun parseRecommendation(text:String,apps:List<UsageApp>,report:UsageReport,now:Long):JSONObject {
        val source=JSONObject(text)
        val summary=source.optString("summary").trim()
        require(summary.isNotEmpty() && summary.length<=1500) { "分析缺少有效结论，请重新生成" }
        val proposed=source.getJSONArray("recommendations")
        require(proposed.length() in 1..apps.size) { "推荐应用数量无效" }
        val seen=mutableSetOf<String>()
        val rows=(0 until proposed.length()).map { index ->
            val row=proposed.getJSONObject(index)
            val alias=row.getString("appId")
            val selected=apps.indices.firstOrNull { "app${it+1}"==alias }
                ?: throw IllegalArgumentException("分析推荐了未选择的应用，未应用任何限制")
            val app=apps[selected]
            require(seen.add(app.pkg)) { "分析重复推荐同一应用" }
            val daily=number(row,"dailyMinutes",1440); val weekly=number(row,"weeklyMinutes",10080)
            require(daily>0 || weekly>0) { "每日和每周限额不能同时为 0" }
            val reason=row.optString("reason").trim()
            require(reason.isNotEmpty() && reason.length<=500) { "分析缺少有效推荐理由" }
            Store.obj("package",app.pkg,"label",app.label,"dailyMinutes",daily,"weeklyMinutes",weekly,"reason",reason,"enabled",true)
        }
        val monday=Rules.monday(Rules.date(now)).plusWeeks(1)
        return Store.obj("id","usageAnalysisDraft","created",now,"model",DeepSeekUsageClient.MODEL,
            "summary",summary,"start",Rules.at(monday,0),"end",Rules.at(monday.plusWeeks(1),0),
            "sourceStart",report.start,"sourceEnd",report.end,"apps",JSONArray(rows))
    }
    private fun number(row:JSONObject,key:String,max:Int):Int {
        val value=row.opt(key)
        require(value is Number) { "限额须为整数" }
        val decimal=value.toDouble()
        require(decimal.isFinite() && decimal==kotlin.math.floor(decimal) && decimal in 0.0..max.toDouble()) { "限额数值超出范围" }
        return decimal.toInt()
    }
    @JvmStatic fun validatePlan(plan:JSONObject) {
        val start=plan.optLong("start"); val end=plan.optLong("end")
        require(start>0 && Rules.date(start).dayOfWeek.value==1 && start==Rules.at(Rules.date(start),0)
            && end==Rules.at(Rules.date(start).plusWeeks(1),0)) { "周计划日期无效" }
        val apps=plan.getJSONArray("apps"); require(apps.length() in 1..12) { "周计划应用数量无效" }
        val names=mutableSetOf<String>()
        for(index in 0 until apps.length()) {
            val app=apps.getJSONObject(index); val pkg=app.optString("package")
            require(pkg.length in 3..255 && pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) && names.add(pkg)) { "周计划应用无效" }
            require(number(app,"dailyMinutes",1440)>0 || number(app,"weeklyMinutes",10080)>0) { "至少设置一个限额" }
            number(app,"weeklyMinutes",10080)
        }
    }
    fun save(context:Context,draft:JSONObject,apps:JSONArray,now:Long) {
        val plan=Store.copy(draft); Store.set(plan,"apps",apps); Store.set(plan,"enabled",true)
        validatePlan(plan)
        require(plan.optLong("start")>now) { "建议日期已过，请重新生成下周计划" }
        for(index in 0 until apps.length()) require(!BlockService.protectedApp(context,apps.getJSONObject(index).getString("package"))) {
            "系统与必要应用不支持限制"
        }
        val db=Store.get(context)
        synchronized(db) {
            val retained=plans(db).filter { it.optLong("start")!=plan.optLong("start") }+plan
            db.put("state",Store.obj("id","usageWeekPlans","plans",JSONArray(retained.sortedBy { it.optLong("start") }.takeLast(10))))
        }
        AppUsageLimits.invalidate(); Scheduler.schedule(context); FallbackBlockService.sync(context)
    }
    fun disable(context:Context,start:Long) {
        val db=Store.get(context)
        synchronized(db) {
            val rows=plans(db); rows.filter { it.optLong("start")==start }.forEach { Store.set(it,"enabled",false) }
            db.put("state",Store.obj("id","usageWeekPlans","plans",JSONArray(rows)))
        }
        AppUsageLimits.invalidate(); Scheduler.schedule(context); FallbackBlockService.sync(context)
    }
    @JvmStatic fun effectiveLimits(db:Store,now:Long):JSONArray {
        val byPackage=linkedMapOf<String,JSONObject>()
        fun add(array:JSONArray?) { for(index in 0 until (array?.length()?:0)) {
            val row=array!!.optJSONObject(index)?:continue
            if(!row.optBoolean("enabled",true)) continue
            val pkg=row.optString("package"); val prior=byPackage[pkg]
            if(prior==null) byPackage[pkg]=Store.copy(row) else {
                Store.set(prior,"dailyMinutes",UsageTimeline.lowerPositive(prior.optInt("dailyMinutes"),row.optInt("dailyMinutes")))
                Store.set(prior,"weeklyMinutes",UsageTimeline.lowerPositive(prior.optInt("weeklyMinutes"),row.optInt("weeklyMinutes")))
            }
        } }
        add(db.settings().optJSONArray("appLimits"))
        plans(db).filter { it.optBoolean("enabled",true) && it.optLong("start")<=now && it.optLong("end")>now }
            .forEach { add(it.optJSONArray("apps")) }
        return JSONArray(byPackage.values.toList())
    }
    @JvmStatic fun next(db:Store,now:Long):Long = plans(db).filter { it.optBoolean("enabled",true) }
        .flatMap { listOf(it.optLong("start"),it.optLong("end")) }.filter { it>now }.minOrNull()?:Long.MAX_VALUE
}
