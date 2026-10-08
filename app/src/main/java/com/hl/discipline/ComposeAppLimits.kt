package com.hl.discipline

import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** UI status only. Enforcement still uses the existing service and quota engine. */
internal fun ComposeMainActivity.appRestrictionMode(): String {
    if (accessibilityStatus() == "运行中") return "无障碍模式"
    val heartbeat = db.one("state", "fallbackHeartbeat")
    return if (heartbeat != null && heartbeat.optLong("time") > 0 &&
        System.currentTimeMillis() - heartbeat.optLong("time") < 15_000 &&
        heartbeat.optString("error").isEmpty()) "尽力模式" else "未运行"
}

@Composable
internal fun AppLimitsScreen(activity: ComposeMainActivity, onBack: () -> Unit,
                             scrollState: androidx.compose.foundation.ScrollState? = null) {
    val revision = activity.revision
    val limits = remember(revision) {
        activity.db.settings().optJSONArray("appLimits")?.let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
        } ?: emptyList()
    }
    val access = AppUsageLimits.hasAccess(activity)
    val overlayAccess = Settings.canDrawOverlays(activity)
    val fallbackEnabled = remember(revision) { activity.db.settings().optBoolean("fallbackEnabled") }
    val heartbeat = remember(revision) { activity.db.one("state", "fallbackHeartbeat") }
    val fallbackRunning = heartbeat != null && heartbeat.optLong("time") > 0 &&
        System.currentTimeMillis() - heartbeat.optLong("time") < 15_000 &&
        heartbeat.optString("error").isEmpty()
    var fallbackConfirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selectedPackage by remember { mutableStateOf("") }
    var pendingPackage by remember { mutableStateOf("") }
    var deletePackage by remember { mutableStateOf("") }

    Screen("应用限额", onBack = onBack, scrollState = scrollState) {
        Caption("每日 00:00、每周一 00:00 重置。任一限额用尽，该应用将限制至对应周期结束。")
        Primary("添加应用限额", Modifier.fillMaxWidth()) {
            selectedPackage = ""
            picking = true
        }
        Panel {
            Text("拦截状态", style = MaterialTheme.typography.titleMedium)
            Caption("当前：${activity.appRestrictionMode()}")
            Caption(if (fallbackRunning) "备用拦截运行中；无障碍服务正常时由它优先处理。"
                else if (fallbackEnabled) "备用拦截已开启，等待权限或已启用规则。"
                else "无障碍服务负责及时拦截；可另外开启备用拦截。")
            Caption("备用模式为尽力拦截，可能延迟。需授权使用情况访问与系统悬浮窗，并保留常驻通知；后台弹出权限不能替代这些授权。")
            Secondary(if (fallbackEnabled) "关闭备用拦截" else "开启备用拦截") {
                if (fallbackEnabled) {
                    if (activity.act {
                        val settings = activity.db.settings()
                        Store.set(settings, "fallbackEnabled", false)
                        activity.db.put("state", settings)
                    }) FallbackBlockService.sync(activity)
                } else fallbackConfirm = true
            }
            if (fallbackEnabled && !access) Secondary("授权使用情况访问") {
                activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            if (fallbackEnabled && !overlayAccess) Secondary("授权系统悬浮窗") {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")))
            }
        }
        if (!access) Panel {
            Text("需要使用情况访问", style = MaterialTheme.typography.titleMedium)
            Caption("授权后才能读取实际使用时长。未授权时不会按时长拦截。")
            Secondary("前往系统授权") {
                activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        if (limits.isEmpty()) Panel {
            Text("尚未设置应用限额", style = MaterialTheme.typography.titleMedium)
            Caption("为应用设置每日或每周可用时长，超额后限制打开。")
        }
        limits.forEach { limit ->
            val pkg = limit.optString("package")
            key(pkg) {
                val appLabel = appNames(activity, JSONArray().put(pkg))
                val usage by produceState<AppUsageLimits.Usage?>(null, pkg, revision, access) {
                    value = if (access) withContext(Dispatchers.IO) {
                        AppUsageLimits.read(activity, pkg, System.currentTimeMillis())
                    } else null
                }
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(appLabel, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Switch(checked = limit.optBoolean("enabled", true),
                            modifier = Modifier.semantics { contentDescription = "$appLabel 使用时长限制" },
                            onCheckedChange = { enabled ->
                            saveAppLimit(activity, pkg, JSONObject(limit.toString()).apply { put("enabled", enabled) })
                        })
                    }
                    val daily = limit.optInt("dailyMinutes")
                    val weekly = limit.optInt("weeklyMinutes")
                    Caption(listOfNotNull(daily.takeIf { it > 0 }?.let { "每日 ${it} 分钟" },
                        weekly.takeIf { it > 0 }?.let { "每周 ${it} 分钟" }).joinToString(" · "))
                    if (usage?.available == true) {
                        val today = (usage!!.todayMillis / Rules.MINUTE).toInt()
                        val week = (usage!!.weekMillis / Rules.MINUTE).toInt()
                        Caption("已使用：今日 $today 分钟 · 本周 $week 分钟")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Secondary("调整") { editing = limit }
                        Secondary("删除") { deletePackage = pkg }
                    }
                }
            }
        }
        Caption("系统的使用统计可能稍晚更新；关闭应用界面不会停止已连接的无障碍服务。系统强行停止或撤销权限后，限制无法继续执行。")
    }
    AppPicker(activity, "[]", picking, { picking = false }, singleSelection = true,
        onHidden = {
            // Continue from the native sheet exit, rather than stacking two modal windows.
            selectedPackage = pendingPackage
            pendingPackage = ""
        }) { result ->
        val chosen = jsonArray(result)
        if (chosen.length() != 1) activity.toast("一次选择一个应用")
        else {
            val pkg = chosen.optString(0)
            if (limits.any { it.optString("package") == pkg }) activity.toast("该应用已设置限额")
            else {
                pendingPackage = pkg
                picking = false
            }
        }
    }
    if (selectedPackage.isNotEmpty()) {
        AppLimitEditor(activity, selectedPackage, null, onDismiss = { selectedPackage = "" }) {
            selectedPackage = ""
        }
    }
    editing?.let { limit ->
        AppLimitEditor(activity, limit.optString("package"), limit,
            onDismiss = { editing = null }) { editing = null }
    }
    if (deletePackage.isNotEmpty()) AlertDialog(onDismissRequest = { deletePackage = "" },
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        title = { Text("删除应用限额？") },
        text = { Text("取消该应用的时长限制，已有使用记录保留。") },
        confirmButton = { TextButton(onClick = {
            saveAppLimit(activity, deletePackage, null)
            deletePackage = ""
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deletePackage = "" }) { Text("取消") } })
    if (fallbackConfirm) ConfirmDialog("开启备用拦截？",
        "需要使用情况访问、系统悬浮窗和常驻通知。无障碍服务断开时备用模式会尽力拦截，但可能延迟或被系统终止。", {
            fallbackConfirm = false
            if (activity.act {
                val settings = activity.db.settings()
                Store.set(settings, "fallbackEnabled", true)
                activity.db.put("state", settings)
            }) {
                FallbackBlockService.sync(activity)
                if (!access) activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                else if (!overlayAccess) activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")))
            }
        }) { fallbackConfirm = false }
}

private fun saveAppLimit(activity: ComposeMainActivity, pkg: String, replacement: JSONObject?): Boolean {
    val saved = activity.act {
        if (BlockService.protectedApp(activity, pkg)) throw IllegalArgumentException("系统关键应用不能设置限制")
        val settings = activity.db.settings()
        val existing = settings.optJSONArray("appLimits") ?: JSONArray()
        val next = JSONArray()
        for (index in 0 until existing.length()) {
            val item = existing.optJSONObject(index) ?: continue
            if (item.optString("package") != pkg) next.put(item)
        }
        if (replacement != null) next.put(replacement)
        Store.set(settings, "appLimits", next)
        activity.db.put("state", settings)
    }
    if (saved) FallbackBlockService.sync(activity)
    return saved
}

@Composable
private fun AppLimitEditor(activity: ComposeMainActivity, pkg: String, existing: JSONObject?,
    onDismiss: () -> Unit, onSaved: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var daily by remember(pkg) { mutableStateOf(existing?.optInt("dailyMinutes")?.takeIf { it > 0 }?.toString() ?: "") }
    var weekly by remember(pkg) { mutableStateOf(existing?.optInt("weeklyMinutes")?.takeIf { it > 0 }?.toString() ?: "") }
    var error by remember(pkg) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        title = { Text(appNames(activity, JSONArray().put(pkg))) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("留空表示不设该周期的上限；至少填写一项。")
            Input("每日上限（分钟）", daily, { daily = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            Input("每周上限（分钟）", weekly, { weekly = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            val d = if (daily.isBlank()) 0 else daily.toIntOrNull()
            val w = if (weekly.isBlank()) 0 else weekly.toIntOrNull()
            if (d == null || w == null || d !in 0..1440 || w !in 0..10080 || (d == 0 && w == 0)) {
                error = "每日填写 1—1440 分钟、每周填写 1—10080 分钟，至少填一项"
                return@TextButton
            }
            if (saveAppLimit(activity, pkg, Store.obj("package", pkg, "dailyMinutes", d,
                "weeklyMinutes", w, "enabled", existing?.optBoolean("enabled", true) ?: true))) {
                keyboard?.hide()
                onSaved()
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
