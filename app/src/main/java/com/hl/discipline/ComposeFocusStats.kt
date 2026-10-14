package com.hl.discipline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
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
        activity.engine.canFocus(it, System.currentTimeMillis())
    } }
    val taskId = activity.focusTaskId
    LaunchedEffect(taskId, revision, focus == null) {
        if (focus == null && taskId.isNotEmpty() && tasks.none { it.optString("id") == taskId })
            activity.selectFocusTask("")
    }
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
    val turnToken = rememberFocusTurn(focus?.let { FocusTurnIdentity(it.optLong("start"), it.optInt("cycle", 1)) },
        phase, motion) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val presentation = focusPresentation(focus, showingCompletion)
    val activeTask = (if (focus != null) focus.optString("task") else taskId)
        .takeIf { it.isNotEmpty() }?.let { activity.db.one("instances", it) }
    val title = activeTask?.optString("name") ?: "自由专注"
    val location = (focus?.optString("place")?.takeIf { it.isNotEmpty() }
        ?: activeTask?.optString("place")?.takeIf { it.isNotEmpty() })
        ?.let { activity.db.one("places", it)?.optString("name") }
    val limitCount = UsageWeekPlans.effectiveLimits(activity.db,System.currentTimeMillis()).let { rules ->
        (0 until rules.length()).count { rules.optJSONObject(it)?.optBoolean("enabled", true) == true }
    }
    var preferencesOpen by rememberSaveable { mutableStateOf(false) }
    var taskPicker by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        val compactWindow = maxHeight < 580.dp || LocalDensity.current.fontScale > 1.15f
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(scrollState)
                .padding(horizontal = AppSpacing.page, vertical = if (compactWindow) 4.dp else AppSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(if (compactWindow) 4.dp else AppSpacing.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                     val taskControl = Modifier.weight(1f).heightIn(min = 48.dp)
                         .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                         .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f))
                     Row((if (focus == null) taskControl.testTag("focus-task-selector")
                         .quietClickable("选择关联任务") { taskPicker = true } else taskControl)
                         .padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                         verticalAlignment = Alignment.CenterVertically) {
                         Icon(painterResource(R.drawable.ic_target), contentDescription = null,
                             modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                         Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                             maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                         if (focus == null) DisclosureMark(taskPicker)
                     }
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
                CompanionSettings(activity, compact = true)
                if (location != null) MetaPill("地点 · $location")
                val forwardWork = focus?.optBoolean("forward") == true &&
                    (phase == "work" || (phase == "paused" && focus.optString("pausedPhase") != "break"))
                val time = if (focus == null) (minutes.toLongOrNull() ?: 0) * Rules.MINUTE else
                    if (phase == "ready") focus.optLong("minutes") * Rules.MINUTE else
                        if (forwardWork) focus.optLong("total") else focus.optLong("remaining")
                FocusInstrumentPanel(compactWindow) {
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
                            fontSize = if (compactWindow && largeType) 36.sp else if (compactWindow) 42.sp else if (compact) 48.sp else 54.sp, maxLines = 1, softWrap = false,
                            fontWeight = FontWeight.Bold, style = AppTypography.number,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (compactWindow) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            HourglassInstrument({ ring.value }, presentation.hourglass, compact, motion, richMotion,
                                "本轮进度 ${(fraction * 100).toInt()}%", instrumentSize = 64.dp, turnToken = turnToken)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, content = timeReadout)
                        }
                    } else Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                        HourglassInstrument({ ring.value }, presentation.hourglass, compact, motion, richMotion,
                            "${if (forwardWork) "已专注" else "本轮"}进度 ${(fraction * 100).toInt()}%",
                            instrumentSize = if (compactWindow) 104.dp else if (compact) 184.dp else 212.dp, turnToken = turnToken)
                        timeReadout()
                    }
                    val expected = when {
                        focus == null -> "预计 ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + time))} 结束"
                        phase == "ready" -> "开始后显示预计结束时间"
                        forwardWork -> "由你决定结束时间"
                        phase == "paused" -> "继续后更新预计结束时间"
                        else -> "预计 ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + time))} 结束"
                    }
                    Row(Modifier.fillMaxWidth().height(32.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(expected, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("本轮进度 ${(fraction * 100).toInt()}%", fontSize = 12.sp,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (focus == null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("focus-preferences")
                            .quietClickable(if (preferencesOpen) "收起本次配置" else "调整本次配置") {
                                preferencesOpen = !preferencesOpen
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙ 本次配置：专注 ${activity.focusMinutes}分 · 休息 ${activity.focusShortBreak}分",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            DisclosureMark(preferencesOpen)
                        }
                        FormDisclosure(preferencesOpen, animateHeight = false) {
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
                                            activity.selectFocusTask(if (it == 0) "" else tasks[it - 1].optString("id"))
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
                    } else {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙ 本次配置：专注 ${focus.optInt("minutes")}分 · 休息 ${focus.optInt("shortBreak")}分",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Caption("本次配置")
                        }
                    }
                }
                val summary = if (focus != null)
                    "累计专注 ${duration(focus.optLong("total"))} · 下一段${if (phase == "break") "专注" else "休息"}"
                    else "按自己的节奏，开始这一轮专注。"
                Box(Modifier.fillMaxWidth().height(32.dp)) {
                    if (!motion) Caption(summary)
                    else Crossfade(summary, animationSpec = tween(AppMotion.state, easing = AppMotion.easing),
                        label = "专注说明原位切换") { shown ->
                        ScreenExitGuard(shown != summary, Modifier.fillMaxWidth()) {
                            Text(shown, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(AppSpacing.xs))
            }
            // Actions stay in the available viewport, independent of instrument/config scroll.
            Column(Modifier.fillMaxWidth().height(if (LocalDensity.current.fontScale > 1.15f) 136.dp else 80.dp)
                .background(MaterialTheme.colorScheme.background)
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
    AppModalSheet(taskPicker && focus == null, onDismiss = { taskPicker = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.page).padding(bottom = AppSpacing.lg)
            .heightIn(max = editorSheetHeight()).verticalScroll(rememberScrollState())) {
            Text("选择关联任务", style = MaterialTheme.typography.titleLarge)
            (listOf("" to "自由专注") + tasks.map { it.optString("id") to it.optString("name") }).forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).quietClickable("选择$name") {
                    activity.selectFocusTask(id); taskPicker = false
                }.padding(vertical = AppSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (id == taskId) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    AppPicker(activity, apps, picker, { picker = false }) { activity.focusApps = it; picker = false }
    if (confirmEnd) ConfirmDialog("结束本次专注？", "结束计时，已记录的有效专注时长会保留。", {
        if (activity.act { activity.engine.endFocus() }) confirmEnd = false
    }) { confirmEnd = false }
}

@Composable
private fun FocusInstrumentPanel(compact: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().animateContentSize(expansionSpec(LocalMotionEnabled.current)), shape = AppShapes.card, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .48f))) {
        Column(Modifier.padding(horizontal = AppSpacing.md, vertical = if (compact) 4.dp else AppSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else AppSpacing.xs), content = content)
    }
}

@Composable
internal fun StatsScreen(activity: ComposeMainActivity, nav: NavHostController,
                         scrollState: LazyListState = rememberLazyListState(),
                         retainedReport: MutableState<StatisticsReport?> = remember { mutableStateOf(null) }) {
    val revision = activity.revision
    var selectedRange by rememberSaveable { mutableIntStateOf(7) }
    val motion = richMotionEnabled()
    var report by retainedReport
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision, selectedRange, retry) {
        loading = true; error = ""
        try {
            report = withContext(Dispatchers.IO) { StatisticsRepository.load(activity.db, selectedRange, System.currentTimeMillis()) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "统计暂时无法更新，原始记录仍保留。" }
        finally { loading = false }
    }
    val data = report
    val loadingScroll = rememberLazyListState()
    var chartSelection by rememberSaveable(data?.range, data?.start) {
        mutableIntStateOf(data?.rows?.indices?.maxByOrNull { data.rows[it].focus } ?: 0)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp)) {
            DraggableSegment(listOf("今天", "本周", "本月"), when (selectedRange) { 1 -> 0; 7 -> 1; else -> 2 }, activity.motionEnabled) {
                selectedRange = listOf(1, 7, 30)[it]
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("stats-list"),
            state = if (data == null) loadingScroll else scrollState, contentPadding = PaddingValues(bottom = AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            item("usage-entry", contentType = "action") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlEntryCard("每日/每周应用限额", "管理可用时长", R.drawable.ic_shield,
                        Modifier.weight(1f).testTag("stats-app-limits")) { nav.navigate("app-limits") }
                    ControlEntryCard("应用使用与下周计划", "查看分布与安排", R.drawable.ic_nav_stats,
                        Modifier.weight(1f)) { nav.navigate("usage-insights") }
                }
            }
            if (error.isNotEmpty()) item("error", contentType = "error") {
                Column { Caption(error); Secondary("重新整理统计") { retry++ } }
            }
            if (data == null) item("overview", contentType = "overview") {
                Panel { Text("本期概览", style = MaterialTheme.typography.titleMedium)
                    Caption(if (loading) "正在整理本地计时与任务记录…" else "可稍后重试，计划和计时不受影响。") }
            } else {
                val range = data.range
                val start = data.start
                val focus = data.focus
                val presence = data.presence
                val done = data.done
                val failed = data.failed
                val review = data.review
                item("overview", contentType = "overview") { StatsOverviewCard(data, loading, motion) }
                val rows = data.rows
                item("focus-heading", contentType = "heading") { Section("专注时长") }
                item("focus-chart", contentType = "chart") { Panel {
                    Caption(if (range == 1) "今天 · 每 4 小时" else if (range == 7) "本周 · 每天" else "本月 · 每天")
                    Caption("单位：分钟 · 薄荷柱表示有效专注时长")
                    val selected = chartSelection.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
                    FocusDurationChart(rows, selected, range, motion, onStartFocus = { activity.openTab("focus", nav) }) { chartSelection = it }
                    if (rows.any { it.focus > 0 }) {
                        Caption("${rows[selected].label} · 专注 ${duration(rows[selected].focus)}")
                        val peak = rows.maxByOrNull { it.focus }
                        if (peak != null) Caption("本期专注最多的是 ${peak.label}，共 ${duration(peak.focus)}。")
                    }
                } }
                item("tasks-heading", contentType = "heading") { Section("任务完成") }
                item("tasks", contentType = "card") { Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Column(Modifier.weight(1f)) {
                            Caption("已核验任务")
                            MetricValueText(if (data.verified == 0) listOf(MetricPart("—"))
                                else amountMetricParts("$done / ${data.verified}"), fontSize = 21.sp)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                MetaPill("$failed 项未完成")
                                MetaPill("$review 项待核验")
                            }
                        }
                        if (data.verified > 0) {
                            val completed = animatedStatProgress(done.toFloat() / data.verified,
                                "$range-task-completion", motion)
                            CircularProgressIndicator(progress = { completed.value }, modifier = Modifier.size(56.dp)
                                .clearAndSetSemantics { contentDescription = "已核验到期任务完成 $done 项，共 ${data.verified} 项" },
                                strokeWidth = 5.dp, color = AppColors.current.chartFocus,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                    Caption("按本期到期任务统计；待核验与请假不计入完成率。")
                } }
                item("goals-heading", contentType = "heading") { Section("各计划专注时长") }
                val goalDurations = data.goals
                item("goals-summary", contentType = "caption") {
                    Caption("本期专注时长 · 单位：分钟")
                    if (goalDurations.isEmpty()) Caption("暂无关联计划的专注记录。开始专注时选择任务即可累计。")
                }
                items(goalDurations, key = { "goal-${it.id}" }, contentType = { "distribution" }) { (id, name, ms) ->
                    Panel(if (motion) Modifier.animateItem(placementSpec = spring(dampingRatio = .85f, stiffness = 520f)) else Modifier) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            MetricValueText(durationMetricParts(ms), Modifier.widthIn(max = 140.dp), fontSize = 16.sp)
                        }
                        val progress = animatedStatProgress(ms.toFloat() /
                            goalDurations.first().millis.coerceAtLeast(1), "$range-goal-$id", motion)
                        LinearProgressIndicator(progress = { progress.value },
                            Modifier.fillMaxWidth(), color = AppColors.current.chartFocus,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                if (goalDurations.isNotEmpty()) item("goals-peak", contentType = "caption") { Caption("投入最多的计划：${goalDurations.first().title}。") }
                item("places-heading", contentType = "heading") { Section("各地点到场时长") }
                val placeDurations = data.places
                item("places-summary", contentType = "caption") {
                    Caption("本期到场时长 · 单位：分钟")
                    if (placeDurations.isEmpty()) Caption("暂无到场记录。添加地点并开启监测后即可累计。")
                }
                items(placeDurations, key = { "place-${it.id}" }, contentType = { "distribution" }) { (id, name, ms) ->
                    Panel(if (motion) Modifier.animateItem(placementSpec = spring(dampingRatio = .85f, stiffness = 520f)) else Modifier) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            MetricValueText(durationMetricParts(ms), Modifier.widthIn(max = 140.dp), fontSize = 16.sp)
                        }
                        val progress = animatedStatProgress(ms.toFloat() /
                            placeDurations.first().millis.coerceAtLeast(1), "$range-place-$id", motion)
                        LinearProgressIndicator(progress = { progress.value },
                            Modifier.fillMaxWidth(), color = AppColors.current.chartPresence,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
                if (placeDurations.isNotEmpty()) item("places-peak", contentType = "caption") { Caption("停留最久的地点：${placeDurations.first().title}。") }
                item("review", contentType = "review") { Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    Section("复盘")
                    Caption(when { failed > 0 -> "有 $failed 项任务未完成。回看目标是否过大、时间是否合适。"
                        review > 0 -> "先处理定位异常，核验后再判断本期完成情况。"
                        data.ended == 0 -> "完成第一个周期后，这里会提供复盘提示。"
                        else -> "本期已完成 $done 项任务。" })
                    Secondary("查看操作与异常记录") { nav.navigate("events") }
                    Secondary("导出统计 CSV") { activity.exportCsv() }
                } }
            }
        }
    }
}

@Composable
internal fun StatsRangeLine(label: String, loading: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterEnd) {
        Text(if (loading) "更新中…" else label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun StatsOverviewCard(data: StatisticsReport, loading: Boolean, motion: Boolean) {
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本期概览", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            val rangeLabel = SimpleDateFormat("M/d", Locale.CHINA).let {
                "${it.format(Date(data.start))}—${it.format(Date(data.now))}"
            }
            StatsRangeLine(rangeLabel, loading, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(.58f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Caption("专注总时长")
                Box(Modifier.fillMaxWidth().height(if (LocalDensity.current.fontScale > 1.2f) 96.dp else 72.dp)) {
                    AnimatedStatDuration(data.focus, motion, "专注时长")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("段均", Modifier.alignByBaseline(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MetricValueText(listOf(MetricPart(if (data.focusSegments > 0)
                        "${data.focus / data.focusSegments / Rules.MINUTE}" else "—", "分钟")),
                        Modifier.alignByBaseline(), fontSize = 16.sp)
                }
            }
            Column(Modifier.weight(.42f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Caption("有效专注段")
                    AnimatedMetricValue(listOf(MetricPart("${data.focusSegments}", "段")), motion, fontSize = 21.sp)
                }
                Column {
                    Caption("到期任务完成率")
                    AnimatedMetricValue(listOf(MetricPart(if (data.verified == 0) "—"
                        else "${data.done * 100 / data.verified}", if (data.verified == 0) "" else "%")), motion, fontSize = 21.sp)
                }
            }
        }
        MetaPill {
            Text("累计到场", Modifier.alignByBaseline())
            MetricValueText(durationMetricParts(data.presence), Modifier.alignByBaseline(),
                fontSize = 12.sp, unitSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .quietClickable(if (rulesOpen) "收起统计规则" else "展开统计规则") { rulesOpen = !rulesOpen },
            verticalAlignment = Alignment.CenterVertically) {
            Text("ⓘ 统计规则", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            DisclosureMark(rulesOpen)
        }
        FormDisclosure(rulesOpen) {
            Caption("相连计时片段合并为一段；暂停或休息后的不连续计时另计。")
        }
    }
}

@Composable
internal fun FocusDurationChart(rows: List<ChartRow>, selected: Int, range: Int, motion: Boolean,
                               onStartFocus: (() -> Unit)? = null,
                               onSelect: (Int) -> Unit) {
    if (rows.none { it.focus > 0 }) {
        Column(Modifier.fillMaxWidth().testTag("focus-duration-chart")
            .padding(vertical = AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            Caption("本周期还没有有效专注记录，前往「专注」开始计时后将在此按时段汇总。")
            if (onStartFocus != null) TextButton(onClick = onStartFocus, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("前往专注 ›")
            }
        }
        return
    }
    val slot = 48.dp
    val maxMinutes = rows.maxOf { (it.focus / Rules.MINUTE).toInt() }.coerceAtLeast(1)
    val axisTop = ((maxMinutes + 29) / 30) * 30
    val animatedAxis = animateFloatAsState(axisTop.toFloat(),
        progressSpec(motion), label = "图表刻度范围")
    val axisState = remember(motion, axisTop, animatedAxis) {
        derivedStateOf { if (motion) animatedAxis.value else axisTop.toFloat() }
    }
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
                progressSpec(motion), label = "${index}日专注时长")
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
                ChartAxisLabel(axisState, 1f)
                ChartAxisLabel(axisState, .5f)
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
                        staggeredTextTransform(motion)
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
                    val minutes = if (motion) barMinutes[index].value else row.focus.toFloat() / Rules.MINUTE
                    val height = (chartHeight * minutes / axisState.value.coerceAtLeast(1f)).coerceAtLeast(if (minutes > 0) 3.dp.toPx() else 0f)
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
internal fun AnimatedStatDuration(actualMs: Long, motion: Boolean, label: String) {
    val actualMinutes = (actualMs / Rules.MINUTE).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val content: @Composable (Int) -> Unit = { shown ->
        ScreenExitGuard(shown != actualMinutes, Modifier.wrapContentSize()) {
            MetricValueText(minuteMetricParts(shown.toLong()),
                Modifier.clearAndSetSemantics { contentDescription = "$label ${duration(actualMs)}" }, fontSize = 28.sp)
        }
    }
    if (!motion) content(actualMinutes)
    else AnimatedContent(actualMinutes, transitionSpec = { precisionNumberTransform(true) },
        label = label, content = { content(it) })
}

@Composable
private fun animatedStatProgress(actual: Float, key: String, motion: Boolean): State<Float> {
    val bounded = actual.coerceIn(0f, 1f)
    var target by rememberSaveable { mutableFloatStateOf(if (motion) 0f else bounded) }
    LaunchedEffect(key, bounded, motion) { target = bounded }
    val shown = animateFloatAsState(target, progressSpec(motion), label = "统计进度")
    return remember(shown, motion, bounded) { derivedStateOf { if (motion) shown.value else bounded } }
}
