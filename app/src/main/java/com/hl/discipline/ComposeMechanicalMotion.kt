package com.hl.discipline

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class CompletionStamp(completed: Boolean) {
    val scale = Animatable(1f)
    val stroke = Animatable(if (completed) 1f else 0f)
    val glyphScale = Animatable(1f)
}

/** Only a successful input increments event. Restored and externally completed rows are static. */
@Composable
internal fun rememberCompletionStamp(event: Int, completed: Boolean, motion: Boolean): CompletionStamp {
    val stamp = remember { CompletionStamp(completed) }
    var seen by remember { mutableIntStateOf(event) }
    LaunchedEffect(event, completed, motion) {
        val changed = seen != event
        seen = event
        if (changed && completed && motion) {
            stamp.scale.snapTo(.97f); stamp.stroke.snapTo(0f); stamp.glyphScale.snapTo(1.2f)
            coroutineScope {
                launch { stamp.scale.animateTo(1f, spring(dampingRatio = .85f, stiffness = 520f)) }
                launch { stamp.glyphScale.animateTo(1f, spring(dampingRatio = .85f, stiffness = 520f)) }
                stamp.stroke.animateTo(1f, tween(220, easing = AppMotion.easing))
            }
        } else {
            stamp.scale.snapTo(1f); stamp.glyphScale.snapTo(1f)
            stamp.stroke.snapTo(if (completed) 1f else 0f)
        }
    }
    return stamp
}

internal fun Modifier.completionScale(stamp: CompletionStamp, motion: Boolean): Modifier = graphicsLayer {
    scaleX = if (motion) stamp.scale.value else 1f
    scaleY = scaleX
}

/** Use actual text line bounds, never the full weighted column width. */
internal fun Modifier.completionInk(layout: () -> TextLayoutResult?, progress: () -> Float,
    ink: Color): Modifier = drawWithContent {
    drawContent()
    val text = layout() ?: return@drawWithContent
    val fraction = progress().coerceIn(0f, 1f)
    val widths = (0 until text.lineCount).map { text.getLineRight(it) - text.getLineLeft(it) }
    var remaining = widths.sum() * fraction
    widths.forEachIndexed { index, width ->
        val amount = remaining.coerceIn(0f, width.coerceAtLeast(0f))
        if (amount > 0f) {
            val y = (text.getLineTop(index) + text.getLineBottom(index)) / 2
            drawLine(ink, Offset(text.getLineLeft(index), y), Offset(text.getLineLeft(index) + amount, y),
                strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
        }
        remaining -= width
    }
}

@Composable
internal fun rememberHourglassTurn(token: Int, state: HourglassState, motion: Boolean): Animatable<Float, AnimationVector1D> {
    val turn = remember { Animatable(0f) }
    var seen by remember { mutableIntStateOf(token) }
    LaunchedEffect(token, state, motion) {
        val changed = seen != token
        seen = token
        if (changed && motion && state == HourglassState.Running) {
            // Symmetric reset keeps the real sand level upright; pause immediately settles it.
            turn.animateTo(180f, spring(dampingRatio = .72f, stiffness = 420f))
            turn.animateTo(0f, spring(dampingRatio = .72f, stiffness = 420f))
        } else turn.snapTo(0f)
    }
    return turn
}

/** UI observer identity excludes pause/resume checkpoints. */
internal data class FocusTurnIdentity(val session: Long, val round: Int)

@Composable
internal fun rememberFocusTurn(identity: FocusTurnIdentity?, phase: String, motion: Boolean,
    onRound: () -> Unit): Int {
    var observed by remember { mutableStateOf(identity) }
    var token by remember { mutableIntStateOf(0) }
    var previousPhase by remember { mutableStateOf(phase) }
    LaunchedEffect(identity, phase, motion) {
        val priorPhase = previousPhase
        previousPhase = phase
        if (identity == null) observed = null
        else if (phase == "work" && identity != observed) {
            val newRound = observed != null && observed?.session == identity.session
            observed = identity
            if (motion) { token++; if (newRound && priorPhase == "break") onRound() }
        }
    }
    return token
}
