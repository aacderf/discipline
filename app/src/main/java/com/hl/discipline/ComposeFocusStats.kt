package com.hl.discipline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavHostController
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

private data class FocusPresentation(
    val hourglass: HourglassState,
    val status: String,
    val action: String,
    val icon: Int
)

private fun focusPresentation(focus: JSONObject?, completed: Boolean): FocusPresentation {
    if (focus == null) return FocusPresentation(HourglassState.Idle, "准备开始", "开始专注", R.drawable.ic_play)
    return when (focus.optString("phase")) {
        "work" -> FocusPresentation(HourglassState.Running, "专注中", "暂停专注", R.drawable.ic_pause)
        "paused" -> FocusPresentation(HourglassState.Paused, "已暂停",
            if (focus.optString("pausedPhase") == "break") "继续休息" else "继续专注", R.drawable.ic_play)
        "break" -> FocusPresentation(if (completed) HourglassState.Completed else HourglassState.Running,
            if (completed) "本轮完成" else "休息中", "跳过休息", R.drawable.ic_play)
        else -> FocusPresentation(HourglassState.Idle, "准备下一轮", "开始下一轮", R.drawable.ic_play)
    }
}

@Composable
internal fun FocusScreen(activity: ComposeMainActivity, nav: NavHostController, scrollState: ScrollState) {
    val revision = activity.revision
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); beat++ } }
    val focus = remember(revision, beat) { activity.db.one("state", "focus") }
    val settings = remember(revision) { activity.db.settings() }
    val tasks = remember(revision) { activity.engine.current(System.currentTimeMillis()).filter {
        it.optString("status") == "open" && !activity.engine.hasChildren(it)
    } }
    val taskId = activity.focusTaskId
    val minutes = activity.focusMinutes
    val forward = activity.focusForward
    val apps = activity.focusApps
    var picker by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val phase = focus?.optString("phase") ?: ""
    val motion = LocalMotionEnabled.current
    val richMotion = richMotionEnabled()
    val haptics = LocalHapticFeedback.current
    var previousPhase by remember { mutableStateOf<String?>(null) }
    var showingCompletion by remember { mutableStateOf(false) }
    LaunchedEffect(phase) {
        val completed = previousPhase == "work" && phase == "break"
        previousPhase = phase
        if (completed) {
            showingCompletion = true
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(AppMotion.focusCompletion.toLong())
            showingCompletion = false
        } else if (phase != "break") showingCompletion = false
    }
    val presentation = focusPresentation(focus, showingCompletion)
    val activeTask = (focus?.optString("task")?.takeIf { it.isNotEmpty() } ?: taskId)
        .takeIf { it.isNotEmpty() }?.let { activity.db.one("instances", it) }
    val title = activeTask?.optString("name") ?: "自由专注"
    val location = (focus?.optString("place")?.takeIf { it.isNotEmpty() }
        ?: activeTask?.optString("place")?.takeIf { it.isNotEmpty() })
        ?.let { activity.db.one("places", it)?.optString("name") }
    val limitCount = settings.optJSONArray("appLimits")?.let { rules ->
        (0 until rules.length()).count { rules.optJSONObject(it)?.optBoolean("enabled", true) == true }
    } ?: 0
    var preferencesOpen by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val compactWindow = maxHeight < 580.dp || LocalDensity.current.fontScale > 1.15f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.page, vertical = AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.heightIn(min = 48.dp).testTag("focus-app-limits")
                        .quietClickable("管理每日与每周应用限额") { nav.navigate("app-limits") }
                        .padding(horizontal = AppSpacing.xs).semantics {
                            stateDescription = if (limitCount == 0) "尚未设置应用限额"
                                else "$limitCount 个应用已启用，${activity.appRestrictionMode()}"
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Text("应用限额", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(" ›", Modifier.clearAndSetSemantics { }, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (location != null) Caption("地点 · $location")
                CompanionSettings(activity)
                val forwardWork = focus?.optBoolean("forward") == true &&
                    (phase == "work" || (phase == "paused" && focus.optString("pausedPhase") != "break"))
                val time = if (focus == null) (minutes.toLongOrNull() ?: 0) * Rules.MINUTE else
                    if (phase == "ready") focus.optLong("minutes") * Rules.MINUTE else
                        if (forwardWork) focus.optLong("total") else focus.optLong("remaining")
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        val cycleCount = focus?.optInt("cycles", settings.optInt("cycles", 4))?.coerceAtLeast(1)
                            ?: activity.focusCycles.toIntOrNull()?.coerceAtLeast(1) ?: 4
                        val round = ((focus?.optInt("cycle", 1) ?: 1) - 1) % cycleCount + 1
                        Text("第 $round / $cycleCount 轮",
                            style = MaterialTheme.typography.labelLarge)
                        AnimatedContent(presentation.status, transitionSpec = {
                            fadeIn(tween(if (motion) AppMotion.stateChange else 0, easing = AppMotion.easing)) togetherWith
                                fadeOut(tween(if (motion) AppMotion.stateChange else 0, easing = AppMotion.easing))
                        }, label = "专注阶段") { status -> Caption(status) }
                    }
                    val workFull = (focus?.optInt("minutes", minutes.toIntOrNull() ?: 25)
                        ?: (minutes.toIntOrNull() ?: 25)).toLong() * Rules.MINUTE
                    val isBreak = phase == "break" || (phase == "paused" && focus?.optString("pausedPhase") == "break")
                    val breakMinutes = if ((focus?.optInt("cycle", 1) ?: 1) % (focus?.optInt("cycles", 4) ?: 4).coerceAtLeast(1) == 0)
                        focus?.optInt("longBreak", 15) ?: activity.focusLongBreak.toIntOrNull() ?: 15
                        else focus?.optInt("shortBreak", 5) ?: activity.focusShortBreak.toIntOrNull() ?: 5
                    val full = if (isBreak) breakMinutes.toLong() * Rules.MINUTE else workFull
                    val fraction = when {
                        focus == null || phase == "ready" -> 0f
                        forwardWork ->
                            (time.toFloat() / full.coerceAtLeast(1)).coerceIn(0f, 1f)
                        else -> (1f - time.toFloat() / full.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                    val ring = remember { Animatable(fraction) }
                    val ringTarget = if (showingCompletion) 1f else fraction
                    LaunchedEffect(ringTarget, phase, motion, richMotion) {
                        when {
                            !motion || focus == null || phase == "ready" || phase == "paused" -> ring.snapTo(ringTarget)
                            showingCompletion -> ring.animateTo(1f, tween(AppMotion.focusRingReset, easing = AppMotion.easing))
                            phase == "break" && ring.value > ringTarget + .5f ->
                                ring.animateTo(ringTarget, tween(AppMotion.focusRingReset, easing = AppMotion.easing))
                            else -> ring.animateTo(ringTarget,
                                tween(if (richMotion) AppMotion.focusRingStep else AppMotion.progress,
                                    easing = LinearEasing))
                        }
                    }
                    val compact = LocalConfiguration.current.screenWidthDp <= 360
                    val largeType = LocalDensity.current.fontScale > 1.15f
                    val timeReadout: @Composable ColumnScope.() -> Unit = {
                        Text(if (forwardWork) "已专注" else "剩余时间", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Text(countdown(time), Modifier.testTag("focus-time"),
                            fontSize = if (compact) 48.sp else 54.sp, maxLines = 1, softWrap = false,
                            fontWeight = FontWeight.Bold, style = AppTypography.number,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("本轮进度 ${(fraction * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp, style = MaterialTheme.typography.labelMedium)
                    }
                    if (compactWindow && largeType) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            HourglassInstrument({ ring.value }, presentation.hourglass, compact, motion, richMotion,
                                "本轮进度 ${(fraction * 100).toInt()}%", instrumentSize = 80.dp)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, content = timeReadout)
                        }
                    } else Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                        HourglassInstrument({ ring.value }, presentation.hourglass, compact, motion, richMotion,
                            "${if (forwardWork) "已专注" else "本轮"}进度 ${(fraction * 100).toInt()}%",
                            instrumentSize = if (compactWindow) 104.dp else if (compact) 184.dp else 212.dp)
                        timeReadout()
                    }
                    val expected = when {
                        focus == null -> "开始后显示预计结束时间"
                        phase == "ready" -> "开始后显示预计结束时间"
                        forwardWork -> "由你决定结束时间"
                        phase == "paused" -> "继续后更新预计结束时间"
                        else -> "预计 ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + time))} 结束"
                    }
                    Caption(expected)
                }
                if (focus != null) {
                    Caption("累计专注 ${duration(focus.optLong("total"))} · 下一段${if (phase == "break") "专注" else "休息"}")
                } else {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("focus-preferences")
                        .quietClickable(if (preferencesOpen) "收起本次配置" else "调整本次配置") {
                            preferencesOpen = !preferencesOpen
                        }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本次配置", style = MaterialTheme.typography.labelLarge)
                            Caption("${activity.focusMinutes} 分钟专注 · ${activity.focusShortBreak} 分钟休息 · ${activity.focusCycles} 轮")
                        }
                        DisclosureMark(preferencesOpen)
                    }
                    FormDisclosure(preferencesOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("25/5", "50/10", "自定义").forEach { preset ->
                                    FilterChip(selected = activity.focusPreset == preset, onClick = {
                                        activity.focusPreset = preset
                                        when (preset) {
                                            "25/5" -> { activity.focusMinutes = "25"; activity.focusShortBreak = "5"; activity.focusLongBreak = "15" }
                                            "50/10" -> { activity.focusMinutes = "50"; activity.focusShortBreak = "10"; activity.focusLongBreak = "20" }
                                        }
                                    }, label = { Text(preset) }, modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Bottom) {
                                Box(Modifier.weight(1f)) {
                                    SelectField("关联任务", listOf("自由专注") + tasks.map { it.optString("name") },
                                        (tasks.indexOfFirst { it.optString("id") == taskId } + 1).coerceAtLeast(0)) {
                                        activity.focusTaskId = if (it == 0) "" else tasks[it - 1].optString("id")
                                    }
                                }
                                Input("分钟", minutes, { activity.focusMinutes = it; activity.focusPreset = "自定义" }, Modifier.weight(1f),
                                    keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                            }
                            if (activity.focusPreset == "自定义" || activity.focusPreset == "custom") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Input("短休息", activity.focusShortBreak, { activity.focusShortBreak = it }, Modifier.weight(1f),
                                        keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                                    Input("长休息", activity.focusLongBreak, { activity.focusLongBreak = it }, Modifier.weight(1f),
                                        keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                                }
                            }
                            Input("每组轮次", activity.focusCycles, { activity.focusCycles = it },
                                keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(forward, { activity.focusForward = it }); Text("正向计时，手动结束")
                            }
                            Secondary("本次专注限制") { picker = true }
                            Caption(if (jsonArray(apps).length() == 0) "尚未选择应用，仅专注计时"
                                else "专注进行中限制 ${jsonArray(apps).length()} 个应用，暂停与休息时解除")
                            Caption("本次：${activity.focusMinutes} 分钟专注 · ${activity.focusShortBreak} 分钟短休息 · 每 ${activity.focusCycles} 轮长休息 ${activity.focusLongBreak} 分钟")
                            Caption("休息结束自动开始下一轮；专注与休息结束均有提示。后台弹窗需要悬浮窗权限。")
                        }
                    }
                }
                Spacer(Modifier.height(AppSpacing.xs))
            }
            // Actions stay in the available viewport, independent of instrument/config scroll.
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .testTag("focus-actions").padding(horizontal = AppSpacing.page, vertical = AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                if (focus == null) GlassPrimaryButton(presentation.action, Modifier.fillMaxWidth(), iconRes = presentation.icon) {
                    if (activity.startFocusDraft()) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                if (focus != null) {
                    val primaryAction: @Composable (Modifier) -> Unit = { modifier ->
                        GlassPrimaryButton(presentation.action,
                            modifier, iconRes = presentation.icon) {
                                if (activity.act { when (phase) {
                                    "work" -> activity.engine.pauseFocus()
                                    "break" -> activity.engine.skipBreak()
                                    else -> activity.engine.resumeFocus()
                                } })
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                    }
                    if (LocalDensity.current.fontScale > 1.15f) {
                        primaryAction(Modifier.fillMaxWidth())
                        Secondary("结束本次", Modifier.fillMaxWidth()) { confirmEnd = true }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                        primaryAction(Modifier.weight(1f))
                        Secondary("结束本次", Modifier.widthIn(min = 108.dp)) { confirmEnd = true }
                    }
                }
            }
        }
    }
    AppPicker(activity, apps, picker, { picker = false }) { activity.focusApps = it; picker = false }
    if (confirmEnd) ConfirmDialog("结束本次专注？", "结束计时，已记录的有效专注时长会保留。", {
        if (activity.act { activity.engine.endFocus() }) confirmEnd = false
    }) { confirmEnd = false }
}

internal data class ChartRow(val label: String, val focus: Long)

@Composable
internal fun StatsScreen(activity: ComposeMainActivity, nav: NavHostController) {
    val revision = activity.revision
    var range by rememberSaveable { mutableIntStateOf(7) }
    val motion = richMotionEnabled()
    val now = remember(revision, range) { System.currentTimeMillis() }
    val day = Rules.date(now)
    val first = when (range) { 1 -> day; 7 -> Rules.monday(day); else -> day.withDayOfMonth(1) }
    val start = Rules.at(first, 0)
    val window = listOf(longArrayOf(start, now))
    val focusRanges = remember(revision) { activity.engine.ranges("focus", null, null) }
    val presenceRanges = remember(revision) { activity.engine.ranges("presence", null, null) }
    val focus = Rules.union(Rules.intersect(focusRanges, window))
    val presence = Rules.union(Rules.intersect(presenceRanges, window))
    val ended = remember(revision, range) { activity.db.all("instances").filter {
        it.optLong("due") in start..now && !activity.engine.hasChildren(it) && it.optString("status") != "leave"
    } }
    val done = ended.count { activity.done(it) }
    val failed = ended.count { it.optString("status") == "failed" }
    val review = ended.count { it.optString("status") == "review" }
    DraggableSegment(listOf("今天", "本周", "本月"), when (range) { 1 -> 0; 7 -> 1; else -> 2 }, activity.motionEnabled) {
        range = listOf(1, 7, 30)[it]
    }
    val empty = focus == 0L && presence == 0L && ended.isEmpty()
    var emptyVisible by rememberSaveable { mutableStateOf(!motion) }
    LaunchedEffect(empty, motion) { emptyVisible = empty }
    if (empty) {
        AnimatedVisibility(emptyVisible, enter = fadeIn(tween(if (motion) AppMotion.statsEmpty else 0, easing = AppMotion.easing)) +
            slideInVertically(tween(if (motion) AppMotion.statsEmpty else 0, easing = AppMotion.easing), initialOffsetY = { it / 12 })) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Panel {
                    Image(painterResource(R.drawable.empty_stats_mist), contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(120.dp))
                    Text("还没有专注记录", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Caption("开始一次专注，查看时长与任务进度。")
                }
                Primary("开始专注") { activity.openTab("focus", nav) }
            }
        }
        return
    }
    Caption("统计范围 · ${timeText(start)} 至现在")
    Panel {
        Text("本期概览", style = MaterialTheme.typography.titleMedium)
        val focusRecords = remember(revision, range) { activity.db.all("sessions").count {
            it.optString("kind") == "focus" && it.optLong("end") > start && it.optLong("start") < now
        } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Caption("专注时长")
            AnimatedStatDuration(focus, motion, "专注时长")
            Caption(if (focus >= 60 * Rules.MINUTE) "小时:分钟" else "分钟")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
            Column(Modifier.weight(1f)) {
                Caption("专注记录")
                Text("$focusRecords", style = AppTypography.number, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Caption("任务完成率")
                Text(if (ended.isEmpty()) "—" else "${done * 100 / ended.size}%",
                    style = AppTypography.number, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
        Caption("到场时长 ${duration(presence)} · 专注记录按有效计时片段统计")
    }
    val rows = remember(revision, range) { chartRows(range, first, now, focusRanges) }
    Section("专注时长")
    Panel {
        Caption(if (range == 1) "今天 · 每 4 小时" else if (range == 7) "本周 · 每天" else "本月 · 每天")
        Caption("单位：分钟 · 薄荷柱表示有效专注时长")
        if (rows.any { it.focus > 0 }) {
            var selected by remember(range) { mutableIntStateOf(rows.indices.maxByOrNull { rows[it].focus } ?: 0) }
            FocusDurationChart(rows, selected, range, motion) { selected = it }
            Caption("${rows[selected].label} · 专注 ${duration(rows[selected].focus)}")
            val peak = rows.maxByOrNull { it.focus }
            if (peak != null) Caption("本期专注最多的是 ${peak.label}，共 ${duration(peak.focus)}。")
        } else Caption("本期暂无专注记录。完成一次专注后显示时长。")
    }
    Section("任务完成")
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Column(Modifier.weight(1f)) {
                Text("到期任务  ${if (ended.isEmpty()) "暂无" else "$done / ${ended.size}"}", fontSize = 19.sp)
                Caption("$failed 项未完成 · $review 项待核验")
            }
            if (ended.isNotEmpty()) {
                val completed = animatedStatProgress(done.toFloat() / ended.size,
                    "$range-task-completion", motion)
                CircularProgressIndicator(progress = { completed }, modifier = Modifier.size(56.dp)
                    .clearAndSetSemantics { contentDescription = "到期任务完成 $done 项，共 ${ended.size} 项" },
                    strokeWidth = 5.dp, color = AppColors.current.chartFocus,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
    Section("各计划专注时长")
    val plans = remember(revision) { activity.db.all("plans") }
    val reportInstances = remember(revision) { activity.db.all("instances") }
    val goalDurations = remember(revision, range) { plans.map { plan ->
        val ranges = reportInstances.filter { it.optString("planId") == plan.optString("id") }
            .flatMap { activity.engine.ranges("focus", null, it.optString("id")) }
        val ms = Rules.union(Rules.intersect(ranges, window))
        plan.optString("name") to ms
    }.filter { it.second > 0 }.sortedByDescending { it.second } }
    Panel {
        Caption("本期专注时长 · 单位：分钟")
        if (goalDurations.isEmpty()) Caption("暂无关联计划的专注记录。开始专注时选择任务即可累计。")
        goalDurations.forEach { (name, ms) ->
            Text("$name · ${duration(ms)}")
            val progress = animatedStatProgress(ms.toFloat() /
                goalDurations.first().second.coerceAtLeast(1), "$range-goal-$name", motion)
            LinearProgressIndicator(progress = { progress },
                Modifier.fillMaxWidth(), color = AppColors.current.chartFocus,
                trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        if (goalDurations.isNotEmpty()) Caption("投入最多的目标：${goalDurations.first().first}。")
    }
    Section("各地点到场时长")
    val placeDurations = remember(revision, range) { activity.db.all("places").map { place ->
        val ms = Rules.union(Rules.intersect(activity.engine.ranges("presence", place.optString("id"), null), window))
        place.optString("name") to ms
    }.filter { it.second > 0 }.sortedByDescending { it.second } }
    Panel {
        Caption("本期到场时长 · 单位：分钟")
        if (placeDurations.isEmpty()) Caption("暂无到场记录。添加地点并开启监测后即可累计。")
        placeDurations.forEach { (name, ms) ->
            Text("$name · ${duration(ms)}")
            val progress = animatedStatProgress(ms.toFloat() /
                placeDurations.first().second.coerceAtLeast(1), "$range-place-$name", motion)
            LinearProgressIndicator(progress = { progress },
                Modifier.fillMaxWidth(), color = AppColors.current.chartPresence,
                trackColor = MaterialTheme.colorScheme.surfaceVariant)
        }
        if (placeDurations.isNotEmpty()) Caption("停留最久的地点：${placeDurations.first().first}。")
    }
    Section("复盘")
    Caption(when { failed > 0 -> "有 $failed 项任务未完成。回看目标是否过大、时间是否合适。"
        review > 0 -> "先处理定位异常，核验后再判断本期完成情况。"
        ended.isEmpty() -> "完成第一个周期后，这里会提供复盘提示。"
        else -> "本期已完成 $done 项任务。" })
    Secondary("查看操作与异常记录") { nav.navigate("events") }
    Secondary("导出统计 CSV") { activity.exportCsv() }
}

@Composable
internal fun FocusDurationChart(rows: List<ChartRow>, selected: Int, range: Int, motion: Boolean,
                               onSelect: (Int) -> Unit) {
    val slot = 48.dp
    val maxMinutes = rows.maxOf { (it.focus / Rules.MINUTE).toInt() }.coerceAtLeast(1)
    val axisTop = ((maxMinutes + 29) / 30) * 30
    val animatedAxis = animateFloatAsState(axisTop.toFloat(),
        tween(if (motion) AppMotion.statsBar else 0, easing = AppMotion.easing), label = "图表刻度范围")
    val focusColor = AppColors.current.chartFocus
    val selectedColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = .35f)
    val scroll = rememberScrollState()
    val slotPixels = with(LocalDensity.current) { slot.toPx() }
    LaunchedEffect(range) { scroll.scrollTo((selected * slotPixels).toInt().coerceAtMost(scroll.maxValue)) }
    var firstDraw by rememberSaveable { mutableStateOf(!motion) }
    LaunchedEffect(motion) { firstDraw = true }
    val barMinutes = rows.mapIndexed { index, row ->
        key(index) {
            val minutes = animateFloatAsState(
                if (firstDraw) row.focus.toFloat() / Rules.MINUTE else 0f,
                tween(if (motion) AppMotion.statsBar else 0, easing = AppMotion.easing), label = "${index}日专注时长")
            minutes
        }
    }
    val labelWidth = 88.dp * LocalConfiguration.current.fontScale.coerceIn(1f, 1.5f)
    val selectedX = animateDpAsState(
        (slot * selected + slot / 2 - labelWidth / 2).coerceIn(0.dp,
            (slot * rows.size - labelWidth).coerceAtLeast(0.dp)),
        tween(if (motion) AppMotion.chartSelection else 0, easing = AppMotion.easing), label = "统计数据标签位置")
    val selectedStrength = rows.indices.map { index ->
        key(index) { animateFloatAsState(if (index == selected) 1f else 0f,
            tween(if (motion) AppMotion.chartSelection else 0, easing = AppMotion.easing), label = "选中数据列") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.width(34.dp)) {
            Spacer(Modifier.height(34.dp))
            Column(Modifier.height(148.dp), verticalArrangement = Arrangement.SpaceBetween) {
                ChartAxisLabel(animatedAxis, 1f)
                ChartAxisLabel(animatedAxis, .5f)
                Text("0", fontSize = 11.sp, style = AppTypography.number,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).horizontalScroll(scroll)) {
            Box(Modifier.width(slot * rows.size).height(34.dp)) {
                Surface(Modifier.width(labelWidth).height(30.dp)
                    .graphicsLayer { translationX = selectedX.value.toPx() }
                    .testTag("chart-selection-label"),
                    shape = AppShapes.pill, color = MaterialTheme.colorScheme.secondaryContainer) {
                    AnimatedContent(rows[selected].focus / Rules.MINUTE, transitionSpec = {
                        fadeIn(tween(if (motion) AppMotion.state else 0)) togetherWith
                            fadeOut(tween(if (motion) AppMotion.outgoing else 0))
                    }, label = "所选时长", modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "${rows[selected].label}专注${duration(rows[selected].focus)}"
                    }) { minutes ->
                        Text("$minutes 分钟", Modifier.fillMaxSize().wrapContentSize(),
                            style = AppTypography.number, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1)
                    }
                }
            }
            Canvas(Modifier.width(slot * rows.size).height(148.dp).testTag("focus-duration-chart")
                .pointerInput(rows) {
                    detectTapGestures { offset ->
                        onSelect((offset.x / slot.toPx()).toInt().coerceIn(rows.indices))
                    }
                }.semantics { contentDescription =
                    if (range == 1) "分时段专注时长柱状图，单位分钟" else "每日专注时长柱状图，单位分钟" }) {
                val chartHeight = size.height - 8.dp.toPx()
                for (step in 0..2) {
                    val y = chartHeight * step / 2f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val slotPx = slot.toPx()
                rows.forEachIndexed { index, row ->
                    val minutes = barMinutes[index].value
                    val height = (chartHeight * minutes / animatedAxis.value.coerceAtLeast(1f)).coerceAtLeast(if (minutes > 0) 3.dp.toPx() else 0f)
                    if (height > 0f) drawRoundRect(
                        color = androidx.compose.ui.graphics.lerp(focusColor, selectedColor, selectedStrength[index].value),
                        topLeft = Offset(index * slotPx + slotPx * .27f, chartHeight - height),
                        size = Size(slotPx * .46f, height), cornerRadius = CornerRadius(5.dp.toPx()))
                }
            }
            Row(Modifier.width(slot * rows.size)) {
                rows.forEachIndexed { index, row ->
                    Text(row.label, Modifier.width(slot).heightIn(min = 48.dp)
                        .quietClickable("查看${row.label}专注数据") { onSelect(index) }
                        .semantics {
                            contentDescription = "${row.label}，专注${duration(row.focus)}"
                            this.selected = index == selected
                        },
                        fontSize = 12.sp, style = AppTypography.number,
                        color = if (index == selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChartAxisLabel(value: State<Float>, fraction: Float) {
    Text("${kotlin.math.round(value.value * fraction).toInt()}",
        fontSize = 11.sp, style = AppTypography.number,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AnimatedStatDuration(actualMs: Long, motion: Boolean, label: String) {
    val actualMinutes = (actualMs / Rules.MINUTE).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    var target by rememberSaveable { mutableIntStateOf(if (motion) 0 else actualMinutes) }
    LaunchedEffect(actualMinutes, motion) { target = actualMinutes }
    val shown by animateIntAsState(target,
        tween(if (motion) AppMotion.statsNumber else 0, easing = AppMotion.easing), label = label)
    Text(if (actualMinutes >= 60) "%d:%02d".format(Locale.CHINA, shown / 60, shown % 60)
        else "$shown", fontSize = 21.sp,
        fontWeight = FontWeight.Bold, style = AppTypography.number,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label ${duration(actualMs)}" })
}

@Composable
private fun animatedStatProgress(actual: Float, key: String, motion: Boolean): Float {
    val bounded = actual.coerceIn(0f, 1f)
    var target by rememberSaveable { mutableFloatStateOf(if (motion) 0f else bounded) }
    LaunchedEffect(key, bounded, motion) { target = bounded }
    val shown by animateFloatAsState(target,
        tween(if (motion) AppMotion.statsBar else 0, easing = AppMotion.easing), label = "统计进度")
    return shown
}

private fun chartRows(range: Int, first: LocalDate, now: Long,
                      focus: List<LongArray>): List<ChartRow> {
    val count = when (range) { 1 -> 6; 7 -> 7; else -> first.lengthOfMonth() }
    return (0 until count).map { i ->
        val date = first.plusDays(i.toLong())
        val from = if (range == 1) Rules.at(first, i * 240) else Rules.at(date, 0)
        val to = minOf(now, if (range == 1) Rules.at(first, (i + 1) * 240)
            else Rules.at(date.plusDays(1), 0))
        val label = if (range == 1) "%02d–%02d时".format(i * 4, (i + 1) * 4)
            else "${date.monthValue}/${date.dayOfMonth}"
        val window = if (to > from) listOf(longArrayOf(from, to)) else emptyList()
        ChartRow(label, Rules.union(Rules.intersect(focus, window)))
    }
}
