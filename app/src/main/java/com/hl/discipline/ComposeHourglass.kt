package com.hl.discipline

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sqrt

internal enum class HourglassState { Idle, Running, Paused, Completed }

/** Only the visible grains animate; the sand level always comes from the persisted timer. */
@Composable
internal fun HourglassInstrument(
    progress: () -> Float,
    state: HourglassState,
    compact: Boolean,
    motionEnabled: Boolean,
    richMotion: Boolean,
    progressLabel: String,
    modifier: Modifier = Modifier,
    instrumentSize: androidx.compose.ui.unit.Dp = if (compact) 184.dp else 212.dp,
    turnToken: Int = 0
) {
    val palette = AppColors.current
    val turn = rememberHourglassTurn(turnToken, state, motionEnabled)
    var grainPhase by remember { mutableFloatStateOf(0f) }
    val glint = remember { Animatable(-1f) }
    val flowing = state == HourglassState.Running && motionEnabled && richMotion
    LaunchedEffect(flowing) {
        while (flowing) {
            delay(50)
            grainPhase = (grainPhase + .067f) % 1f
        }
    }
    LaunchedEffect(state, motionEnabled) {
        if (state == HourglassState.Completed && motionEnabled) {
            glint.snapTo(0f)
            glint.animateTo(1f, tween(420))
        } else glint.snapTo(-1f)
    }
    Spacer(modifier.size(instrumentSize).graphicsLayer {
        rotationZ = if (motionEnabled) turn.value else 0f
    }.semantics {
        contentDescription = progressLabel
    }.drawWithCache {
        val w = size.width
        val h = size.height
        val dark = palette.dark
        val frame = if (dark) Color(0xFF9BB5B6) else Color(0xFF3A555B)
        val frameShade = if (dark) Color(0xFF597577) else Color(0xFF708C91)
        val glass = if (dark) Color(0xFF315255) else Color(0xFFE7F4F1)
        val glassEdge = if (dark) Color(0xFF7FA4A3) else Color(0xFF9EBBB9)
        val highlight = if (dark) Color(0xFFC8E6E0) else Color.White
        val sand = palette.chartFocus
        val sandShade = if (dark) Color(0xFF5F9E9B) else Color(0xFF4F9F9A)
        val dim = if (state == HourglassState.Paused) .73f else 1f

        val body = Path().apply {
            moveTo(w * .25f, h * .145f)
            lineTo(w * .75f, h * .145f)
            cubicTo(w * .785f, h * .28f, w * .69f, h * .39f, w * .525f, h * .485f)
            lineTo(w * .525f, h * .515f)
            cubicTo(w * .69f, h * .61f, w * .785f, h * .72f, w * .75f, h * .855f)
            lineTo(w * .25f, h * .855f)
            cubicTo(w * .215f, h * .72f, w * .31f, h * .61f, w * .475f, h * .515f)
            lineTo(w * .475f, h * .485f)
            cubicTo(w * .31f, h * .39f, w * .215f, h * .28f, w * .25f, h * .145f)
            close()
        }

        // The interior curves leave a visible glass rim instead of a flat triangular outline.
        val upper = Path().apply {
            moveTo(w * .275f, h * .175f); lineTo(w * .725f, h * .175f)
            cubicTo(w * .73f, h * .29f, w * .64f, h * .40f, w * .51f, h * .475f)
            lineTo(w * .49f, h * .475f)
            cubicTo(w * .36f, h * .40f, w * .27f, h * .29f, w * .275f, h * .175f)
            close()
        }
        val lower = Path().apply {
            moveTo(w * .49f, h * .525f); lineTo(w * .51f, h * .525f)
            cubicTo(w * .64f, h * .60f, w * .73f, h * .71f, w * .725f, h * .825f)
            lineTo(w * .275f, h * .825f)
            cubicTo(w * .27f, h * .71f, w * .36f, h * .60f, w * .49f, h * .525f)
            close()
        }
        val upperSand = Path()
        val pile = Path()
        val bolts = floatArrayOf(.235f, .765f)
        onDrawBehind {
        // Read animated values only in drawing: the timer labels and controls stay out of the frame loop.
        val p = progress().coerceIn(0f, 1f)
        for (i in 0..4) {
            val y = h * (.24f + i * .13f)
            val tick = if (i == 2) .029f else .017f
            drawLine(frameShade.copy(alpha = .52f), Offset(w * .085f, y),
                Offset(w * (.085f + tick), y), 1.dp.toPx(), cap = StrokeCap.Round)
            drawLine(frameShade.copy(alpha = .52f), Offset(w * (.915f - tick), y),
                Offset(w * .915f, y), 1.dp.toPx(), cap = StrokeCap.Round)
        }
        drawPath(body, glass.copy(alpha = if (dark) .65f else .7f))
        if (p < .998f) {
            val level = h * (.195f + .282f * p)
            upperSand.reset()
            upperSand.apply {
                moveTo(0f, level)
                cubicTo(w * .33f, level - h * .004f, w * .64f, level + h * .006f, w, level)
                lineTo(w, h * .49f); lineTo(0f, h * .49f); close()
            }
            clipPath(upper) {
                drawPath(upperSand, Brush.verticalGradient(listOf(sand.copy(alpha = .72f * dim), sandShade.copy(alpha = .90f * dim)), level, h * .49f))
            }
        }
        val pileWidth = w * (.045f + .27f * sqrt(p))
        val pileTop = h * (.823f - .286f * p)
        pile.reset()
        pile.apply {
            moveTo(w * .5f - pileWidth, h * .84f)
            cubicTo(w * .37f, pileTop + h * .015f, w * .44f, pileTop, w * .5f, pileTop)
            cubicTo(w * .56f, pileTop, w * .63f, pileTop + h * .015f, w * .5f + pileWidth, h * .84f)
            close()
        }
        clipPath(lower) {
            drawPath(pile, Brush.verticalGradient(listOf(sand.copy(alpha = .87f * dim), sandShade.copy(alpha = .96f * dim)), pileTop, h * .84f))
            if (p > .03f) drawPath(pile, highlight.copy(alpha = .23f * dim),
                style = Stroke(.7.dp.toPx(), cap = StrokeCap.Round))
        }

        // A small stream and four deterministic grains convey motion without simulating physics.
        if (state == HourglassState.Running && p < .995f) {
            drawLine(sand.copy(alpha = .8f), Offset(w * .5f, h * .485f),
                Offset(w * .5f, pileTop.coerceAtLeast(h * .53f)),
                1.4.dp.toPx(), cap = StrokeCap.Round)
        }
        if ((state == HourglassState.Running || state == HourglassState.Paused) &&
            p < .995f && motionEnabled && richMotion) {
            val span = (pileTop - h * .52f).coerceAtLeast(h * .012f)
            repeat(4) { i ->
                val travel = (grainPhase + i * .23f) % 1f
                val x = w * (.5f + (i - 1.5f) * .007f)
                drawCircle(sandShade.copy(alpha = (.34f + .31f * travel) * dim),
                    radius = (if (i == 2) 1.1.dp else .8.dp).toPx(),
                    center = Offset(x, h * .52f + span * travel))
            }
        }

        // Paired edges and thin inner highlights make the vessel read as glass.
        drawPath(body, glassEdge.copy(alpha = .72f * dim),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawPath(body, highlight.copy(alpha = if (dark) .37f else .78f),
            style = Stroke(.9.dp.toPx(), cap = StrokeCap.Round))
        drawLine(highlight.copy(alpha = .48f), Offset(w * .288f, h * .194f),
            Offset(w * .304f, h * .343f), 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(frameShade.copy(alpha = .36f), Offset(w * .711f, h * .648f),
            Offset(w * .73f, h * .802f), 1.7.dp.toPx(), cap = StrokeCap.Round)
        if (glint.value in 0f..1f) {
            val y = h * (.20f + .61f * glint.value)
            clipPath(body) {
                drawLine(highlight.copy(alpha = .30f), Offset(w * .27f, y),
                    Offset(w * .73f, y - h * .06f), 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        drawRoundRect(frameShade, Offset(w * .21f, h * .09f), Size(w * .58f, h * .06f),
            CornerRadius(5.dp.toPx()))
        drawRoundRect(frame, Offset(w * .21f, h * .085f), Size(w * .58f, h * .052f),
            CornerRadius(5.dp.toPx()))
        drawRoundRect(frameShade, Offset(w * .21f, h * .855f), Size(w * .58f, h * .06f),
            CornerRadius(5.dp.toPx()))
        drawRoundRect(frame, Offset(w * .21f, h * .866f), Size(w * .58f, h * .051f),
            CornerRadius(5.dp.toPx()))
        for (x in bolts) {
            drawCircle(highlight.copy(alpha = .57f), 1.3.dp.toPx(), Offset(w * x, h * .112f))
            drawCircle(highlight.copy(alpha = .57f), 1.3.dp.toPx(), Offset(w * x, h * .891f))
        }
        }
    })
}
