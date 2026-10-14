package com.hl.discipline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.dp

/** Actual progress is exposed immediately; only its drawn position interpolates. */
@Composable
internal fun ProgressTrack(actual: Float, label: String, modifier: Modifier = Modifier,
                           color: Color = AppColors.current.chartFocus) {
    val bounded = if (actual.isFinite()) actual.coerceIn(0f, 1f) else 0f
    val motion = LocalMotionEnabled.current
    val position = animateFloatAsState(bounded,
        progressSpec(motion),
        label = "真实进度位置")
    val track = MaterialTheme.colorScheme.secondaryContainer
    Canvas(modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(bounded, 0f..1f)
    }) {
        val radius = size.height / 2f
        drawRoundRect(track, cornerRadius = CornerRadius(radius))
        val fraction = (if (motion) position.value else bounded).coerceIn(0f, 1f)
        if (fraction > 0f) drawLine(color, Offset(radius, radius),
            Offset(radius + (size.width - size.height).coerceAtLeast(0f) * fraction, radius),
            strokeWidth = size.height, cap = StrokeCap.Round)
    }
}

/** One stable arrow rotates in place rather than replacing two vector resources. */
@Composable
internal fun DisclosureMark(expanded: Boolean, modifier: Modifier = Modifier) {
    val angle = animateFloatAsState(if (expanded) 180f else 0f,
        tween(if (LocalMotionEnabled.current) AppMotion.expand else 0, easing = AppMotion.easing),
        label = "展开方向")
    Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null,
        modifier = modifier.size(20.dp).graphicsLayer { rotationZ = angle.value })
}

/** Finite check stroke. Already completed items mount at the final shape. */
@Composable
internal fun CompletionStroke(fraction: () -> Float, ink: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Spacer(modifier.clearAndSetSemantics {}.drawWithCache {
        val check = Path().apply {
            moveTo(size.width * .23f, size.height * .51f)
            lineTo(size.width * .43f, size.height * .70f)
            lineTo(size.width * .79f, size.height * .31f)
        }
        val measure = PathMeasure().apply { setPath(check, false) }
        val part = Path()
        onDrawBehind {
            part.reset()
            val p = fraction().coerceIn(0f, 1f)
            if (p > 0f) {
                measure.getSegment(0f, measure.length * p, part, true)
                drawPath(part, ink, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    })
}
