package com.hl.discipline

import android.app.ActivityManager
import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import org.json.JSONObject

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
internal data class TaskMotionScope(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)
internal val LocalTaskMotion = staticCompositionLocalOf<TaskMotionScope?> { null }
internal val LocalTaskTransitionId = staticCompositionLocalOf<MutableState<String?>?> { null }
internal val LocalMotionEnabled = staticCompositionLocalOf { ValueAnimator.areAnimatorsEnabled() }

@Composable
internal fun richMotionEnabled(): Boolean {
    val context = LocalContext.current
    val lowRam = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    return LocalMotionEnabled.current && !lowRam
}

@Composable
internal fun Modifier.pressableCard(shape: Shape = AppShapes.card,
                                    onClickLabel: String? = null, clipContent: Boolean = true,
                                    onClick: () -> Unit): Modifier {
    val motion = LocalMotionEnabled.current
    val richMotion = richMotionEnabled()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    var touch by remember { mutableStateOf(Offset.Zero) }
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { AppMotion.magneticTravelDp.dp.toPx() }
    val scale by animateFloatAsState(
        if (pressed && motion) AppMotion.cardPressScale else 1f,
        animationSpec = when {
            !motion -> snap()
            pressed -> tween(AppMotion.pressIn, easing = AppMotion.easing)
            else -> spring(dampingRatio = AppMotion.pressSpringDamping,
                stiffness = AppMotion.pressSpringStiffness)
        }, label = "卡片按压")
    val x by animateFloatAsState(if (pressed && richMotion) touch.x else 0f,
        animationSpec = if (!motion) snap() else if (pressed) snap() else spring(
            dampingRatio = AppMotion.pressSpringDamping, stiffness = AppMotion.pressSpringStiffness),
        label = "卡片横向归位")
    val y by animateFloatAsState(if (pressed && richMotion) touch.y else 0f,
        animationSpec = if (!motion) snap() else if (pressed) snap() else spring(
            dampingRatio = AppMotion.pressSpringDamping, stiffness = AppMotion.pressSpringStiffness),
        label = "卡片纵向归位")
    val ink = MaterialTheme.colorScheme.primary
    return this.graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = if (pressed && richMotion) touch.x else x
            translationY = if (pressed && richMotion) touch.y else y
            this.shape = shape; clip = clipContent
        }.pointerInput(richMotion) {
            if (richMotion) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointerId = down.id
                touch = Offset(
                    ((down.position.x / size.width.coerceAtLeast(1)) - .5f) * 2f * travelPx,
                    ((down.position.y / size.height.coerceAtLeast(1)) - .5f) * 2f * travelPx)
                var stillPressed: Boolean
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    stillPressed = change?.pressed == true
                    if (change != null && change.pressed) touch = Offset(
                        ((change.position.x / size.width.coerceAtLeast(1)) - .5f).coerceIn(-.5f, .5f) * 2f * travelPx,
                        ((change.position.y / size.height.coerceAtLeast(1)) - .5f).coerceIn(-.5f, .5f) * 2f * travelPx)
                } while (stillPressed)
            }
        }.drawWithContent {
            drawContent()
            if (pressed) {
                // The inner content of a padded card must not be clipped a second time.
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, ink.copy(alpha = .055f))
            }
            if (focused) drawRoundRect(ink.copy(alpha = .65f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style = Stroke(1.dp.toPx()))
        }.clickable(interactionSource = interaction, indication = null,
            onClickLabel = onClickLabel, onClick = onClick)
}

@Composable
internal fun ScrollChrome(lifted: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotionEnabled.current
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (lifted) scheme.surface.copy(alpha = .95f) else scheme.background,
        tween(if (motion) AppMotion.scrollBar else 0, easing = AppMotion.easing), label = "顶部栏底色")
    val edge by animateColorAsState(
        if (lifted) scheme.outline.copy(alpha = .65f) else Color.Transparent,
        tween(if (motion) AppMotion.scrollBar else 0, easing = AppMotion.easing), label = "顶部栏边界")
    Box(Modifier.fillMaxWidth().background(fill).drawBehind {
        drawLine(edge, Offset(0f, size.height - 1.dp.toPx()),
            Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 1.dp.toPx())
    }) { content() }
}

private val taskBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = AppMotion.taskShared, easing = AppMotion.easing)
}

@Composable
internal fun ScreenExitGuard(leaving: Boolean, modifier: Modifier = Modifier.fillMaxSize(),
                             content: @Composable () -> Unit) {
    Box(modifier.then(if (leaving) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
        content()
        if (leaving) Box(Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        })
    }
}

@Composable
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
internal fun Modifier.taskShared(key: String, enabled: Boolean, bounds: Boolean = false): Modifier {
    val scope = LocalTaskMotion.current ?: return this
    if (!enabled) return this
    return with(scope.shared) {
        if (bounds) this@taskShared.sharedBounds(rememberSharedContentState(key), scope.visibility,
            boundsTransform = taskBoundsTransform,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.fadeOut(tween(0)),
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(AppShapes.card))
        else this@taskShared.sharedElement(rememberSharedContentState(key), scope.visibility,
            boundsTransform = taskBoundsTransform)
    }
}

/** Unmatched controls keep their layout slot but don't collide with a travelling shared action. */
@Composable
internal fun Modifier.taskTransitionAccessory(shared: Boolean): Modifier {
    val scope = LocalTaskMotion.current
    // Seeking a predictive return can be paused at a fraction while isTransitionActive is false.
    val travelling = shared && scope != null && (scope.shared.isTransitionActive ||
        scope.visibility.transition.currentState != scope.visibility.transition.targetState)
    val alpha by animateFloatAsState(if (travelling) 0f else 1f,
        tween(if (travelling || !LocalMotionEnabled.current) 0 else AppMotion.stateChange,
            easing = AppMotion.easing), label = "非共享任务信息")
    return this.graphicsLayer { this.alpha = alpha }
        .then(if (travelling) Modifier.semantics { hideFromAccessibility() } else Modifier)
        .then(if (travelling) Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    .changes.forEach { it.consume() }
            }
        } else Modifier)
}

internal data class TaskVisual(val id: String, val name: String, val metric: String, val action: String?)
internal fun JSONObject.visual(): TaskVisual {
    val metric = optString("metric")
    val action = if (optString("status") != "open") null else when (metric) {
        "check" -> "标记完成"
        "count" -> "记录数量"
        "focus", "placeFocus" -> "开始专注"
        else -> null
    }
    return TaskVisual(optString("id"), optString("name"), metric, action)
}

@Composable
internal fun TaskTypeGlyph(metric: String, modifier: Modifier = Modifier, size: Dp = 56.dp,
                           completed: Boolean = false) {
    val icon = when (metric) {
        "check" -> R.drawable.ic_task_list
        "count" -> R.drawable.ic_target
        "presence" -> R.drawable.ic_map_pin
        "placeFocus" -> R.drawable.ic_place_focus
        else -> R.drawable.ic_clock
    }
    val stroke = animateFloatAsState(if (completed) 1f else 0f,
        tween(if (LocalMotionEnabled.current) AppMotion.state else 0, easing = AppMotion.easing),
        label = "任务完成标记")
    val ink = MaterialTheme.colorScheme.primary
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(Modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = ink,
                modifier = Modifier.size(size * .54f).graphicsLayer { alpha = 1f - stroke.value })
            CompletionStroke({ stroke.value }, ink, Modifier.size(size * .62f))
        }
    }
}

@Composable
internal fun GlassTaskPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = AppColors.current
    // Keep one opaque boundary on every ABI. Refraction can fold pale edges over
    // controls and progress tracks on a real device, even when x86 looks correct.
    Surface(modifier.fillMaxWidth(), shape = AppShapes.glass,
        color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder)) {
        Column(Modifier.padding(AppSpacing.md + 2.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs + 2.dp), content = content)
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
internal fun MorphIcon(active: Boolean, forward: Int, backward: Int,
                       description: String, motionEnabled: Boolean, modifier: Modifier = Modifier) {
    val image = AnimatedImageVector.animatedVectorResource(if (active) forward else backward)
    var previous by remember { mutableStateOf<Boolean?>(null) }
    var atEnd by remember { mutableStateOf(true) }
    LaunchedEffect(active, motionEnabled) {
        if (previous != null && previous != active && motionEnabled) {
            atEnd = false
            withFrameNanos { }
        }
        atEnd = true
        previous = active
    }
    Icon(rememberAnimatedVectorPainter(image, atEnd),
        contentDescription = description.ifEmpty { null }, modifier = modifier)
}
