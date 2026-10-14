package com.hl.discipline

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun Input(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier,
                   error: String = "", singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onValue, modifier = modifier.fillMaxWidth(),
        label = { Text(label) }, isError = error.isNotEmpty(), supportingText = if (error.isNotEmpty()) {{ Text(error) }} else null,
        singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

/** Keep Material's native touch/keyboard/semantics behavior, with explicit theme contrast. */
@Composable
internal fun GlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
                        modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = AppColors.current
    val colors = MaterialTheme.colorScheme
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.navigationInk,
            checkedTrackColor = palette.navigationFill,
            checkedBorderColor = palette.navigationFill,
            uncheckedThumbColor = colors.primary,
            uncheckedTrackColor = colors.surfaceVariant,
            uncheckedBorderColor = colors.onSurfaceVariant,
            disabledCheckedThumbColor = palette.disabledText,
            disabledCheckedTrackColor = palette.secondaryDisabled,
            disabledCheckedBorderColor = palette.disabledText,
            disabledUncheckedThumbColor = palette.disabledText,
            disabledUncheckedTrackColor = palette.secondaryDisabled,
            disabledUncheckedBorderColor = palette.disabledText))
}

@Composable
internal fun GlassCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
                          modifier: Modifier = Modifier, enabled: Boolean = true) {
    val palette = AppColors.current
    val colors = MaterialTheme.colorScheme
    Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        colors = CheckboxDefaults.colors(checkedColor = colors.primary,
            uncheckedColor = colors.primary, checkmarkColor = colors.onPrimary,
            disabledCheckedColor = palette.disabledText,
            disabledUncheckedColor = palette.disabledText))
}

@Composable
private fun ClockInput(label: String, value: String, onValue: (String) -> Unit, error: String = "") {
    OutlinedTextField(value, { raw ->
        val digits = raw.filter(Char::isDigit).take(4)
        onValue(if (digits.length > 2) digits.take(2) + ":" + digits.drop(2) else digits)
    }, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        isError = error.isNotEmpty(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text(error.ifEmpty { "HH:mm" }) })
}

@Composable
internal fun SelectField(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Caption(label)
        Secondary(options.getOrElse(selected) { options.firstOrNull() ?: "请选择" }, Modifier.fillMaxWidth()) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { index, text ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(index); open = false })
            }
        }
    }
}

internal fun parseMinute(input: String, end: Boolean): Int {
    val fields = input.trim().split(":")
    require(fields.size == 2) { "时间格式为 HH:mm" }
    val hour = fields[0].toIntOrNull() ?: throw IllegalArgumentException("时间格式为 HH:mm")
    val minute = fields[1].toIntOrNull() ?: throw IllegalArgumentException("时间格式为 HH:mm")
    require(hour in 0..(if (end) 24 else 23) && minute in 0..59 && (hour != 24 || minute == 0)) { "时间不正确" }
    return hour * 60 + minute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlinePicker(stage: Int, initial: Long, selectedDay: Long, title: String = "截止",
                           onDate: (Long) -> Unit, onTime: (Long) -> Unit, onDismiss: () -> Unit) {
    if (stage == 1) {
        val date = Instant.ofEpochMilli(initial).atZone(Rules.ZONE).toLocalDate()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let(onDate) }) { Text("下一步") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) {
            DatePicker(state, title = { Text("选择${title}日期", Modifier.padding(16.dp)) })
        }
    } else if (stage == 2) {
        val previous = Instant.ofEpochMilli(initial).atZone(Rules.ZONE)
        val state = rememberTimePickerState(initialHour = previous.hour, initialMinute = previous.minute, is24Hour = true)
        AlertDialog(onDismissRequest = onDismiss, title = { Text("选择${title}时间") },
            text = { TimeInput(state = state) },
            confirmButton = { TextButton(onClick = {
                val date = Instant.ofEpochMilli(selectedDay).atZone(ZoneOffset.UTC).toLocalDate()
                onTime(date.atTime(state.hour, state.minute).atZone(Rules.ZONE).toInstant().toEpochMilli())
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    }
}

internal fun jsonArray(value: String): JSONArray = try { JSONArray(value) } catch (_: Exception) { JSONArray() }
internal fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }

/** Collapse fields in place; only clear focus when this group owns the active input. */
@Composable
internal fun FormDisclosure(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotionEnabled.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (!visible && hasFocus) { focus.clearFocus(); keyboard?.hide() }
    }
    AnimatedVisibility(visible,
        enter = expandVertically(tween(if (motion) AppMotion.fieldExpand else 0, easing = AppMotion.easing)) +
            fadeIn(tween(if (motion) AppMotion.contentEnter else 0, easing = AppMotion.easing)),
        exit = shrinkVertically(tween(if (motion) AppMotion.collapse else 0, easing = AppMotion.easing)) +
            fadeOut(tween(if (motion) AppMotion.contentExit else 0, easing = AppMotion.easing))) {
        ScreenExitGuard(!visible, Modifier.fillMaxWidth().onFocusChanged { hasFocus = it.hasFocus }.focusGroup()) {
            Column(content = content)
        }
    }
}

@Composable
internal fun PlanFormScreen(activity: ComposeMainActivity, nav: NavHostController, id: String) {
    val revision = activity.revision
    val existing = remember(id, revision) { if (id == "new") null else activity.db.one("plans", id) }
    if (existing != null && existing.optString("cycle") == "once") {
        LaunchedEffect(id) { activity.toast("单次目标已生效；请为当前任务请假并新建目标"); nav.popBackStack() }
        return
    }
    val defaults = remember(id) { activity.db.settings() }
    var name by rememberSaveable(id) { mutableStateOf(existing?.optString("name") ?: "") }
    val metricCodes = listOf("check", "count", "focus", "presence", "placeFocus")
    var metric by rememberSaveable(id) { mutableStateOf(existing?.optString("metric") ?: "check") }
    var target by rememberSaveable(id) { mutableStateOf(numberText(existing?.optDouble("target", 1.0) ?: 1.0)) }
    var unit by rememberSaveable(id) { mutableStateOf(existing?.optString("unit", "次") ?: "次") }
    var placeId by rememberSaveable(id) { mutableStateOf(existing?.optString("place") ?: "") }
    val createdPlace = activity.createdPlace
    LaunchedEffect(createdPlace) { if (createdPlace.isNotEmpty()) { placeId = createdPlace; activity.createdPlace = "" } }
    var cycle by rememberSaveable(id) { mutableStateOf(existing?.optString("cycle") ?: "once") }
    val defaultDeadline = remember(id) { Rules.at(Rules.date(System.currentTimeMillis()), 1439) }
    var deadline by rememberSaveable(id) { mutableLongStateOf(existing?.optLong("deadline", defaultDeadline) ?: defaultDeadline) }
    var plannedStartAt by rememberSaveable(id) { mutableLongStateOf(existing?.optLong("plannedStartAt") ?: 0L) }
    var reminderMode by rememberSaveable(id) { mutableStateOf(existing?.optString("reminderMode", defaults.optString("planReminderMode", "sound")) ?: "sound") }
    var startLead by rememberSaveable(id) { mutableStateOf((existing?.optInt("startReminderMinutes", 15) ?: 15).toString()) }
    var dueLead by rememberSaveable(id) { mutableStateOf((existing?.optInt("dueReminderMinutes", 30) ?: 30).toString()) }
    var deadlineStage by rememberSaveable(id) { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable(id) { mutableLongStateOf(0L) }
    var days by rememberSaveable(id) { mutableStateOf(existing?.optJSONArray("days")?.toString() ?: "[1,2,3,4,5,6,7]") }
    var from by rememberSaveable(id) { mutableStateOf(clockText(existing?.optInt("from") ?: 0)) }
    var until by rememberSaveable(id) { mutableStateOf(clockText(existing?.optInt("until", 1440) ?: 1440)) }
    var penalty by rememberSaveable(id) { mutableStateOf((existing?.optInt("penalty", 120) ?: 120).toString()) }
    var penaltyMode by rememberSaveable(id) { mutableStateOf(existing?.optString("penaltyMode", "fixed") ?: "fixed") }
    var penaltyAt by rememberSaveable(id) { mutableStateOf(existing?.optInt("penaltyAt", -1)?.takeIf { it >= 0 }?.let(::clockText) ?: "") }
    var apps by rememberSaveable(id) { mutableStateOf(existing?.optJSONArray("apps")?.toString() ?: defaults.optJSONArray("defaultApps")?.toString() ?: "[]") }
    var parent by rememberSaveable(id) { mutableStateOf(existing?.optString("parent") ?: "") }
    var why by rememberSaveable(id) { mutableStateOf(existing?.optString("why") ?: "") }
    var scheduleOpen by rememberSaveable(id) { mutableStateOf(cycle != "once" || from != "00:00" || until != "24:00") }
    var penaltyOpen by rememberSaveable(id) { mutableStateOf(false) }
    var otherOpen by rememberSaveable(id) { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var errorField by remember { mutableStateOf("") }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val places = remember(revision) { activity.db.all("places").filter { !it.optBoolean("archived") } }
    val trial = remember(revision) { activity.db.settings().optBoolean("trial", true) }
    val parents = remember(revision) { activity.db.all("plans").filter {
        !it.optBoolean("archived") && it.optString("id") != id && activity.engine.canParent(id.takeUnless { key -> key == "new" } ?: "", it.optString("id"))
    } }

    fun validate(): JSONObject? {
        error = ""; errorField = ""
        fun invalid(field: String, message: String): Nothing {
            errorField = field
            throw IllegalArgumentException(message)
        }
        try {
            if (name.trim().isEmpty()) invalid("name", "请填写计划名称")
            val n = target.toDoubleOrNull() ?: invalid("target", "请输入目标数量")
            if (!n.isFinite() || n <= 0 || n > 1_000_000) invalid("target", "目标必须大于 0 且不超过 1000000")
            if (metric in listOf("presence", "placeFocus") && placeId.isBlank()) invalid("place", "请先选择地点")
            if (metric in listOf("presence", "placeFocus") && places.none { it.optString("id") == placeId })
                invalid("place", "地点已移除，请重新选择")
            if (cycle == "once" && deadline <= System.currentTimeMillis()) invalid("deadline", "截止时间必须在未来")
            if (plannedStartAt > 0 && cycle == "once" && plannedStartAt >= deadline)
                invalid("plannedStartAt", "执行开始时间必须早于截止时间")
            val startMinutes = startLead.toIntOrNull() ?: invalid("startLead", "开始提醒须为 0–1440 分钟")
            val dueMinutes = dueLead.toIntOrNull() ?: invalid("dueLead", "截止提醒须为 0–1440 分钟")
            if (startMinutes !in 0..1440 || dueMinutes !in 0..1440)
                invalid("dueLead", "提醒须为 0–1440 分钟")
            if (jsonArray(days).length() == 0) invalid("days", "至少选择一天")
            val beginning = try { parseMinute(from, false) } catch (_: Exception) { invalid("from", "开始时间格式为 HH:mm") }
            val end = try { parseMinute(until, true) } catch (_: Exception) { invalid("until", "结束时间格式为 HH:mm") }
            if (end <= beginning) invalid("until", "结束时间应晚于开始时间；跨午夜请拆为两个时段")
            val p = penalty.toIntOrNull() ?: invalid("penalty", "请输入限制分钟数")
            if (p !in 0..1440) invalid("penalty", "限制时长应为 0—1440 分钟")
            if (metric == "presence" && penaltyMode == "remainingDwell" && jsonArray(apps).length() == 0)
                invalid("penalty", "请选择要限制的应用")
            val penaltyTime = if (penaltyAt.isBlank()) -1 else try { parseMinute(penaltyAt, false) }
                catch (_: Exception) { invalid("penaltyAt", "限制开始时刻格式为 HH:mm") }
            return (existing?.let(Store::copy) ?: JSONObject()).also { form ->
                Store.set(form, "name", name.trim()); Store.set(form, "metric", metric)
                Store.set(form, "target", if (metric == "check") 1 else n)
                Store.set(form, "unit", unit.trim()); Store.set(form, "place", if (metric in listOf("presence", "placeFocus")) placeId else "")
                Store.set(form, "cycle", cycle); Store.set(form, "deadline", deadline)
                Store.set(form, "plannedStartAt", if (cycle == "once") plannedStartAt else 0L)
                Store.set(form, "reminderMode", reminderMode)
                Store.set(form, "startReminderMinutes", startMinutes)
                Store.set(form, "dueReminderMinutes", dueMinutes)
                Store.set(form, "days", jsonArray(days)); Store.set(form, "from", beginning); Store.set(form, "until", end)
                Store.set(form, "apps", jsonArray(apps)); Store.set(form, "penalty", p)
                Store.set(form, "penaltyMode", if (metric == "presence") penaltyMode else "fixed")
                Store.set(form, "penaltyAt", if (metric == "presence" && penaltyMode == "remainingDwell") -1 else penaltyTime)
                Store.set(form, "parent", parent); Store.set(form, "why", why.trim())
            }
        } catch (e: Exception) {
            error = e.message ?: "请检查输入"
            if (errorField in listOf("from", "until", "days")) scheduleOpen = true
            if (errorField in listOf("penalty", "penaltyAt")) penaltyOpen = true
            return null
        }
    }

    Screen(if (existing == null) "创建计划" else "编辑计划", onBack = { nav.popBackStack() },
        bottom = {
            if (!imeVisible) GlassTaskPanel(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Caption("${name.ifBlank { "新计划" }} · ${if (metric == "check") "完成一次" else "$target${if (metric == "count") unit else " 分钟"}"}")
                if (error.isNotBlank()) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                Primary("预览并保存", Modifier.fillMaxWidth()) { if (validate() != null) confirm = true }
            }
        }) {
        Section("计划内容")
        Input("计划名称", name, { name = it; error = "" }, error = if (errorField == "name") error else "")
        Section("完成条件")
        if (cycle == "once" && metric in listOf("presence", "placeFocus"))
            Caption("从创建计划到截止时间累计；每天只计入有效时段内的记录。")
        SelectField("完成方式", listOf("勾选完成", "累计数量", "专注分钟", "地点停留分钟", "地点内专注分钟"),
            metricCodes.indexOf(metric).coerceAtLeast(0)) { index ->
            metric = metricCodes[index]
            if (index > 1 && target == "1") target = if (index == 2) "25" else "120"
        }
        if (metric != "check") Input(if (metric == "count") "目标数量" else "目标分钟", target,
            { target = it; error = "" }, error = if (errorField == "target") error else "",
            keyboard = KeyboardType.Decimal)
        if (metric == "count") Input("数量单位", unit, { unit = it })
        if (metric == "presence" || metric == "placeFocus") {
            SelectField("关联地点", listOf("选择地点") + places.map { it.optString("name") },
                (places.indexOfFirst { it.optString("id") == placeId } + 1).coerceAtLeast(0)) {
                placeId = if (it == 0) "" else places[it - 1].optString("id")
            }
            if (errorField == "place" && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Secondary("添加地图地点") { activity.openMap() }
        }
        Section("执行时间")
        if (cycle == "once") Secondary("截止：${timeText(deadline)}", Modifier.fillMaxWidth()) {
            deadlineStage = 1
        }
        if (cycle == "once") {
            Secondary(if (plannedStartAt > 0) "执行开始：${timeText(plannedStartAt)}" else "设置执行开始时间（可选）",
                Modifier.fillMaxWidth()) { deadlineStage = 3 }
            if (plannedStartAt > 0) TextButton(onClick = { plannedStartAt = 0L }) { Text("清除开始时间") }
        }
        if (errorField == "deadline" && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (errorField == "plannedStartAt" && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        SelectField("提醒方式", listOf("关闭", "静音通知", "有声通知"),
            listOf("off", "silent", "sound").indexOf(reminderMode).coerceAtLeast(0)) {
            reminderMode = listOf("off", "silent", "sound")[it]
        }
        if (reminderMode != "off") {
            if (cycle == "once" && plannedStartAt > 0 || cycle == "daily" && from != "00:00")
                Input("开始前提醒（分钟）", startLead, { startLead = it }, keyboard = KeyboardType.Number)
            Input("截止前提醒（分钟）", dueLead, { dueLead = it }, keyboard = KeyboardType.Number)
        }
        Caption("${cycleText(cycle)} · $from—$until")
        Secondary(if (scheduleOpen) "收起重复与有效时段" else "重复与有效时段") { scheduleOpen = !scheduleOpen }
        FormDisclosure(scheduleOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectField("重复方式", listOf("单次目标／指定日期", "每周指定日，分别达标", "每周累计达标"),
                listOf("once", "daily", "weekly").indexOf(cycle).coerceAtLeast(0)) {
                cycle = listOf("once", "daily", "weekly")[it]
            }
            if (cycle != "once") {
                Text("哪些天计入计划")
                val selected = jsonArray(days).toStringList().toSet()
                listOf((1..4).toList(), (5..7).toList()).forEach { rowDays ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowDays.forEach { day ->
                            FilterChip(selected = day.toString() in selected, onClick = {
                                val next = JSONArray()
                                (1..7).filter { d -> if (d == day) d.toString() !in selected else d.toString() in selected }.forEach(next::put)
                                days = next.toString(); error = ""
                            }, label = { Text("周" + "一二三四五六日"[day - 1]) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (errorField == "days" && error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            ClockInput("每日开始时间", from, { from = it; error = "" }, if (errorField == "from") error else "")
            ClockInput("每日结束时间", until, { until = it; error = "" }, if (errorField == "until") error else "")
        }
        }
        Section("未完成后的限制")
        Caption("${if (metric == "presence" && penaltyMode == "remainingDwell") "按剩余停留时间限制" else "未完成限制 $penalty 分钟"} · ${appNames(activity, jsonArray(apps))}")
        Secondary(if (penaltyOpen) "收起限制设置" else "调整限制") { penaltyOpen = !penaltyOpen }
        FormDisclosure(penaltyOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (metric == "presence") SelectField("限制时长来源", listOf("固定时长", "剩余停留时间"),
                if (penaltyMode == "remainingDwell") 1 else 0) { penaltyMode = if (it == 1) "remainingDwell" else "fixed" }
            if (metric == "presence" && penaltyMode == "remainingDwell")
                Caption("截止时还差多少分钟，就限制所选应用多久；沿用每日限制上限和核验规则。")
            else {
            Input("限制时长（分钟，0 为不限制）", penalty, { penalty = it; error = "" },
                error = if (errorField == "penalty") error else "", keyboard = KeyboardType.Number)
            ClockInput("限制开始时刻（可留空）", penaltyAt, { penaltyAt = it; error = "" },
                if (errorField == "penaltyAt") error else "")
            }
            Secondary("选择限制应用") { pickerOpen = true }
        }
        }
        Section("更多选项")
        Secondary(if (otherOpen) "收起其他选项" else "上级目标与完成理由") { otherOpen = !otherOpen }
        FormDisclosure(otherOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectField("上级目标", listOf("无上级目标") + parents.map { it.optString("name") },
                (parents.indexOfFirst { it.optString("id") == parent } + 1).coerceAtLeast(0)) {
                parent = if (it == 0) "" else parents[it - 1].optString("id")
            }
            Input("计划备注", why, { why = it })
        }
        }
        Spacer(Modifier.height(20.dp))
    }
    AppPicker(activity, apps, pickerOpen, { pickerOpen = false }) { apps = it; pickerOpen = false }
    DeadlinePicker(if (deadlineStage > 2) deadlineStage - 2 else deadlineStage,
        if (deadlineStage > 2) plannedStartAt.takeIf { it > 0 } ?: System.currentTimeMillis() else deadline,
        selectedDay, if (deadlineStage > 2) "开始" else "截止",
        onDate = { selectedDay = it; deadlineStage = if (deadlineStage > 2) 4 else 2 },
        onTime = { if (deadlineStage > 2) plannedStartAt = it else deadline = it; deadlineStage = 0 },
        onDismiss = { deadlineStage = 0 })
    if (confirm) {
        val selectedApps = jsonArray(apps)
        val penaltySummary = when {
            metric == "presence" && penaltyMode == "remainingDwell" && selectedApps.length() > 0 -> "截止时按剩余停留时间限制"
            penalty.toIntOrNull() == 0 -> "仅记录"
            selectedApps.length() == 0 -> "未选限制应用，不会拦截"
            else -> "限制 $penalty 分钟"
        }
        val preview = "$name\n目标：${if (metric == "check") "1 项" else "$target${if (metric == "count") unit else " 分钟"}"}\n${cycleText(cycle)}" +
            (if (cycle == "once") "，截止 ${timeText(deadline)}" else "") +
            "\n未完成：$penaltySummary" +
            (if (selectedApps.length() > 0) "\n应用：${appNames(activity, selectedApps)}" else "") +
            "\n任务限制：${if (trial) "仅记录" else "实际拦截"}"
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认计划") }, text = { Text(preview) },
            confirmButton = { TextButton(onClick = {
                val form = validate() ?: return@TextButton
                val saved = activity.act {
                    val now = System.currentTimeMillis()
                    activity.db.atomic {
                        if (!form.has("id")) { Store.set(form, "id", Store.id()); Store.set(form, "created", now) }
                        else activity.db.all("instances").filter {
                            it.optString("planId") == form.optString("id") && it.optLong("start") > now
                        }.forEach { activity.db.remove("instances", it.optString("id")) }
                        val generated = if (existing != null) Rules.at(
                            if (cycle == "weekly") Rules.monday(Rules.date(now)).plusWeeks(1) else Rules.date(now).plusDays(1), 0
                        ) else now
                        Store.set(form, "generated", generated)
                        activity.db.put("plans", form)
                    }
                    activity.engine.tick()
                }
                if (saved) {
                    confirm = false
                    activity.toast("计划已保存")
                    nav.navigate("plans") { popUpTo("main") { inclusive = false }; launchSingleTop = true }
                }
            }) { Text("保存计划") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回修改") } })
    }
}

/** Existing single-use plans can change this rule without rewriting their goal or schedule. */
@Composable
internal fun DwellPenaltyDialog(activity: ComposeMainActivity, task: JSONObject, onDismiss: () -> Unit) {
    var remaining by remember { mutableStateOf(task.optString("penaltyMode", "fixed") == "remainingDwell") }
    var fixed by remember { mutableStateOf(task.optInt("penalty", 120).toString()) }
    var apps by remember { mutableStateOf(task.optJSONArray("apps")?.toString() ?: "[]") }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AppPicker(activity, apps, picking, { picking = false }) { apps = it; picking = false }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("未完成后的限制") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SelectField("限制时长来源", listOf("固定时长", "剩余停留时间"), if (remaining) 1 else 0) { remaining = it == 1 }
            if (remaining) Caption("截止时还差多少分钟，就限制所选应用多久。长任务按已记录进度结算，定位缺口不算完成；每日上限仍有效。")
            else Input("固定限制分钟，0 为不限制", fixed, { fixed = it }, keyboard = KeyboardType.Number)
            Secondary("选择限制应用") { picking = true }
            Caption(appNames(activity, jsonArray(apps)))
            Caption("应用于本周期与后续未结算周期。已结算的记录保持不变。")
            if (activity.db.settings().optBoolean("trial", true)) Caption("当前为仅记录模式，不实际拦截；可在专注页调整执行模式。")
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            val minutes = fixed.toIntOrNull()
            if (!remaining && (minutes == null || minutes !in 0..1440)) { error = "固定时长须为 0–1440 分钟"; return@TextButton }
            if (remaining && jsonArray(apps).length() == 0) { error = "请选择要限制的应用"; return@TextButton }
            if (activity.act {
                activity.db.atomic {
                    val planId = task.optString("planId")
                    val current = activity.db.one("instances", task.optString("id"))
                    check(current?.optString("status") in listOf("open", "review")) { "本周期已结算，无法修改限制" }
                    val rows = activity.db.all("instances").filter {
                        it.optString("planId") == planId && it.optLong("due") >= task.optLong("due") &&
                            it.optString("status") in listOf("open", "review")
                    }
                    (rows + listOfNotNull(activity.db.one("plans", planId))).forEach { record ->
                        Store.set(record, "penaltyMode", if (remaining) "remainingDwell" else "fixed")
                        Store.set(record, "penalty", minutes ?: 120)
                        Store.set(record, "apps", jsonArray(apps))
                        if (remaining) Store.set(record, "penaltyAt", -1)
                        activity.db.put(if (record.has("planId")) "instances" else "plans", record)
                    }
                }
                activity.engine.tick()
            }) { onDismiss() }
        }) { Text("保存限制") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
