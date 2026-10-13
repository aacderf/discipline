package com.hl.discipline

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableScreen(activity: ComposeMainActivity, onScrolled: (Boolean) -> Unit = {}) {
    val revision = activity.revision
    val state = remember(revision) { Timetable.state(activity.db) }
    val today = Rules.date(System.currentTimeMillis())
    val currentWeek = Timetable.weekOf(today).coerceIn(1, 18)
    var week by rememberSaveable { mutableIntStateOf(currentWeek) }
    var day by rememberSaveable { mutableIntStateOf(today.dayOfWeek.value) }
    var grid by rememberSaveable { mutableStateOf(false) }
    val agendaScroll = rememberScrollState()
    val gridScroll = rememberScrollState()
    val scrolled by remember(grid, agendaScroll, gridScroll) {
        derivedStateOf { (if (grid) gridScroll else agendaScroll).value > 24 }
    }
    LaunchedEffect(scrolled) { onScrolled(scrolled) }
    val uiPreferences = remember(activity) {
        activity.getSharedPreferences("timetable_ui", Context.MODE_PRIVATE)
    }
    val viewport = rememberSaveable(saver = TimetableViewportState.Saver) {
        TimetableViewportState(uiPreferences.getFloat("grid_zoom", 2.5f))
    }
    LaunchedEffect(viewport.zoom) {
        delay(350)
        uiPreferences.edit().putFloat("grid_zoom", viewport.zoom).apply()
    }
    var management by rememberSaveable { mutableStateOf(false) }
    var importHelp by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = Saver<JSONObject?, String>(
        save = { it?.toString() }, restore = { JSONObject(it) })) { mutableStateOf<JSONObject?>(null) }
    var csvPreview by remember { mutableStateOf<List<JSONObject>?>(null) }
    var pendingEditor by remember { mutableStateOf<JSONObject?>(null) }
    var pendingHelp by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) activity.runDataOperation("正在读取课表", {
            Timetable.parseCsv(DocumentTransfers.read(activity, uri, 1_000_000, "课表 CSV 超过 1MB"))
        }) { csvPreview = it }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(if (grid) gridScroll else agendaScroll),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        ModeSwitch(if (grid) "周网格" else "日程", if (grid) "日程" else "周网格",
            MaterialTheme.typography.titleMedium, Modifier.testTag("timetable-view-switch")) { grid = !grid }
        GlassIconButton("课表管理", Modifier.testTag("timetable-management"), onClick = { management = true }) {
            Text("⋯", fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics { })
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("第 $week 周", style = MaterialTheme.typography.titleMedium)
            Caption("${Timetable.date(week, 1).format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}—" +
                Timetable.date(week, 7).format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)))
        }
        Row {
            TextButton(onClick = { week = (week - 1).coerceAtLeast(1) },
                enabled = week > 1, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("‹") }
            TextButton(onClick = { week = (week + 1).coerceAtMost(18) },
                enabled = week < 18, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("›") }
        }
    }
    if (!state.optBoolean("confirmed")) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .quietClickable("校对课表", onClick = { management = true }),
        verticalAlignment = Alignment.CenterVertically) {
        Caption("课表草稿 · ${Timetable.rows(state).count { it.optBoolean("review") }} 条待校对")
    }
    ViewTransitionLayout {
    AnimatedContent(grid, transitionSpec = {
        (fadeIn(tween(if (activity.motionEnabled) AppMotion.state else 0, easing = AppMotion.easing)) togetherWith
            fadeOut(tween(if (activity.motionEnabled) AppMotion.sharedContentExit else 0, easing = AppMotion.easing)))
            .using(androidx.compose.animation.SizeTransform(clip = false,
                sizeAnimationSpec = { _, _ -> tween(if (activity.motionEnabled) AppMotion.courseBounds else 0,
                    easing = AppMotion.easing) }))
    }, label = "课表布局", modifier = Modifier.fillMaxWidth()) { shownGrid ->
    CompositionLocalProvider(LocalViewMotion provides ViewMotionScope(this@ViewTransitionLayout,
        this@AnimatedContent, AppMotion.courseBounds, "course-view")) {
    ScreenExitGuard(shownGrid != grid, Modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!shownGrid) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { number ->
                val date = Timetable.date(week, number)
                FilterChip(selected = day == number, onClick = { day = number },
                    label = { Text("${"一二三四五六日"[number - 1]} ${date.dayOfMonth}") },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        AnimatedContent(targetState = "$week-$day", transitionSpec = {
            fadeIn(tween(if (activity.motionEnabled) AppMotion.stateChange else 0)) togetherWith
                fadeOut(tween(if (activity.motionEnabled) AppMotion.outgoing else 0, easing = AppMotion.easing))
        }, label = "课表日期") { target ->
            val parts = target.split('-')
            val shown = Timetable.forDay(state, parts[0].toInt(), parts[1].toInt())
            ScreenExitGuard(target != "$week-$day", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (shown.isEmpty()) Panel {
                    Text("当天暂无课程", style = MaterialTheme.typography.titleMedium)
                    Caption("选择其他日期，或从课表管理添加课程。")
                }
                shown.forEach { row -> key(row.optString("id")) {
                    CourseCard(row, Modifier.viewShared("course-${parts[0]}-${parts[1]}-${row.optString("id")}")) { editing = row }
                } }
            }
            }
        }
    } else {
        TimetableWeekGrid(state, week, day, viewport,
            onFocusedDay = { day = it }, onDaySelected = { day = it; grid = false },
            onCourseSelected = { editing = it })
    }
    }
    }
    }
    }
    }
    AppModalSheet(management, onDismiss = { management = false }, onHidden = {
        if (pendingEditor != null) { editing = pendingEditor; pendingEditor = null }
        if (pendingHelp) { importHelp = true; pendingHelp = false }
    }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("课表管理", style = MaterialTheme.typography.titleLarge)
        Caption("第 $week 周 · ${Timetable.rows(state).size} 条课程")
        GlassSecondaryButton("添加课程", Modifier.fillMaxWidth()) {
            management = false
            pendingEditor = Store.obj("id", Store.id(), "title", "", "room", "", "day", day,
                "from", 1, "until", 2, "weeks", JSONArray(listOf(week)), "source", "manual")
        }
        GlassSecondaryButton("导入 CSV", Modifier.fillMaxWidth()) {
            management = false
            picker.launch(arrayOf("text/*", "text/csv", "application/csv"))
        }
        TextButton(onClick = { management = false; pendingHelp = true },
            modifier = Modifier.heightIn(min = 48.dp)) { Text("导入格式与学期信息") }
        if (!state.optBoolean("confirmed")) {
            Caption("${Timetable.rows(state).count { it.optBoolean("review") }} 条待校对；确认后开启课程提醒。")
            Primary("确认课表并启用提醒", Modifier.fillMaxWidth()) {
                if (activity.act {
                    Timetable.confirm(activity.db)
                    Scheduler.schedule(activity)
                }) management = false
            }
        }
        }
    }
    if (importHelp) AlertDialog(onDismissRequest = { importHelp = false }, title = { Text("课表导入说明") },
        text = { Text("本学期第 1 周从 2026 年 8 月 31 日开始，共 18 周。\n\nUTF-8 CSV 列：title,room,day,from,until,weeks。day 为周一至周日的 1–7，from/until 为起止节次。周次可用 1;3;5、1-18、1-18/odd 或 1-18/even。相同日期、节次和周次的冲突课程会跳过，同批导入保留先出现的课程。") },
        confirmButton = { TextButton(onClick = { importHelp = false }) { Text("知道了") } })
    AppValueSheet(editing, onDismiss = { editing = null }) { original ->
        key(original.optString("id")) {
            CourseEditor(activity, state, original, week, onDismiss = { editing = null })
        }
    }
    csvPreview?.let { pending ->
        val existing = Timetable.rows(state)
        val accepted = Timetable.importable(existing, pending)
        val fits = existing.size + accepted.size <= 500
        val conflicts = pending.filterNot { it in accepted }
        AlertDialog(onDismissRequest = { csvPreview = null }, title = { Text("导入课表预览") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("共 ${pending.size} 条；可导入 ${accepted.size} 条，冲突 ${conflicts.size} 条。")
                    if (!fits) Text("课表最多保存 500 条课程，请先整理旧课程。", color = MaterialTheme.colorScheme.error)
                    accepted.take(8).forEach { row ->
                        Caption("导入 · 周${"一二三四五六日"[row.optInt("day") - 1]} " +
                            "${row.optInt("from")}–${row.optInt("until")}节 · ${row.optString("title")}")
                    }
                    conflicts.take(8).forEach { row ->
                        Text("跳过冲突 · 周${"一二三四五六日"[row.optInt("day") - 1]} " +
                            "${row.optInt("from")}–${row.optInt("until")}节 · ${row.optString("title")}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (pending.size > 8) Caption("其余条目会按同样的时间冲突规则处理。")
                }
            },
            confirmButton = { TextButton(enabled = fits && accepted.isNotEmpty(), onClick = {
                var imported = 0
                if (activity.act {
                    imported = Timetable.importCourses(activity.db, pending)
                    Scheduler.schedule(activity)
                }) {
                    csvPreview = null
                    activity.toast("已导入 $imported 条课程" +
                        if (imported < pending.size) "，${pending.size - imported} 条冲突未导入" else "")
                }
            }) { Text("导入无冲突课程") } },
            dismissButton = { TextButton(onClick = { csvPreview = null }) { Text("返回") } })
    }
    Spacer(Modifier.height(16.dp))
    }
}

internal fun courseDescription(row: JSONObject): String {
    val (start, end) = Timetable.times(row)
    return "${row.optString("title")}，第 ${row.optInt("from")} 至 ${row.optInt("until")} 节，" +
        "${if (start.isNotEmpty() && end.isNotEmpty()) "$start 到 $end" else "钟点待补"}，${row.optString("room")}" +
        if (row.optBoolean("review")) "，待校对" else ""
}

@Composable
private fun CourseCard(row: JSONObject, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .pressableCard(shape = RoundedCornerShape(12.dp), onClickLabel = "编辑${row.optString("title")}", onClick = onClick)
        .semantics { contentDescription = courseDescription(row) }, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val (start, end) = Timetable.times(row)
            Text(if (start.isEmpty() || end.isEmpty()) "${row.optInt("from")}–${row.optInt("until")}节"
                else "$start\n$end", style = AppTypography.number)
            Column(Modifier.weight(1f)) {
                Text(row.optString("title"), style = MaterialTheme.typography.titleMedium)
                Caption("${row.optInt("from")}–${row.optInt("until")} 节 · ${row.optString("room")}")
                if (row.optBoolean("review")) Caption("待校对 · 暂不提醒")
            }
        }
    }
}

@Composable
private fun CourseEditor(activity: ComposeMainActivity, state: JSONObject, original: JSONObject,
                         week: Int, onDismiss: () -> Unit) {
    val id = original.optString("id")
    var title by rememberSaveable(id) { mutableStateOf(original.optString("title")) }
    var room by rememberSaveable(id) { mutableStateOf(original.optString("room")) }
    var day by rememberSaveable(id) { mutableStateOf(original.optInt("day").toString()) }
    var from by rememberSaveable(id) { mutableStateOf(original.optInt("from").toString()) }
    var until by rememberSaveable(id) { mutableStateOf(original.optInt("until").toString()) }
    var weeks by rememberSaveable(id) { mutableStateOf((1..18).filter { Timetable.inWeek(original, it) }.joinToString(";")) }
    val existing = remember(id) { Timetable.rows(state).any { it.optString("id") == id } }
    var error by remember(id) { mutableStateOf("") }
    fun save(onlyWeek: Boolean) {
        val d = day.toIntOrNull() ?: 0; val first = from.toIntOrNull() ?: 0
        val last = until.toIntOrNull() ?: 0
        val numbers = try { TimetableCsv.weeks(weeks) } catch (_: IllegalArgumentException) {
            error = "周次可用 1;3;5、1-18、1-18/odd 或 1-18/even"; return
        }
        if (title.isBlank() || d !in 1..7 || first !in 1..12 || last !in first..12 ||
            numbers.isEmpty() || numbers.any { it !in 1..18 }) {
            error = "请填写名称、星期、1–12 节及 1–18 周"; return
        }
        val row = Store.obj("id", id, "title", title.trim(), "room", room.trim(),
            "day", d, "from", first, "until", last, "weeks", JSONArray(numbers),
            "source", "manual", "review", false)
        if (first == original.optInt("from") && last == original.optInt("until")) {
            listOf("startTime", "endTime").forEach { key ->
                if (original.has(key)) Store.set(row, key, original.optString(key))
            }
        }
        Store.set(row, "review", Timetable.times(row).let { it.first.isBlank() || it.second.isBlank() })
        if (activity.act {
            Timetable.save(activity.db, row, if (existing && onlyWeek) week else null, requireExisting = existing)
            Scheduler.schedule(activity)
        }) onDismiss()
    }
    val maxHeight = editorSheetHeight()
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
        .padding(horizontal = AppSpacing.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (existing) "编辑课程" else "添加课程", Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Input("课程名", title, { title = it; error = "" })
                Input("地点", room, { room = it })
                Input("星期：1–7", day, { day = it }, keyboard = KeyboardType.Number)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Input("起始节", from, { from = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                    Input("结束节", until, { until = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                }
                Input("周次：1-18 或 1;3;5", weeks, { weeks = it; error = "" })
                Caption("单双周可写 1-18/odd 或 1-18/even；保存这周只影响第 $week 周。")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (existing) TextButton(onClick = {
                    if (activity.act {
                        Timetable.remove(activity.db, id, week)
                        Scheduler.schedule(activity)
                    }) onDismiss()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("删除这周")
                }
        }
        Primary(if (existing) "保存这周" else "保存课程", Modifier.fillMaxWidth()) { save(existing) }
        if (existing) {
            Secondary("保存整个系列", Modifier.fillMaxWidth()) { save(false) }
        }
    }
}
