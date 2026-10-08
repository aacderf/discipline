package com.hl.discipline

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalSharedTransitionApi::class)
internal data class ViewMotionScope(
    val shared: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val duration: Int,
    val prefix: String
)

internal val LocalViewMotion = staticCompositionLocalOf<ViewMotionScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ViewTransitionLayout(content: @Composable SharedTransitionScope.() -> Unit) {
    val existing = LocalTaskMotion.current?.shared
    if (existing != null) content(existing) else SharedTransitionLayout { content() }
}

/** Local view changes have their own keys; task navigation retains its existing keys. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.viewShared(key: String, shape: Shape = AppShapes.card): Modifier {
    val scope = LocalViewMotion.current ?: return this
    if (!richMotionEnabled()) return this
    val window = LocalView.current
    var visible by remember(key) { mutableStateOf(true) }
    val measured = this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.bottom > 0 && bounds.top < window.height &&
            bounds.right > 0 && bounds.left < window.width
    }
    if (!visible) return measured
    return with(scope.shared) {
        measured.sharedBounds(
            rememberSharedContentState("${scope.prefix}-$key"), scope.visibility,
            boundsTransform = { _, _ -> tween(scope.duration, easing = AppMotion.easing) },
            // Only the destination material/text is drawn while its boundary travels.
            enter = EnterTransition.None, exit = fadeOut(tween(0)),
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(shape)
        )
    }
}

/** One shaped press response, including a visible keyboard focus state. */
@Composable
internal fun Modifier.quietClickable(
    label: String, shape: Shape = RoundedCornerShape(12.dp), onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val motion = LocalMotionEnabled.current
    val scale by animateFloatAsState(if (pressed && motion) AppMotion.cardPressScale else 1f,
        if (!motion) snap() else if (pressed) tween(AppMotion.pressIn, easing = AppMotion.easing)
        else spring(dampingRatio = AppMotion.pressSpringDamping, stiffness = AppMotion.pressSpringStiffness), label = "局部按压")
    val tint = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(if (pressed) tint.copy(alpha = .09f) else Color.Transparent,
        tween(if (motion) AppMotion.pressIn else 0), label = "局部触摸底色")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape).background(fill)
        .drawWithContent {
            drawContent()
            if (focused) drawRoundRect(tint.copy(alpha = .65f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                style = Stroke(1.dp.toPx()))
        }.clickable(interactionSource = interaction, indication = null,
            role = Role.Button, onClickLabel = label, onClick = onClick)
}

@Composable
internal fun ModeSwitch(label: String, alternate: String, style: TextStyle,
                        modifier: Modifier = Modifier, onClick: () -> Unit) {
    val motion = LocalMotionEnabled.current
    val transition = updateTransition(label, label = "视图标题与标识")
    val rotation = transition.animateFloat(transitionSpec = {
        tween(if (motion) AppMotion.state else 0, easing = AppMotion.easing)
    }, label = "切换标识") { if (it == "今天" || it == "日程") 0f else 180f }
    Row(modifier.heightIn(min = 48.dp).quietClickable("切换到$alternate", onClick = onClick)
        .padding(horizontal = 4.dp).semantics {
            contentDescription = "${label}视图"
            stateDescription = "可切换到$alternate"
        }, verticalAlignment = Alignment.CenterVertically) {
        transition.AnimatedContent(modifier = Modifier.clipToBounds(), transitionSpec = {
            fadeIn(tween(if (motion) AppMotion.state else 0)) +
                slideInVertically(tween(if (motion) AppMotion.state else 0, easing = AppMotion.easing)) { it / 6 } togetherWith
                (fadeOut(tween(if (motion) AppMotion.outgoing else 0)) +
                    slideOutVertically(tween(if (motion) AppMotion.state else 0, easing = AppMotion.easing)) { -it / 6 })
        }) { shown ->
            Text(shown, style = style, modifier = Modifier.clearAndSetSemantics { })
        }
        Spacer(Modifier.width(6.dp))
        val ink = MaterialTheme.colorScheme.primary
        androidx.compose.foundation.Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = rotation.value }
            .clearAndSetSemantics { }) {
            val stroke = 1.6.dp.toPx()
            drawLine(ink, Offset(size.width * .15f, size.height * .35f),
                Offset(size.width * .85f, size.height * .35f), stroke)
            drawLine(ink, Offset(size.width * .65f, size.height * .15f),
                Offset(size.width * .85f, size.height * .35f), stroke)
            drawLine(ink, Offset(size.width * .15f, size.height * .65f),
                Offset(size.width * .85f, size.height * .65f), stroke)
            drawLine(ink, Offset(size.width * .35f, size.height * .85f),
                Offset(size.width * .15f, size.height * .65f), stroke)
        }
    }
}
