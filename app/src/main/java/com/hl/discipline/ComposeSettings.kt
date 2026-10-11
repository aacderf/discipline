package com.hl.discipline

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import org.json.JSONObject

/** A group owns one surface. Rows keep their value and explanation distinct at larger fonts. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Surface(shape = AppShapes.material.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm), content = content)
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String = "", help: String = "",
                       statusFeedback: Boolean = false, divider: Boolean = true, onClick: () -> Unit) {
    val valueColor by animateColorAsState(
        if (statusFeedback && value in listOf("已授权", "已开启", "已允许", "已豁免", "运行中"))
            MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(if (LocalMotionEnabled.current) AppMotion.stateChange else 0), label = "权限结果")
    val inlineValue = value.length <= 8 && LocalConfiguration.current.fontScale <= 1.15f
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).quietClickable(title, onClick = onClick)
            .padding(vertical = AppSpacing.sm), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (inlineValue && value.isNotBlank()) Text(value,
                        style = MaterialTheme.typography.bodyMedium, color = valueColor)
                }
                if (!inlineValue && value.isNotBlank()) Text(value,
                    style = MaterialTheme.typography.bodyMedium, color = valueColor)
                if (help.isNotBlank()) Caption(help)
            }
            Text("›", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
    }
}

@Composable
private fun StatusLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = AppSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun ComposeMainActivity.openAppSettings() {
    AndroidCompat.openSettings(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

internal fun ComposeMainActivity.accessibilityStatus(): String {
    val manager = getSystemService(AccessibilityManager::class.java)
    val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == packageName }
    if (!enabled) return "未开启"
    val heartbeat = db.one("state", "blockHeartbeat")
    if (heartbeat?.optString("error")?.isNotEmpty() == true &&
        System.currentTimeMillis() - heartbeat.optLong("time") < 30_000) return "服务异常"
    return if (heartbeat != null && System.currentTimeMillis() - heartbeat.optLong("time") < 15_000)
        "运行中" else "已授权，等待连接"
}

@Composable
internal fun SettingsScreen(activity: ComposeMainActivity, nav: NavHostController) {
    val revision = activity.revision
    val settings = remember(revision) { activity.db.settings() }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var trialConfirm by remember { mutableStateOf(false) }
    var monitorConfirm by remember { mutableStateOf(false) }
    var fusionNotice by remember { mutableStateOf(false) }
    var appsPicker by remember { mutableStateOf(false) }
    var numberKey by remember { mutableStateOf("") }
    var reminderChoice by remember { mutableStateOf("") }
    var backupNotice by remember { mutableStateOf(false) }
    var accessGuide by remember { mutableStateOf(false) }
    var themeChoice by rememberSaveable { mutableStateOf(false) }
    val fine = activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = Build.VERSION.SDK_INT < 29 || activity.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val notifications = activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    val alarm = AndroidCompat.exactAlarmsAllowed(activity)
    val battery = activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)
    val access = activity.accessibilityStatus()
    val usageAccess = AppUsageLimits.hasAccess(activity)
    val appLimitCount = settings.optJSONArray("appLimits")?.length() ?: 0
    val fallbackEnabled = settings.optBoolean("fallbackEnabled")
    val overlayAccess = Settings.canDrawOverlays(activity)
    val fallbackHeartbeat = activity.db.one("state", "fallbackHeartbeat")
    val fallbackRunning = fallbackHeartbeat != null && fallbackHeartbeat.optLong("time") > 0 &&
        System.currentTimeMillis() - fallbackHeartbeat.optLong("time") < 15_000 &&
        fallbackHeartbeat.optString("error").isEmpty()
    val limitMode = when {
        access == "运行中" -> "无障碍模式"
        fallbackRunning -> "尽力模式"
        else -> "未运行"
    }
    val location = remember(revision) { activity.db.one("state", "location") }
    val monitoring = settings.optBoolean("monitor")
    val locationState = when {
        !monitoring -> "未开启"
        !fine -> "需要定位权限"
        !LocationService.running -> "服务未运行"
        location == null -> "等待定位"
        System.currentTimeMillis() - location.optLong("observedAt", location.optLong("time")) > 120_000 -> "定位中断"
        else -> "监测中"
    }

    val pending = buildList {
        if (monitoring && !fine) add("精确定位")
        if (monitoring && !background) add("后台定位")
        if (monitoring && fine && AndroidCompat.serviceProblem(activity, LocationService::class.java).isNotBlank()) add("地点后台运行")
        if (!notifications) add("通知")
        if (!alarm) add("准时提醒")
        if ((!settings.optBoolean("trial") || appLimitCount > 0) &&
            access != "运行中" && !fallbackRunning) add("应用限制服务")
        if (appLimitCount > 0 && !usageAccess) add("使用情况访问")
        if ((fallbackEnabled || settings.optBoolean("companionEnabled")) && !overlayAccess) add("系统悬浮窗")
        if (monitoring && !battery) add("电池与后台")
    }
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    val page = if (permissionsOpen) 1 else 0
    val settingsScroll = androidx.compose.foundation.rememberScrollState()
    val permissionsScroll = androidx.compose.foundation.rememberScrollState()
    SubpageTransition(page, backEnabled = currentRoute == "settings", onBack = {
        permissionsOpen = false
    }) { shown ->
    if (shown == 1) {
        Screen("运行与权限", onBack = { permissionsOpen = false }, scrollState = permissionsScroll) {
            Caption(if (pending.isEmpty()) "运行条件已就绪" else "${pending.size} 项需要处理：${pending.joinToString("、")}")
            Caption("按需要开启功能与授权；未授权时仍可管理计划、课表和使用前台专注计时。")
            Section("地点监测")
            SettingRow("地点监测开关", if (monitoring) "已开启" else "未开启",
                "开启后按已保存地点和计划累计到场", statusFeedback = true) {
                if (!fine) activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 30)
                else if (monitoring && LocationService.running) monitorConfirm = true
                else activity.act { Store.set(settings, "monitor", true); activity.db.put("state", settings)
                    AndroidCompat.startService(activity, Intent(activity, LocationService::class.java)) }
            }
            SettingRow("精确定位", if (fine) "已授权" else "未授权", "用于地点计时", statusFeedback = true) {
                if (!fine) activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 30)
                else activity.openAppSettings()
            }
            SettingRow("后台定位", if (background) "已授权" else "未授权", "需要时在系统中选择始终允许",
                statusFeedback = true) { activity.openAppSettings() }
            SettingRow("融合定位", if (PositionSource.consented(activity)) "已启用" else "未启用",
                "GPS、Wi-Fi 与基站协同；较差结果不会直接覆盖近期精准位置") {
                if (PositionSource.consented(activity)) activity.act {
                    activity.getSharedPreferences("PlaceActivity", 0).edit()
                        .putBoolean("locationPrivacyAccepted", false).apply()
                    if (monitoring && fine) AndroidCompat.startService(activity, Intent(activity, LocationService::class.java))
                } else fusionNotice = true
            }
            if (location != null) Caption("${location.optString("source", "系统定位")} · 估计误差 ${numberText(location.optDouble("accuracy"))} 米\n${location.optString("status")}")
            Section("应用限制与提醒")
            SettingRow("应用限制服务", access, statusFeedback = true) { accessGuide = true }
            SettingRow("使用情况访问", if (usageAccess) "已授权" else "未授权",
                "按应用累计每日与每周用量", statusFeedback = true) {
                AndroidCompat.openSettings(activity, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            if (fallbackEnabled) SettingRow("系统悬浮窗", if (overlayAccess) "已授权" else "未授权",
                "仅用于无障碍服务断开后的尽力备用拦截", statusFeedback = true) {
                AndroidCompat.openSettings(activity, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")))
            }
            SettingRow("通知", if (notifications) "已开启" else "未开启", statusFeedback = true) {
                if (Build.VERSION.SDK_INT >= 33 && !notifications) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
                else AndroidCompat.openSettings(activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            SettingRow("准时提醒", if (alarm) "已允许" else "可能延迟", statusFeedback = true) {
                if (Build.VERSION.SDK_INT >= 31) AndroidCompat.openSettings(activity, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${activity.packageName}")))
            }
            SettingRow("电池与后台", if (battery) "已豁免" else "系统管理", statusFeedback = true) {
                AndroidCompat.openSettings(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            Section("本机后台运行")
            SettingRow("后台运行与自启动", "系统设置", AndroidCompat.backgroundHint()) {
                activity.openAppSettings()
            }
            Caption("菜单名称随系统版本变化；厂商后台弹出权限不能替代 Android 悬浮窗授权。")
            listOf(LocationService::class.java, FocusCompanionService::class.java,
                FallbackBlockService::class.java).forEach { service ->
                val problem = AndroidCompat.serviceProblem(activity, service)
                if (problem.isNotBlank()) Caption(when (service) {
                    LocationService::class.java -> "地点监测：$problem"
                    FocusCompanionService::class.java -> "专注与悬浮球：$problem"
                    else -> "备用拦截：$problem"
                })
            }
        }
    } else Screen("设置", onBack = { nav.popBackStack() }, scrollState = settingsScroll) {
        SettingsGroup("运行状态") {
            StatusLine("地点监测", locationState)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
            StatusLine("应用拦截", limitMode)
            if (pending.isNotEmpty()) {
                Text("${pending.size} 项运行条件待完善", color = AppColors.current.warningText,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                SettingRow("检查运行条件", "查看", divider = false) { permissionsOpen = true }
            }
        }
        SettingsGroup("外观") {
            SettingRow("主题", activity.themeMode.title, "浅色、深色或跟随系统", divider = false) {
                themeChoice = true
            }
        }
        SettingsGroup("专注设置") {
            val options = listOf("focusMinutes" to "默认专注时长", "shortBreak" to "短休息时长",
                "longBreak" to "长休息时长", "cycles" to "长休息间隔")
            options.forEachIndexed { index, (key, title) ->
                SettingRow(title, "${settings.optInt(key)} ${if (key == "cycles") "轮" else "分钟"}",
                    divider = index != options.lastIndex) { numberKey = key }
            }
        }
        SettingsGroup("提醒") {
            SettingRow("计划提醒", when (settings.optString("planReminderMode", "sound")) {
                "off" -> "关闭"; "silent" -> "静音通知"; else -> "有声通知"
            }, "可在每项计划中单独调整") { reminderChoice = "planReminderMode" }
            SettingRow("课程提醒", when (settings.optString("classReminderMode", "sound")) {
                "off" -> "关闭"; "silent" -> "静音通知"; else -> "有声通知"
            }, "校对并确认课表后启用") { reminderChoice = "classReminderMode" }
            SettingRow("课前提前提醒", "${settings.optInt("classReminderMinutes", 10)} 分钟",
                divider = false) { numberKey = "classReminderMinutes" }
        }
        SettingsGroup("任务未完成时") {
            SettingRow("任务限制模式", if (settings.optBoolean("trial")) "仅记录" else "实际拦截",
                "仅影响任务未完成后的限制，应用限额单独生效") { trialConfirm = true }
            SettingRow("默认受限应用", appNames(activity, settings.optJSONArray("defaultApps")),
                "新计划默认使用这组选项") { appsPicker = true }
            SettingRow("每日限制时长上限", "${settings.optInt("capHours", 8)} 小时",
                "任务限制每日最多持续多久，不是应用可用时长") { numberKey = "capHours" }
            SettingRow("任务限制记录", "查看", divider = false) { nav.navigate("penalties") }
        }
        SettingsGroup("运行与权限") {
            SettingRow("权限和后台运行", if (pending.isEmpty()) "已就绪" else "${pending.size} 项待处理",
                "定位、应用拦截、通知与电池管理", divider = false) { permissionsOpen = true }
        }
        SettingsGroup("首页内容") {
            SettingRow("首页寄语", if (settings.optBoolean("rotate")) "每天轮换" else "固定首条",
                "留空即可隐藏", divider = false) { nav.navigate("quotes") }
        }
        SettingsGroup("数据与隐私") {
            SettingRow("导出备份", help = "包含计划、课表、记录和设置") { activity.exportJson() }
            if (activity.db.latestRestoreSnapshot() != null)
                SettingRow("导出恢复前的数据", help = "保留最近一次恢复前的内容") { activity.exportOldData() }
            SettingRow("从备份恢复", help = "校验后替换当前数据") { backupNotice = true }
            SettingRow("操作与异常记录") { nav.navigate("events") }
            SettingRow("隐私与使用说明", divider = false) { nav.navigate("about") }
        }
        val version = remember { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "" }
        Caption("自律 $version · 本地优先")
        Spacer(Modifier.height(20.dp))
    }
    }
    if (themeChoice) AlertDialog(onDismissRequest = { themeChoice = false },
        title = { Text("主题") },
        text = {
            Column(Modifier.selectableGroup()) {
                AppThemeMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .selectable(selected = activity.themeMode == mode, role = Role.RadioButton,
                            onClick = {
                                if (activity.act {
                                    Store.set(settings, "themeMode", mode.value)
                                    activity.db.put("state", settings)
                                }) themeChoice = false
                            }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = activity.themeMode == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }, confirmButton = {}, dismissButton = {
            TextButton(onClick = { themeChoice = false }) { Text("取消") }
        })
    if (monitorConfirm) ConfirmDialog("停止地点监测？", "停止期间无法累计到场时间，受影响的任务可能需要核验。", {
        if (activity.act { Store.set(settings, "monitor", false); activity.db.put("state", settings)
            activity.stopService(Intent(activity, LocationService::class.java)) }) monitorConfirm = false
    }) { monitorConfirm = false }
    if (fusionNotice) FusionLocationNotice(accept = {
        activity.getSharedPreferences("PlaceActivity", 0).edit().putBoolean("locationPrivacyAccepted", true).apply()
        fusionNotice = false
        activity.act { if (monitoring && fine) AndroidCompat.startService(activity, Intent(activity, LocationService::class.java)) }
    }, systemOnly = { fusionNotice = false }, dismiss = { fusionNotice = false })
    if (trialConfirm) ConfirmDialog(if (settings.optBoolean("trial")) "启用任务拦截？" else "仅记录任务限制？",
        "仅影响此后触发的任务限制。已产生的限制保持原样，每日与每周应用限额独立生效。", {
            if (activity.act { Store.set(settings, "trial", !settings.optBoolean("trial")); activity.db.put("state", settings)
                activity.db.log("settings", "切换惩罚执行模式") }) trialConfirm = false
        }) { trialConfirm = false }
    AppPicker(activity, settings.optJSONArray("defaultApps")?.toString() ?: "[]", appsPicker, { appsPicker = false }) { value ->
        if (activity.act { Store.set(settings, "defaultApps", jsonArray(value)); activity.db.put("state", settings) }) appsPicker = false
    }
    if (numberKey.isNotBlank()) {
        val config = when (numberKey) { "capHours" -> Triple("任务限制每日时长上限", 1, 24)
            "classReminderMinutes" -> Triple("课前提前提醒（分钟）", 0, 120)
            "focusMinutes" -> Triple("默认专注时长（分钟）", 1, 720)
            "shortBreak" -> Triple("短休息时长（分钟）", 1, 120)
            "longBreak" -> Triple("长休息时长（分钟）", 1, 180)
            else -> Triple("每几轮长休息", 1, 12) }
        NumberDialog(activity, settings, numberKey, config.first, config.second, config.third) { numberKey = "" }
    }
    if (reminderChoice.isNotBlank()) AlertDialog(onDismissRequest = { reminderChoice = "" },
        title = { Text(if (reminderChoice == "planReminderMode") "计划提醒" else "课程提醒") },
        text = { Text("有声与静音由系统通知渠道、静音模式和勿扰设置共同控制。") },
        confirmButton = {
            Column {
                listOf("off" to "关闭", "silent" to "静音通知", "sound" to "有声通知").forEach { (mode, label) ->
                    TextButton(onClick = {
                        if (activity.act {
                            Store.set(settings, reminderChoice, mode)
                            activity.db.put("state", settings)
                            Scheduler.schedule(activity)
                        }) reminderChoice = ""
                    }) { Text(label) }
                }
            }
        }, dismissButton = { TextButton(onClick = { reminderChoice = "" }) { Text("返回") } })
    if (backupNotice) ConfirmDialog("恢复会覆盖当前数据", "选择你自己导出的备份文件。恢复后需重新开启地点监测。", {
        backupNotice = false; activity.chooseBackup()
    }) { backupNotice = false }
    if (accessGuide) ConfirmDialog("开启应用限制", "自律使用无障碍服务识别前台应用，并在约定时段显示拦截页。不读取聊天和输入内容。若开关立即关闭，请先在应用详情中允许受限设置。", {
        accessGuide = false; AndroidCompat.openSettings(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }) { accessGuide = false }
    val prepared = activity.preparedRestore
    if (prepared != null) {
        val busy = activity.dataOperation.isNotEmpty()
        AlertDialog(onDismissRequest = { if (!busy) activity.preparedRestore = null },
            title = { Text("恢复完整备份？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将替换全部计划、记录和设置。备份含 ${prepared.optJSONArray("plans")?.length() ?: 0} 个计划、${prepared.optJSONArray("sessions")?.length() ?: 0} 段计时。恢复前会保存当前数据。")
                if (busy) Text(activity.dataOperation, color = MaterialTheme.colorScheme.primary)
            } },
            confirmButton = { TextButton(enabled = !busy,
                onClick = { activity.restorePreparedBackup(prepared) }) { Text("恢复备份") } },
            dismissButton = { TextButton(enabled = !busy,
                onClick = { activity.preparedRestore = null }) { Text("取消") } })
    }
}

@Composable
private fun NumberDialog(activity: ComposeMainActivity, settings: JSONObject, key: String, title: String, min: Int, max: Int, dismiss: () -> Unit) {
    var value by remember(key) { mutableStateOf(settings.optInt(key,
        if (key == "classReminderMinutes") 10 else 0).toString()) }
    var error by remember(key) { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        text = { Column { Input(if (key == "cycles") "轮次" else if (key == "capHours") "小时" else "分钟", value, { value = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton(onClick = {
            val n = value.toIntOrNull()
            if (n == null || n !in min..max) { error = "请输入 $min–$max 之间的整数"; return@TextButton }
            if (activity.act { Store.set(settings, key, n); activity.db.put("state", settings); Scheduler.schedule(activity) }) dismiss()
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss) { Text("返回") } })
}

@Composable
internal fun QuotesScreen(activity: ComposeMainActivity, nav: NavHostController) {
    val settings = remember { activity.db.settings() }
    var quotes by rememberSaveable { mutableStateOf(settings.optString("quotes")) }
    var rotate by rememberSaveable { mutableStateOf(settings.optBoolean("rotate")) }
    Screen("首页寄语", onBack = { nav.popBackStack() }) {
        GlassTaskPanel { Text(quotes.lineSequence().firstOrNull(String::isNotBlank)?.let { "“ $it ”" } ?: "在首页留一句提醒",
            style = MaterialTheme.typography.titleMedium)
            Caption("每行一条；留空即可隐藏。") }
        Input("寄语内容", quotes, { quotes = it }, singleLine = false)
        Row { Checkbox(rotate, { rotate = it }); Text("每天轮换") }
        Primary("保存寄语", Modifier.fillMaxWidth()) {
            if (activity.act { Store.set(settings, "quotes", quotes.trim()); Store.set(settings, "rotate", rotate); activity.db.put("state", settings) })
                nav.popBackStack()
        }
    }
}

@Composable
internal fun RecordScreen(activity: ComposeMainActivity, nav: NavHostController, table: String, title: String) {
    val revision = activity.revision
    val records = remember(revision, table) { activity.db.all(table).asReversed() }
    Scaffold(topBar = { ScreenHeader(title, onBack = { nav.popBackStack() }) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassTaskPanel { Text(title, style = MaterialTheme.typography.titleLarge)
                    Caption(if (records.isEmpty()) "尚未产生记录" else "共 ${records.size} 条记录") }
            }
            if (records.isEmpty()) item { Caption("暂无记录") }
            items(records, key = { it.optString("id") }) { row ->
                Panel {
                    Text(row.optString("text", row.optString("name")))
                    if (table == "penalties") {
                        Caption("${timeText(row.optLong("start"))}—${timeText(row.optLong("end"))}")
                        Caption(if (row.optBoolean("trial")) "仅记录" else "实际拦截")
                        Caption("应用：${appNames(activity, row.optJSONArray("apps"))}")
                    } else Caption(timeText(row.optLong("time")))
                }
            }
        }
    }
}

@Composable
internal fun AboutScreen(activity: ComposeMainActivity, nav: NavHostController) {
    var licenseOpen by remember { mutableStateOf(false) }
    Screen("隐私与使用说明", onBack = { nav.popBackStack() }) {
        GlassTaskPanel { Text("数据留在本机", style = MaterialTheme.typography.titleLarge)
            Caption("计划、地点与计时记录由你管理。") }
        listOf(
            "自律保存计划、地点和计时记录在本机，不上传到自建服务器。",
            "地点监测证明手机位置，不能证明本人正在学习；精度不足、定位中断和模拟定位不计时。",
            "应用限制通过无障碍服务识别前台应用包名，不读取聊天、密码或页面文字。",
            "关闭权限、系统强行停止或卸载后，限制无法保持。",
            "默认按北京时间、周一至周日结算。建议打开系统自动时间。"
        ).forEach { Text(it, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .55f)) }
        Secondary("高德地图开放平台隐私权政策") {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://lbs.amap.com/pages/privacy/")))
        }
        Secondary("开源图标授权") { licenseOpen = true }
    }
    if (licenseOpen) {
        val text = remember {
            listOf("LUCIDE-LICENSE.txt", "MORPHICONS-LICENSE.txt").joinToString("\n\n") { filename ->
                try { "$filename\n\n" + activity.assets.open("licenses/$filename").bufferedReader().use { it.readText() } }
                catch (_: Exception) { "$filename 暂时无法读取" }
            }
        }
        AlertDialog(onDismissRequest = { licenseOpen = false }, title = { Text("开源图标授权") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { Text(text) } },
            confirmButton = { TextButton(onClick = { licenseOpen = false }) { Text("关闭") } })
    }
}
