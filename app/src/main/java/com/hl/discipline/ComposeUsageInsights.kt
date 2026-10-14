package com.hl.discipline

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private fun usageTime(millis:Long):String {
    val minutes=millis/Rules.MINUTE
    return if(minutes<60) "$minutes 分" else "${minutes/60}小时${minutes%60}分"
}

@Composable internal fun UsageInsightsScreen(activity:ComposeMainActivity,onBack:()->Unit,onPlan:(Long)->Unit) {
    val revision=activity.revision
    val access=remember(revision) { AppUsageLimits.hasAccess(activity) }
    var reload by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<UsageReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var keySettings by remember { mutableStateOf(false) }
    var keyConfigured by remember(revision) { mutableStateOf(DeepSeekKeyStore.hasKey(activity)) }
    var sendConfirm by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableIntStateOf(0) }
    var goal by rememberSaveable { mutableStateOf("减少娱乐应用的使用，保留学习和必要沟通。") }
    val draft=remember(revision) { activity.db.one("state","usageAnalysisDraft") }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var deletingWeek by remember { mutableLongStateOf(0) }
    var deletingDraft by remember { mutableStateOf<JSONObject?>(null) }
    val today=Rules.date(System.currentTimeMillis())
    LaunchedEffect(access,reload,today) {
        if(!access) { report=null; return@LaunchedEffect }
        loading=true; error=""
        try { report=withContext(Dispatchers.IO) { UsageInsightsRepository.load(activity,System.currentTimeMillis(),reload>0) } }
        catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { error="系统使用记录暂不可用，请检查授权后刷新。" }
        finally { loading=false }
    }
    var selected by rememberSaveable(report?.start) { mutableStateOf(report?.apps?.filter { it.canLimit }?.take(5)?.joinToString("|"){it.pkg}.orEmpty()) }
    val chosen=selected.split('|').filter { it.isNotEmpty() }.toSet()
    val selectedApps=report?.apps?.filter {it.canLimit && it.pkg in chosen}.orEmpty()
    Box(Modifier.fillMaxSize().imePadding()) {
    Screen("应用使用与下周计划",onBack=onBack,
        fixedContent={ Box(Modifier.fillMaxWidth().padding(horizontal=AppSpacing.page).padding(bottom=8.dp)) {
            DraggableSegment(listOf("应用使用统计","制定下周计划"),view,activity.motionEnabled) {view=it}
        } }, bottom={
            if(view==0 && selectedApps.isNotEmpty()) Row(Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface).heightIn(min=48.dp)
                .padding(horizontal=AppSpacing.page),verticalAlignment=Alignment.CenterVertically) {
                Text("已勾选 ${selectedApps.size} 个分析应用",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                TextButton(onClick={view=1}) {Text("去生成下周计划 ›",style=MaterialTheme.typography.labelSmall)}
            }
        }) {
        val data=report
        if(view==0) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("过去 7 天",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton(onClick={reload++},enabled=access && !loading) { Text(if(loading) "更新中" else "刷新") }
        }
        if(!access) Panel {
            Text("查看平常的应用使用",style=MaterialTheme.typography.titleMedium)
            Caption("授权后可在本机查看前台时长与使用分布。尚未读取或上传任何使用记录。")
            Secondary("授权使用情况访问") { AndroidCompat.openSettings(activity,Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        if(error.isNotEmpty()) Caption(error)
        if(data!=null) {
            Caption("${data.dates.first()}—${data.dates.last()} · 更新于 ${timeText(data.end)}")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("总前台时长" to usageTime(data.total),"有记录日均" to usageTime(data.total/data.availableDays.count { it }.coerceAtLeast(1)),"使用应用" to "${data.apps.size} 个")
                    .forEach { (label,value) -> Column(Modifier.weight(1f)) {
                        Caption(label); Text(value,style=MaterialTheme.typography.titleMedium)
                    } }
            }
            Section("每日应用前台时长")
            UsageTrend(data)
            Caption("${data.availableDays.count { it }}/7 天有系统记录；— 表示未提供记录，不视为零使用。")
            Caption("单位：分钟 · 薄荷色为应用前台累计时长；多窗口可能同时累计。系统汇总可能延后，历史记录可能不完整。")
            if(data.apps.isEmpty()) Panel {
                Text("暂没有可分析的应用记录",style=MaterialTheme.typography.titleMedium)
                Caption("正常使用手机后刷新；不把缺失记录当作零使用或生成虚构结论。")
            } else {
                val top=data.apps.first()
                Text("${top.label} 使用最多，占记录时长的 ${if(data.total>0) top.total*100/data.total else 0}%。",
                    style=MaterialTheme.typography.bodyMedium)
                Section("应用分布 · 勾选用于分析的应用")
                val visible=if(showAll) data.apps else data.apps.take(10)
                visible.forEach { app ->
                    val onSelectionChange: (Boolean) -> Unit = { checked ->
                        if(checked && chosen.size>=12) activity.toast("最多分析 12 个应用")
                        else selected=(if(checked) chosen+app.pkg else chosen-app.pkg).joinToString("|")
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min=64.dp)
                        .toggleable(value=app.pkg in chosen,enabled=app.canLimit,role=Role.Checkbox,
                            interactionSource=remember { MutableInteractionSource() },indication=null,
                            onValueChange=onSelectionChange),verticalAlignment=Alignment.CenterVertically) {
                    GlassCheckbox(checked=app.pkg in chosen,enabled=app.canLimit,onCheckedChange=null,
                        modifier=Modifier.semantics { contentDescription="分析${app.label}" })
                    Column(Modifier.weight(1f)) { Text(app.label,style=MaterialTheme.typography.bodyLarge)
                        Caption(if(app.canLimit) "有记录日均 ${usageTime(app.total/data.availableDays.count { it }.coerceAtLeast(1))}" else "必要应用 · 仅显示数据") }
                    Text(usageTime(app.total),style=MaterialTheme.typography.labelLarge)
                } }
                if(data.apps.size>10) TextButton(onClick={showAll=!showAll}) { Text(if(showAll) "收起应用列表" else "查看全部 ${data.apps.size} 个应用") }
            }
        } else if(loading) Caption("正在本机整理使用记录…")
        } else {
        Caption("已勾选 ${selectedApps.size} 个分析应用")
        TextButton(onClick={view=0}) {Text("选择分析应用 ›")}
        Section("使用计划")
        Secondary("手动安排下周") {onPlan(0)}
        val weekPlans=UsageWeekPlans.plans(activity.db).filter {it.optLong("end")>System.currentTimeMillis()}
        if(weekPlans.isEmpty()) Caption("还没有周计划。可手动安排，也可让 AI 根据使用记录给出建议。")
        weekPlans.forEach { plan -> Panel {
            val start=plan.optLong("start")
            val enabled=plan.optBoolean("enabled",true)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("${Rules.date(start)}—${Rules.date(plan.optLong("end")-1)}",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                GlassSwitch(enabled,{ value ->activity.act {UsageWeekPlans.setEnabled(activity,start,value,System.currentTimeMillis())} },
                    modifier=Modifier.semantics {contentDescription="${Rules.date(start)}的使用计划"})
            }
            Caption("${plan.optJSONArray("apps")?.length()?:0} 个应用 · "+
                if(!enabled) "已停用" else if(start>System.currentTimeMillis()) "等待周一生效" else "本周生效中")
            val apps=plan.optJSONArray("apps")
            for(index in 0 until (apps?.length()?:0)) {
                val app=apps!!.getJSONObject(index)
                Caption(app.optString("label")+" · "+listOfNotNull(
                    app.optInt("dailyMinutes").takeIf {it>0}?.let {"每日 $it 分钟"},
                    app.optInt("weeklyMinutes").takeIf {it>0}?.let {"每周 $it 分钟"}).joinToString(" · "))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick={onPlan(start)}) {Text("调整限额")}
                TextButton(onClick={deletingWeek=start}) {Text("删除计划")}
            }
        } }
        Section("AI 下周建议")
        Caption("DeepSeek V4.1 · 生成建议后可调整，确认后才启用；原有应用限额保留，重叠时执行更严格的限额。")
        Input("希望如何安排下一周",goal,{goal=it.take(600)})
        Secondary(if(keyConfigured) "DeepSeek 已配置 · 管理" else "配置 DeepSeek") { keySettings=true }
        GlassPrimaryButton(if(activity.dataOperation.isEmpty()) "生成下周建议" else "正在处理…",Modifier.fillMaxWidth(),
            enabled=data!=null && selectedApps.isNotEmpty() && keyConfigured && activity.dataOperation.isEmpty()) { sendConfirm=true }
        if(draft!=null) Panel {
            Text("上次分析 · ${timeText(draft.optLong("created"))}",style=MaterialTheme.typography.labelLarge)
            Text(draft.optString("summary"),style=MaterialTheme.typography.bodyMedium)
            if(draft.optLong("start")<=System.currentTimeMillis()) Caption("建议日期已过；可查看内容，启用前需要重新生成。")
            Secondary("查看并调整建议") { review=draft }
            TextButton(onClick={deletingDraft=draft},enabled=activity.dataOperation.isEmpty()) {Text("清除建议")}
        }
        }
    }
    }
    if(sendConfirm) ConfirmDialog("发送使用摘要给 DeepSeek？",
        "仅发送所选应用名称、每日使用分钟和你的目标，不发送位置、任务或消息内容。使用你自己的 API 账户，按官方计费。建议不会自动执行。",{
        val data=report
        val requestGoal=goal
        val requestApps=selectedApps
        sendConfirm=false
        if(data!=null) activity.runDataOperation("正在分析使用计划",{
            val result=DeepSeekUsageClient().analyze(DeepSeekKeyStore.read(activity),data,requestApps,requestGoal,System.currentTimeMillis())
            activity.db.put("state",result)
            result
        }) { review=it }
    }) {sendConfirm=false}
    if(keySettings) DeepSeekSettings(activity,onDismiss={keySettings=false;keyConfigured=DeepSeekKeyStore.hasKey(activity)})
    if(deletingWeek>0) ConfirmDialog("删除这周使用计划？","只取消这份周限额，不删除使用记录；原有长期限额继续保留。",{
        if(activity.act {UsageWeekPlans.remove(activity,deletingWeek)}) deletingWeek=0
    }) {deletingWeek=0}
    deletingDraft?.let { value -> ConfirmDialog("清除这份建议？","已保存的周计划保留。再次生成建议将使用新的 API 请求。",{
        if(activity.act {UsageWeekPlans.discardDraft(activity,value)}) deletingDraft=null
    }) {deletingDraft=null} }
    review?.let { value -> UsagePlanReview(activity,value,onDismiss={review=null}) }
}

@Composable private fun UsageTrend(report:UsageReport) {
    val values=remember(report) { report.daily }
    val max=values.maxOrNull()?.coerceAtLeast(1)?:1
    val chartHeight=(96+40*LocalDensity.current.fontScale).dp
    Row(Modifier.fillMaxWidth().height(chartHeight).testTag("usage-daily-chart"),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.Bottom) {
        values.forEachIndexed { index,value -> Column(Modifier.weight(1f).semantics {
            contentDescription="${report.dates[index]}，"+if(report.availableDays[index]) "${value/Rules.MINUTE} 分钟" else "系统未提供记录"
        },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(if(report.availableDays[index]) "${value/Rules.MINUTE}" else "—",style=MaterialTheme.typography.labelSmall,maxLines=1)
            Box(Modifier.width(18.dp).height((value.toFloat()/max*88).coerceAtLeast(2f).dp)
                .background(if(report.availableDays[index]) AppColors.current.chartFocus else MaterialTheme.colorScheme.outlineVariant,AppShapes.material.small))
            Text("${report.dates[index].dayOfMonth}日",style=MaterialTheme.typography.labelSmall)
        } }
    }
}

@Composable private fun DeepSeekSettings(activity:ComposeMainActivity,onDismiss:()->Unit) {
    var key by remember { mutableStateOf("") }
    val stored=DeepSeekKeyStore.hasKey(activity)
    AlertDialog(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,
        title={Text("DeepSeek V4.1")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Caption("官方接口 · deepseek-flash。API Key 加密保存在本机，不进入备份；卸载后需要重新配置。")
        OutlinedTextField(key,{key=it.trim()},label={Text(if(stored) "新 API Key，留空保持现有" else "API Key")},
            visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        if(stored) {
            Secondary("验证连接") { activity.runDataOperation("正在验证 DeepSeek",{
                DeepSeekUsageClient().verify(DeepSeekKeyStore.read(activity))
            }) {activity.toast("DeepSeek V4.1 连接正常")} }
            TextButton(onClick={DeepSeekKeyStore.clear(activity);onDismiss()}) {Text("删除 API Key")}
        }
    }},confirmButton={TextButton(onClick={
        if(key.isBlank() && stored) onDismiss()
        else activity.runDataOperation("正在保存 API Key",{DeepSeekKeyStore.save(activity,key)}) {onDismiss()}
    },enabled=activity.dataOperation.isEmpty() && (stored || key.isNotBlank())) {Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

@Composable private fun UsagePlanReview(activity:ComposeMainActivity,draft:JSONObject,onDismiss:()->Unit) {
    val original=remember(draft.toString()) { draft.getJSONArray("apps").let { array ->(0 until array.length()).map(array::getJSONObject) } }
    val daily=remember(draft.toString()) { mutableStateMapOf<String,String>().apply { original.forEach { put(it.getString("package"),it.optInt("dailyMinutes").toString()) } } }
    val weekly=remember(draft.toString()) { mutableStateMapOf<String,String>().apply { original.forEach { put(it.getString("package"),it.optInt("weeklyMinutes").toString()) } } }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,
        title={Text("确认下周计划")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Caption("${Rules.date(draft.optLong("start"))}—${Rules.date(draft.optLong("end")-1)} · 0 表示不设该周期上限")
        Text(draft.optString("summary"),style=MaterialTheme.typography.bodyMedium)
        original.forEach { app -> val pkg=app.getString("package")
            Text(app.optString("label"),style=MaterialTheme.typography.titleMedium)
            Caption(app.optString("reason"))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Input("每日分钟",daily[pkg].orEmpty(),{daily[pkg]=it},Modifier.weight(1f),keyboard=androidx.compose.ui.text.input.KeyboardType.Number)
                Input("每周分钟",weekly[pkg].orEmpty(),{weekly[pkg]=it},Modifier.weight(1f),keyboard=androidx.compose.ui.text.input.KeyboardType.Number)
            }
        }
        Caption("相同日期的已保存周计划将被替换；本周和永久规则不变。没有可用拦截模式时，请先到应用限额检查权限。")
        if(draft.optLong("start")<=System.currentTimeMillis()) Caption("建议日期已过，请返回重新生成下周建议。")
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={TextButton(onClick={
        val edited=try { JSONArray(original.map { app ->Store.copy(app).apply {
            put("dailyMinutes",daily[app.getString("package")]?.toIntOrNull()?:error("请输入每日整数分钟"))
            put("weeklyMinutes",weekly[app.getString("package")]?.toIntOrNull()?:error("请输入每周整数分钟"))
        } }) } catch(e:Exception) {error=e.message?:"限额格式无效";return@TextButton}
        if(activity.act {UsageWeekPlans.save(activity,draft,edited,System.currentTimeMillis())}) {activity.toast("下周计划已保存，周一开始生效");onDismiss()}
    },enabled=draft.optLong("start")>System.currentTimeMillis() && activity.dataOperation.isEmpty()) {Text("确认启用")}},dismissButton={TextButton(onClick=onDismiss){Text("返回")}})
}
