package com.hl.discipline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Presentation only: use the same effective rules and exhaustion checks as Engine. */
internal data class LimitStatus(val packageName: String, val usage: AppUsageLimits.Usage?,
    val exhausted: Boolean, val remaining: Long?, val unlocked: Boolean)
internal data class LimitOverview(val rows: List<LimitStatus>, val access: Boolean, val mode: String) {
    val summary: String get() = when {
        rows.isEmpty() -> "尚未设置应用限额"
        !access -> "${rows.size} 项已启用 · 待授权"
        mode == "未运行" -> "${rows.size} 项已启用 · 拦截未运行"
        rows.any { it.exhausted } -> "${rows.size} 项生效中 · 部分已用尽"
        rows.any { it.usage?.available != true } -> "${rows.size} 项生效中 · 余额待更新"
        rows.any { (it.remaining ?: Long.MAX_VALUE) <= 5 * Rules.MINUTE } -> "${rows.size} 项生效中 · 即将耗尽"
        else -> "${rows.size} 项生效中 · 余额充足"
    }
}

internal fun readLimitOverview(activity: ComposeMainActivity, now: Long): LimitOverview {
    val access = AppUsageLimits.hasAccess(activity)
    val rules = UsageWeekPlans.effectiveLimits(activity.db, now)
    val rows = (0 until rules.length()).mapNotNull { index ->
        val rule = rules.optJSONObject(index) ?: return@mapNotNull null
        val pkg = rule.optString("package")
        val daily = rule.optInt("dailyMinutes")
        val weekly = rule.optInt("weeklyMinutes")
        if ((daily <= 0 && weekly <= 0) || BlockService.protectedApp(activity, pkg)) return@mapNotNull null
        val usage = if (access) AppUsageLimits.read(activity, pkg, now) else null
        val exhausted = usage != null && (AppUsageLimits.dailyBlockEnd(usage, daily, now) > now ||
            AppUsageLimits.weeklyBlockEnd(usage, weekly, now) > now)
        val remaining = if (usage?.available == true) listOfNotNull(
            daily.takeIf { it > 0 }?.let { it * Rules.MINUTE - usage.todayMillis },
            weekly.takeIf { it > 0 }?.let { it * Rules.MINUTE - usage.weekMillis }).minOrNull() else null
        LimitStatus(pkg, usage, exhausted, remaining, activity.db.one("state", "pass:$pkg")?.optLong("until", 0)?.let { it > now } == true)
    }
    return LimitOverview(rows, access, activity.appRestrictionMode())
}

@Composable
internal fun rememberLimitOverview(activity: ComposeMainActivity): State<LimitOverview?> =
    produceState<LimitOverview?>(null, activity.revision) {
        // No system usage queries on the UI thread or while the activity is stopped.
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = withContext(Dispatchers.IO) { readLimitOverview(activity, System.currentTimeMillis()) }
                delay(15_000)
            }
        }
    }

@Composable
internal fun AppLimitStatusBar(activity: ComposeMainActivity, onManage: () -> Unit) {
    val overview by rememberLimitOverview(activity)
    Surface(Modifier.fillMaxWidth().height(48.dp).testTag("today-app-limit-status")
        .quietClickable("管理每日与每周应用限额", onClick = onManage), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f),
        border = BorderStroke(1.dp, AppColors.current.glassBorder)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("应用限额", style = MaterialTheme.typography.labelMedium)
                Text(overview?.summary ?: "正在读取限额状态…", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("管理限额 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A single opaque badge revealed by two gates; mounted/restored state is quiet. */
@Composable
internal fun LimitGateBadge(label: String, engaged: Boolean, modifier: Modifier = Modifier, ready: Boolean = true) {
    val motion = LocalMotionEnabled.current
    val reveal = remember { Animatable(1f) }
    val haptics = LocalHapticFeedback.current
    var previousReady by remember { mutableStateOf(ready) }
    var previousLabel by remember { mutableStateOf(label) }
    var previousEngaged by remember { mutableStateOf(engaged) }
    LaunchedEffect(label, engaged, motion, ready) {
        val wasReady = previousReady
        previousReady = ready
        val changed = previousLabel != label || previousEngaged != engaged
        previousLabel = label; previousEngaged = engaged
        if (motion && ready && wasReady && changed && engaged) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            reveal.snapTo(0f)
            reveal.animateTo(1f, spring(dampingRatio = .75f, stiffness = 500f))
        } else reveal.snapTo(1f)
    }
    val ink = MaterialTheme.colorScheme.primary
    Surface(modifier.drawWithContent {
        val p = if (motion) reveal.value.coerceIn(0f, 1f) else 1f
        val opening = size.width * (1f - p) / 2
        // Draw each half once: no overlapping text, second border or transparent old badge.
        clipRect(right = size.width / 2) {
            withTransform({ translate(-opening, 0f) }) { this@drawWithContent.drawContent() }
        }
        clipRect(left = size.width / 2) {
            withTransform({ translate(opening, 0f) }) { this@drawWithContent.drawContent() }
        }
    }, shape = RoundedCornerShape(8.dp),
        color = if (engaged) ink else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (engaged) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ControlEntryCard(title: String, subtitle: String, icon: Int,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.heightIn(min = 88.dp).quietClickable(title, onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f),
        border = BorderStroke(1.dp, AppColors.current.glassBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge.copy(
                lineBreak = androidx.compose.ui.text.style.LineBreak.Heading.copy(
                    strategy = androidx.compose.ui.text.style.LineBreak.Strategy.Balanced)))
            Text("$subtitle ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
