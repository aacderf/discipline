package com.hl.discipline

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.*
import androidx.savedstate.*

internal data class CompanionTask(val name: String, val summary: String, val completed: Boolean)
internal data class CompanionLimit(val name: String, val summary: String)
internal data class CompanionSnapshot(
    val phase: String = "", val focusTitle: String = "自由专注", val time: Long = 0,
    val cycle: Int = 1, val forward: Boolean = false,
    val tasks: List<CompanionTask> = emptyList(), val taskCount: Int = 0,
    val limits: List<CompanionLimit> = emptyList(), val detailsLoaded: Boolean = false, val error: String = ""
)

/** A bounded overlay window. It never creates or navigates an Activity. */
internal class FocusCompanionOverlayHost(
    context: Context, maxHeightDp: Float,
    val close: () -> Unit, val timerAction: () -> Unit, val acknowledge: (String) -> Unit
) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    var snapshot by mutableStateOf(CompanionSnapshot())
    private var phaseEvent by mutableStateOf<org.json.JSONObject?>(null)
    var event: org.json.JSONObject?
        get() = phaseEvent
        set(value) { phaseEvent = value; refreshAccessibility() }
    private var accessibilityPending = false
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private val composition = ComposeView(context).also { root ->
        saved.performAttach(); saved.performRestore(null)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        registry.currentState = Lifecycle.State.RESUMED
        root.setContent {
            DisciplineTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalMotionEnabled provides android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    // Transparent gaps keep the background visible; each small plate protects its own text.
                    Column(Modifier.heightIn(max = maxHeightDp.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val notice = event
                        if (notice != null) {
                            CompanionPlate {
                                Text(notice.optString("title"), style = MaterialTheme.typography.titleMedium)
                                Text(notice.optString("message"), style = MaterialTheme.typography.bodyMedium)
                                Primary("知道了", Modifier.fillMaxWidth()) { acknowledge(notice.optString("token")) }
                            }
                        } else {
                            CompanionDashboard(snapshot, close, timerAction)
                        }
                    }
                }
            }
        }
    }
    val view = CompanionRevealLayout(context).apply {
        setViewTreeLifecycleOwner(this@FocusCompanionOverlayHost)
        setViewTreeSavedStateRegistryOwner(this@FocusCompanionOverlayHost)
        addView(composition, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    fun update(value: CompanionSnapshot, mode: AppThemeMode) {
        val changed = snapshot.phase != value.phase || snapshot.tasks != value.tasks || snapshot.limits != value.limits
        snapshot = value; themeMode = mode
        if (changed) refreshAccessibility()
    }
    fun refreshAccessibility() {
        if (accessibilityPending) return
        accessibilityPending = true
        composition.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                composition.viewTreeObserver.removeOnPreDrawListener(this)
                accessibilityPending = false
                // Notify after Compose has committed the new action and content to this native overlay window.
                val change = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                change.contentChangeTypes = android.view.accessibility.AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                view.sendAccessibilityEventUnchecked(change)
                return true
            }
        })
        composition.invalidate()
    }
    fun dispose() { registry.currentState = Lifecycle.State.DESTROYED }
}

@Composable
private fun CompanionPlate(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = AppColors.current
    Surface(modifier.widthIn(max = 228.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (palette.dark) .84f else .86f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .65f)), tonalElevation = 0.dp) {
        Column(Modifier.padding(10.dp).animateContentSize(tween(
            if (LocalMotionEnabled.current) 220 else 0, easing = AppMotion.easing)),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun CompanionCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = if (AppColors.current.dark) Color(0xFFB7C8C2) else Color(0xFF46534F),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CompanionAction(description: String, icon: Int, onClick: () -> Unit) {
    // A new timer action must also replace the virtual accessibility action, not only its icon.
    key(description) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && LocalMotionEnabled.current) .97f else 1f,
        tween(if (LocalMotionEnabled.current) AppMotion.pressIn else 0, easing = AppMotion.easing), label = "悬浮操作")
    Box(Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(CircleShape).clickable(interactionSource = interaction, indication = null,
            role = Role.Button, onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).background(AppColors.current.iconPressed.copy(alpha = if (pressed) 1f else .6f),
            CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp))
        }
    }
    }
}

@Composable
private fun CompanionDashboard(snapshot: CompanionSnapshot, close: () -> Unit, timerAction: () -> Unit) {
    var allTasks by remember { mutableStateOf(false) }
    var allLimits by remember { mutableStateOf(false) }
    CompanionPlate {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("此刻", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(snapshot.focusTitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CompanionAction("收起悬浮面板", R.drawable.ic_close, close)
        }
        if (snapshot.phase.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(countdown(snapshot.time), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = BodyFontFamily, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"))
                    CompanionCaption("${companionPhase(snapshot.phase)} · 第 ${snapshot.cycle} 轮" +
                        if (snapshot.forward) " · 已专注" else " · 剩余")
                }
                val running = snapshot.phase == "work" || snapshot.phase == "break"
                CompanionAction(when (snapshot.phase) {
                    "work" -> "暂停专注"; "break" -> "暂停休息"; "paused" -> "继续计时"
                    else -> "开始下一轮"
                }, if (running) R.drawable.ic_pause else R.drawable.ic_play, timerAction)
            }
        } else CompanionCaption("尚未开始专注")
    }
    CompanionPlate(Modifier.pressableCard(RoundedCornerShape(16.dp), "展开或收起今日计划") { allTasks = !allTasks }
        .semantics { stateDescription = if (allTasks) "已展开" else "已收起" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("今日计划", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompanionCaption(snapshot.taskCount.toString())
            Spacer(Modifier.width(6.dp)); DisclosureMark(allTasks)
        }
        if (!snapshot.detailsLoaded) CompanionCaption("正在读取…")
        else if (snapshot.tasks.isEmpty()) CompanionCaption("今天没有待办计划")
        (if (allTasks) snapshot.tasks else snapshot.tasks.take(1)).forEach { task ->
            Text((if (task.completed) "✓ " else "") + task.name, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompanionCaption(task.summary)
        }
        val shown = if (allTasks) snapshot.tasks.size else snapshot.tasks.take(1).size
        if (snapshot.detailsLoaded && snapshot.taskCount > shown) CompanionCaption("另有 ${snapshot.taskCount - shown} 项 · 点按展开")
    }
    CompanionPlate(Modifier.pressableCard(RoundedCornerShape(16.dp), "展开或收起应用限额") { allLimits = !allLimits }
        .semantics { stateDescription = if (allLimits) "已展开" else "已收起" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("应用限额", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            DisclosureMark(allLimits)
        }
        if (!snapshot.detailsLoaded) CompanionCaption("正在读取…")
        else if (snapshot.limits.isEmpty()) CompanionCaption("尚未设置每日或每周限额")
        (if (allLimits) snapshot.limits else snapshot.limits.take(1)).forEach { limit ->
            Text(limit.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompanionCaption(limit.summary)
        }
        if (!allLimits && snapshot.limits.size > 1) CompanionCaption("另有 ${snapshot.limits.size - 1} 项 · 点按展开")
    }
    if (snapshot.error.isNotBlank()) CompanionPlate {
        Text(snapshot.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

internal fun companionPhase(phase: String): String = when (phase) {
    "work" -> "专注中"; "break" -> "休息中"; "paused" -> "已暂停"; else -> "准备下一轮"
}

@Composable
internal fun CompanionSettings(activity: ComposeMainActivity, compact: Boolean = false) {
    val revision = activity.revision
    val enabled = remember(revision) { activity.db.settings().optBoolean("companionEnabled") }
    val access = android.provider.Settings.canDrawOverlays(activity)
    var detailsOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("悬浮球", style = MaterialTheme.typography.bodyLarge)
            if (!compact) Caption(if (enabled && !access) "需要系统悬浮窗授权" else "点按查看计划、计时与应用余额")
        }
        if (compact && enabled) TextButton(onClick = { detailsOpen = !detailsOpen }) { Text(if (detailsOpen) "收起" else "说明") }
        GlassSwitch(checked = enabled, modifier = Modifier.semantics {
            contentDescription = "启用悬浮球"
        }, onCheckedChange = { value ->
            activity.act {
                val settings = activity.db.settings()
                Store.set(settings, "companionEnabled", value)
                activity.db.put("state", settings)
            }
            if (value && !access) AndroidCompat.openSettings(activity, android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${activity.packageName}")))
        })
    }
    if (enabled && (!compact || detailsOpen)) {
        val problem = AndroidCompat.serviceProblem(activity, FocusCompanionService::class.java)
        if (problem.isNotBlank()) Caption(problem)
        Caption("双击完全隐藏，不占用屏幕触控区域。点常驻通知恢复；也可在系统快捷开关中添加「自律悬浮球」。")
        if (!access) Secondary("授权悬浮窗") { AndroidCompat.openSettings(activity, android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${activity.packageName}"))) }
        else TextButton(onClick = { FocusCompanionService.restore(activity) }) { Text("显示悬浮球") }
    }
}
