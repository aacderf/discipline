package com.hl.discipline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A 220ms drawing overlay, never a change to the stored polygon or its native hit targets. */
@Composable
internal fun BoundarySnapFeedback(points: List<Offset>, token: Int, motion: Boolean, modifier: Modifier = Modifier) {
    val radius = remember { Animatable(1f) }
    val opacity = remember { Animatable(0f) }
    var seen by remember { mutableIntStateOf(token) }
    val path = remember(points) { Path().apply {
        points.firstOrNull()?.let { moveTo(it.x, it.y) }
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    } }
    LaunchedEffect(token, points, motion) {
        val changed = seen != token
        seen = token
        if (changed && motion && points.size >= 3) {
            radius.snapTo(0f); opacity.snapTo(1f)
            coroutineScope {
                val growth = launch { radius.animateTo(1f, spring(dampingRatio = .65f, stiffness = 520f)) }
                opacity.animateTo(0f, tween(220, easing = AppMotion.easing))
                growth.cancel()
            }
            radius.snapTo(1f)
        } else { radius.snapTo(1f); opacity.snapTo(0f) }
    }
    val ink = AppColors.current.chartFocus
    Canvas(modifier.clearAndSetSemantics {}) {
        // Underlying native polygon/handles already show the exact final state when motion is off.
        val alpha = if (motion) opacity.value else 0f
        if (alpha > 0f && points.size >= 3) {
            drawPath(path, ink.copy(alpha = alpha), style = Stroke(
                width = (1f + alpha * 1.5f).dp.toPx(), cap = StrokeCap.Round))
            points.forEach { point -> drawCircle(ink.copy(alpha = alpha),
                radius = 10.dp.toPx() * radius.value.coerceAtLeast(0f), center = point,
                style = Stroke(width = 1.5.dp.toPx())) }
        }
    }
}
