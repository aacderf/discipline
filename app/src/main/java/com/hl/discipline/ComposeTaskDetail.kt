package com.hl.discipline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import org.json.JSONObject

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun TaskDetail(activity: ComposeMainActivity, nav: NavHostController, id: String) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val revision = activity.revision
    val task = remember(id, revision) { activity.db.one("instances", id) }
    if (task == null) { LaunchedEffect(id) { nav.popBackStack() }; return }
    var manual by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var failConfirm by remember { mutableStateOf(false) }
    var penaltySettings by remember { mutableStateOf(false) }
    val visual = task.visual()
    val transitionId = LocalTaskTransitionId.current
    val shared = richMotionEnabled() && transitionId?.value == visual.id
    val detailScroll = rememberScrollState()
    val statusText = "${activity.status(task)} · ${activity.amount(task)}"
    val leaving = LocalTaskMotion.current?.visibility?.transition?.targetState == androidx.compose.animation.EnterExitState.PostExit
    ScreenExitGuard(leaving) {
    Screen("任务详情", onBack = { nav.popBackStack() }, scrollState = detailScroll) {
        GlassTaskPanel(Modifier.taskShared("card-${visual.id}", shared, bounds = true)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TaskTypeGlyph(visual.metric, Modifier.taskShared("glyph-${visual.id}", shared)
                    .graphicsLayer {
                        translationY = if (shared) (detailScroll.value * .08f).coerceAtMost(8.dp.toPx()) else 0f
                    },
                    completed = activity.done(task))
                Text(visual.name, Modifier.weight(1f).taskShared("title-${visual.id}", shared, bounds = true),
                    style = MaterialTheme.typography.headlineMedium)
            }
            AnimatedContent(statusText,
                transitionSpec = {
                    fadeIn(tween(if (activity.motionEnabled) AppMotion.stateChange else 0, easing = AppMotion.easing)) togetherWith
                        fadeOut(tween(if (activity.motionEnabled) AppMotion.briefExit else 0, easing = AppMotion.easing))
                }, label = "任务状态") { shown ->
                Text(shown, Modifier
                    .then(if (shown == statusText) Modifier.taskShared("status-${visual.id}", shared, bounds = true)
                        else Modifier)
                    .semantics {
                    if (shown != statusText) hideFromAccessibility()
                    else liveRegion = LiveRegionMode.Polite
                },
                    color = MaterialTheme.colorScheme.primary)
            }
            Caption("截止 ${timeText(task.optLong("due"))}")
            if (!activity.engine.hasChildren(task)) when (visual.action) {
                "标记完成" -> GlassPrimaryButton("标记完成", Modifier.fillMaxWidth().taskShared("action-${visual.id}", shared),
                    iconRes = taskActionIcon("标记完成")) {
                    if (activity.act { activity.engine.manual(task, 1.0, "手动确认完成", false) }) {
                        transitionId?.value = null
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    }
                }
                "记录数量" -> GlassPrimaryButton("记录数量", Modifier.fillMaxWidth().taskShared("action-${visual.id}", shared),
                    iconRes = taskActionIcon("记录数量")) { manual = true }
                "开始专注" -> GlassPrimaryButton("开始专注", Modifier.fillMaxWidth().taskShared("action-${visual.id}", shared),
                    iconRes = taskActionIcon("开始专注")) {
                    activity.startFocusFor(id, nav)
                }
            }
        }
        androidx.compose.foundation.layout.Box {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                val why = task.optString("why")
                if (why.isNotBlank()) Panel { Text(why) }
                Section("执行规则")
                Panel {
                    Text("有效时段 ${clockText(task.optInt("from"))}—${clockText(task.optInt("until", 1440))}")
                    val apps = task.optJSONArray("apps")
                    Text(when {
                        task.optString("metric") == "presence" && task.optString("penaltyMode") == "remainingDwell" && apps != null && apps.length() > 0 -> "未完成：剩余停留时间转为应用限制"
                        task.optInt("penalty", 120) <= 0 -> "未完成：仅记录"
                        apps == null || apps.length() == 0 -> "未完成：无应用限制"
                        else -> "未完成：${task.optInt("penalty", 120)} 分钟应用限制"
                    })
                    if (apps != null && apps.length() > 0) Caption("限制应用：${appNames(activity, apps)}")
                    if (activity.db.settings().optBoolean("trial", true)) Caption("任务限制仅记录，暂不拦截")
                    if (task.optString("place").isNotEmpty()) Caption(if (Rules.locationNeedsReview(
                        task.optString("coveragePolicy"), task.optLong("start"), task.optLong("due")))
                        "定位缺口先核验，确认后结算" else "按已记录进度结算，定位缺口不计入完成")
                    Caption("若后台晚结算，限制从实际结算时开始；每日限制上限仍有效。")
                    if (activity.engine.hasChildren(task)) Caption("父计划汇总子任务，未完成限制由子任务执行，避免重复限制。")
                    if (task.optString("metric") == "presence" && task.optString("status") in listOf("open", "review"))
                        TextButton(onClick = { penaltySettings = true }) { Text("调整未完成限制") }
                }
            }
        }
        androidx.compose.foundation.layout.Box {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                if (task.optString("note").isNotBlank()) {
                    Section("处理记录"); Text(task.optString("note"))
                }
                if (activity.engine.hasChildren(task)) {
                    Section("子任务")
                    activity.db.all("instances").filter { it.optString("parent") == task.optString("planId") }
                        .forEach { TaskCard(activity, nav, it, false) }
                } else if (task.optString("status") == "open" || task.optString("status") == "review") {
                    Spacer(Modifier.height(12.dp))
                    if (task.optString("status") == "review") {
                        GlassSecondaryButton("补记并核验", Modifier.fillMaxWidth()) { manual = true }
                        GlassSecondaryButton("确认未完成并结算", Modifier.fillMaxWidth(),
                            tone = GlassSecondaryTone.Destructive) { failConfirm = true }
                    }
                    GlassSecondaryButton("记录请假", Modifier.fillMaxWidth()) { leave = true }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    }
    if (manual || leave) ManualDialog(activity, task, leave,
        onSaved = { transitionId?.value = null }, onDismiss = { manual = false; leave = false })
    if (penaltySettings) DwellPenaltyDialog(activity, task) { penaltySettings = false }
    if (failConfirm) ConfirmDialog("确认核验结果？", "按已记录进度结算，未完成限制从现在开始；每日限制上限仍有效。", {
        if (activity.act { activity.engine.confirmFailure(task.optString("id"), System.currentTimeMillis()); activity.engine.tick() }) {
            transitionId?.value = null
            failConfirm = false
        }
    }) { failConfirm = false }
}

@Composable
private fun ManualDialog(activity: ComposeMainActivity, task: JSONObject, leave: Boolean,
                         onSaved: () -> Unit, onDismiss: () -> Unit) {
    var amount by rememberSaveable(task.optString("id"), leave) {
        mutableStateOf(numberText(task.optDouble("manual", 0.0)))
    }
    var reason by rememberSaveable(task.optString("id"), leave) { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (leave) "记录请假" else "记录进度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!leave) OutlinedTextField(amount, { amount = it }, label = {
                    Text(if (task.optString("metric") == "count") "手动累计完成量" else "补记累计量（分钟／项）")
                }, singleLine = true)
                OutlinedTextField(reason, { reason = it }, label = { Text("原因") })
                AnimatedVisibility(error.isNotBlank(),
                    enter = fadeIn(tween(if (LocalMotionEnabled.current) AppMotion.stateChange else 0)),
                    exit = fadeOut(tween(if (LocalMotionEnabled.current) AppMotion.briefExit else 0))) {
                    Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                val n = if (leave) task.optDouble("manual", 0.0) else amount.toDoubleOrNull()
                if (n == null || !n.isFinite() || n < 0) { error = "请输入有效进度"; return@TextButton }
                if (activity.act { activity.engine.manual(task, n, reason, leave) }) {
                    onSaved()
                    onDismiss()
                }
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}

internal fun appNames(activity: ComposeMainActivity, apps: org.json.JSONArray?): String {
    if (apps == null || apps.length() == 0) return "未选择"
    val names = ArrayList<String>()
    for (i in 0 until apps.length()) {
        val pkg = apps.optString(i)
        val label = try {
            activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        names += label
    }
    return names.joinToString("、")
}
