package com.hl.discipline

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class UsagePlanningDeviceTest {
    private val context:Context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val db:Store get()=Store.get(context)
    @Before fun setup() {
        check(context.packageName.endsWith(".qa") && (Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone")))
        listOf(LocationService::class.java,FocusCompanionService::class.java,FallbackBlockService::class.java).forEach {context.stopService(Intent(context,it))}
        db.atomic { Store.TABLES.forEach {db.writableDatabase.delete(it,null,null)} }
        AppUsageLimits.invalidate()
    }
    private fun app()=UsageApp("com.example.video","视频",List(7){90*Rules.MINUTE},4,true)
    private fun report(now:Long):UsageReport {
        val dates=(0..6).map {Rules.date(now).minusDays((6-it).toLong())}
        return UsageReport(Rules.at(dates.first(),0),now,dates,listOf(app()),now)
    }
    private fun content(id:String="app1",daily:Any=60)=Store.obj("summary","可减少晚间娱乐使用", "recommendations",
        JSONArray(listOf(Store.obj("appId",id,"dailyMinutes",daily,"weeklyMinutes",360,"reason","保持温和且可执行的预算")))).toString()
    private fun clientReply(content:String,finish:String="stop")=Store.obj("choices",JSONArray(listOf(
        Store.obj("finish_reason",finish,"message",Store.obj("content",content))))).toString()
    @Test fun apiRequestSendsOnlyReviewedAggregateAndDoesNotApplyRules() {
        val now=System.currentTimeMillis(); var request=JSONObject()
        val client=DeepSeekUsageClient(DeepSeekTransport {path,_,body ->
            assertEquals("/chat/completions",path); request=JSONObject(body!!); clientReply(content())
        })
        val draft=client.analyze("test-key",report(now),listOf(app()),"保留学习时间",now)
        assertEquals("deepseek-flash",request.getString("model"))
        assertEquals("json_object",request.getJSONObject("response_format").getString("type"))
        assertFalse(request.toString().contains(app().pkg)); assertFalse(request.toString().contains("test-key"))
        assertEquals(60,draft.getJSONArray("apps").getJSONObject(0).getInt("dailyMinutes"))
        assertNull(db.one("state","usageWeekPlans"))
    }
    @Test fun fabricatedAppsDecimalsAndTruncatedResultsAreRejected() {
        val now=System.currentTimeMillis()
        for(bad in listOf(content("app99"),content(daily=1.5),content(daily=-1),content(daily=1441)))
            assertThrows(IllegalArgumentException::class.java) {UsageWeekPlans.parseRecommendation(bad,listOf(app()),report(now),now)}
        val client=DeepSeekUsageClient(DeepSeekTransport {_,_,_->clientReply(content(),"length")})
        assertThrows(IllegalArgumentException::class.java) {client.analyze("test-key",report(now),listOf(app()),"目标",now)}
        assertNull(db.one("state","usageWeekPlans"))
    }
    @Test fun datedPlanStartsNextMondayAndPermanentLimitsReturnAfterExpiry() {
        val now=System.currentTimeMillis(); val draft=UsageWeekPlans.parseRecommendation(content(),listOf(app()),report(now),now)
        val settings=db.settings(); Store.set(settings,"appLimits",JSONArray(listOf(Store.obj("package",app().pkg,
            "enabled",true,"dailyMinutes",120,"weeklyMinutes",500)))); db.put("state",settings)
        UsageWeekPlans.save(context,draft,draft.getJSONArray("apps"),now)
        assertEquals(120,UsageWeekPlans.effectiveLimits(db,now).getJSONObject(0).getInt("dailyMinutes"))
        val start=draft.getLong("start"); val end=draft.getLong("end")
        assertEquals(60,UsageWeekPlans.effectiveLimits(db,start).getJSONObject(0).getInt("dailyMinutes"))
        assertEquals(360,UsageWeekPlans.effectiveLimits(db,start).getJSONObject(0).getInt("weeklyMinutes"))
        assertEquals(120,UsageWeekPlans.effectiveLimits(db,end).getJSONObject(0).getInt("dailyMinutes"))
        assertNotNull(db.prepareImport(db.exportJson()))
        UsageWeekPlans.disable(context,start)
        assertEquals(120,UsageWeekPlans.effectiveLimits(db,start).getJSONObject(0).getInt("dailyMinutes"))
        assertEquals(Long.MAX_VALUE,UsageWeekPlans.next(db,now))
    }
    @Test fun apiKeyIsEncryptedAndExcludedFromBackup() {
        val sample="sk-local-test-only-0123456789"
        try {
            DeepSeekKeyStore.save(context,sample); assertEquals(sample,DeepSeekKeyStore.read(context))
            assertFalse(context.getSharedPreferences("deepseek_private",0).all.toString().contains(sample))
            assertFalse(db.exportJson().contains(sample))
        } finally {DeepSeekKeyStore.clear(context)}
    }
    @Test fun batchQuotaReadsShareQueriesAndSwitchesStillReadFresh() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("appops set ${context.packageName} GET_USAGE_STATS allow")
        try {
            assertTrue(AppUsageLimits.hasAccess(context)); AppUsageLimits.invalidate()
            val before=AppUsageLimits.debugFreshQueries.get(); val now=System.currentTimeMillis()
            assertTrue(AppUsageLimits.read(context,"com.example.first",now).available)
            AppUsageLimits.read(context,"com.example.second",now)
            assertEquals(1,AppUsageLimits.debugFreshQueries.get()-before)
            AppUsageLimits.readForLimit(context,"com.example.second",now+1,60,360,true)
            assertEquals(2,AppUsageLimits.debugFreshQueries.get()-before)
        } finally {device.executeShellCommand("appops set ${context.packageName} GET_USAGE_STATS default")}
    }
    private fun task(id:String,now:Long,longTask:Boolean)=Store.obj("id",id,"planId",id,"name","长任务测试",
        "cycle","once","metric","presence","manual",0,"target",120,"place","library",
        "start",now-(if(longTask) 3*24*60 else 120)*Rules.MINUTE,"due",now-30*Rules.MINUTE,
        "status","open","from",0,"until",1440,"penalty",10,"apps",JSONArray(listOf("com.android.chrome")))
    @Test fun longTaskMissingCoverageExecutesFromActualSettlementOnlyOnce() {
        val now=System.currentTimeMillis(); val settings=db.settings(); Store.set(settings,"trial",false); db.put("state",settings)
        db.put("instances",task("long",now,true)); val engine=Engine(context); engine.tick()
        assertEquals("failed",db.one("instances","long")!!.getString("status"))
        val penalty=db.one("penalties","penalty:long")!!
        assertTrue(penalty.getLong("start")>=now); assertTrue(penalty.getLong("end")>System.currentTimeMillis())
        assertNotNull(engine.blocked("com.android.chrome",now+1000))
        engine.tick(); assertEquals(1,db.all("penalties").size)
        assertEquals(penalty.getLong("start"),db.one("penalties","penalty:long")!!.getLong("start"))
    }
    @Test fun dailyCoverageStillReviewsAndStaleConfirmationCannotPunishDoneTask() {
        val now=System.currentTimeMillis(); db.put("instances",task("short",now,false)); val engine=Engine(context); engine.tick()
        assertEquals("review",db.one("instances","short")!!.getString("status"))
        val changed=db.one("instances","short")!!; Store.set(changed,"status","done"); db.put("instances",changed)
        assertThrows(IllegalStateException::class.java) {engine.confirmFailure("short",now)}
        assertTrue(db.all("penalties").isEmpty()); assertEquals("done",db.one("instances","short")!!.getString("status"))
    }
    @Test fun protectedAppAndInvalidSavedBudgetAreRejected() {
        val now=System.currentTimeMillis(); val draft=UsageWeekPlans.parseRecommendation(content(),listOf(app()),report(now),now)
        val protected=Store.copy(draft.getJSONArray("apps").getJSONObject(0)); Store.set(protected,"package","com.android.settings")
        assertThrows(IllegalArgumentException::class.java) {UsageWeekPlans.save(context,draft,JSONArray(listOf(protected)),now)}
        assertNull(db.one("state","usageWeekPlans"))
        val corrupt=Store.copy(draft); Store.set(corrupt.getJSONArray("apps").getJSONObject(0),"dailyMinutes",-1)
        db.put("state",Store.obj("id","usageWeekPlans","plans",JSONArray(listOf(corrupt))))
        assertThrows(IllegalArgumentException::class.java) {db.prepareImport(db.exportJson())}
    }
}
