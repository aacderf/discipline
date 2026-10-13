package com.hl.discipline

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** A full page keeps a multi-app editor usable with a keyboard or a small viewport. */
@Composable internal fun UsagePlanEditorScreen(activity:ComposeMainActivity,week:Long,onBack:()->Unit) {
    val templateText=rememberSaveable(week) { UsageWeekPlans.manualDraft(System.currentTimeMillis()).toString() }
    val template=remember(templateText) { JSONObject(templateText) }
    val originalText=rememberSaveable(week) {
        UsageWeekPlans.plans(activity.db).firstOrNull { it.optLong("start")==if(week>0) week else template.optLong("start") }?.toString().orEmpty()
    }
    val original=remember(originalText) { originalText.takeIf {it.isNotEmpty()}?.let(::JSONObject) }
    var appsText by rememberSaveable(week) { mutableStateOf(original?.optJSONArray("apps")?.toString()?:"[]") }
    var picking by remember { mutableStateOf(false) }
    var error by rememberSaveable(week) { mutableStateOf("") }
    val keyboard=LocalSoftwareKeyboardController.current
    val revision=activity.revision
    val current=remember(revision,originalText) { original?.let { saved ->
        UsageWeekPlans.plans(activity.db).firstOrNull {it.optLong("start")==saved.optLong("start")}
    } }
    if(week>0 && original==null) {
        Screen("使用计划",onBack=onBack) {Caption("这周计划已删除，返回后重新查看。");Secondary("返回") {onBack()}}
        return
    }
    val rows=jsonArray(appsText).let { array -> (0 until array.length()).map(array::getJSONObject) }
    fun replaceRow(pkg:String,field:String,value:String) {
        appsText=JSONArray(rows.map { Store.copy(it).apply {if(optString("package")==pkg) put(field,value)} }).toString()
        error=""
    }
    Box(Modifier.fillMaxSize().imePadding()) {
    Screen(if(original==null) "安排下周使用" else "调整使用计划",onBack=onBack) {
        val plan=original?:template
        Text("${Rules.date(plan.optLong("start"))}—${Rules.date(plan.optLong("end")-1)}",style=MaterialTheme.typography.titleMedium)
        Caption("每日 00:00、周一 00:00 重置。留空或 0 表示不设该周期上限；每个应用至少设置一项。")
        if(original?.optBoolean("enabled",true)==false) Caption("这周计划已停用；保存调整后仍保持停用，可在列表中恢复。")
        if(original!=null) Caption("调整不会清零已使用时长；若新的上限低于已用量，应用可能立即受到限制。")
        if(original!=null && current==null) Caption("这周计划已删除，无法保存旧窗口中的调整。")
        Secondary(if(rows.isEmpty()) "选择应用" else "调整应用 · ${rows.size} 个") {picking=true}
        if(rows.isEmpty()) Caption("选择 1–12 个应用，为下一周安排合适的每日或每周时长，无需配置 AI。")
        rows.forEach { app -> key(app.optString("package")) {
            val pkg=app.getString("package")
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(app.optString("label",pkg),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={appsText=JSONArray(rows.filter {it.optString("package")!=pkg}).toString();error=""},
                    modifier=Modifier.semantics {contentDescription="从周计划移除${app.optString("label")}"}) {Text("移除")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Input("每日分钟",app.optString("dailyText",app.optInt("dailyMinutes").toString()),
                    {replaceRow(pkg,"dailyText",it)},Modifier.weight(1f).semantics {contentDescription="${app.optString("label")}每日上限分钟"},keyboard=KeyboardType.Number)
                Input("每周分钟",app.optString("weeklyText",app.optInt("weeklyMinutes").toString()),
                    {replaceRow(pkg,"weeklyText",it)},Modifier.weight(1f).semantics {contentDescription="${app.optString("label")}每周上限分钟"},keyboard=KeyboardType.Number)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        } }
        Caption("原有长期限额保留，重叠时执行更严格的限额。保存不自动开启拦截权限。")
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        Primary(if(original==null) "保存下周计划" else "保存调整",Modifier.fillMaxWidth(),
            enabled=rows.isNotEmpty() && (original==null || current!=null)) {
            val edited=try {JSONArray(rows.map { app -> Store.copy(app).apply {
                fun minutes(field:String,stored:String):Int {
                    val input=app.optString(field,app.optInt(stored).toString()).trim()
                    return if(input.isEmpty()) 0 else input.toIntOrNull()?:error("${app.optString("label")}：限额须为整数分钟")
                }
                put("dailyMinutes",minutes("dailyText","dailyMinutes"));put("weeklyMinutes",minutes("weeklyText","weeklyMinutes"))
                remove("dailyText");remove("weeklyText")
            } })} catch(e:Exception) {error=e.message?:"限额格式无效";return@Primary}
            keyboard?.hide()
            if(activity.act {
                if(original==null) UsageWeekPlans.save(activity,template,edited,System.currentTimeMillis(),replaceExisting=false)
                else UsageWeekPlans.update(activity,original,edited,System.currentTimeMillis())
            }) {activity.toast("使用计划已保存");onBack()}
        }
    }
    }
    val selected=JSONArray(rows.map {it.optString("package")}).toString()
    AppPicker(activity,selected,picking,{picking=false}) { selection ->
        val packages=jsonArray(selection).toStringList()
        if(packages.size>12) activity.toast("每周最多安排 12 个应用")
        else {
            appsText=JSONArray(packages.map { pkg -> rows.firstOrNull {it.optString("package")==pkg}
                ?: Store.obj("package",pkg,"label",appNames(activity,JSONArray().put(pkg)),"dailyMinutes",60,"weeklyMinutes",0,"enabled",true) }).toString()
            picking=false;error=""
        }
    }
}
