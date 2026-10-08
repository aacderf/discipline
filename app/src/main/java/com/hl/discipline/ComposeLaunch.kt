package com.hl.discipline

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/** System splash bitmaps are cropped to the safe area; view width is not the vector's viewport. */
internal fun nativeLaunchMarkWidth(view: android.view.View): Float {
    val image = view as? android.widget.ImageView
    val drawable = image?.drawable as? android.graphics.drawable.BitmapDrawable
    if (drawable != null) {
        val bitmap = drawable.bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var first = bitmap.height
        var last = -1
        pixels.forEachIndexed { index, pixel ->
            if ((pixel ushr 24) > 127) {
                val row = index / bitmap.width
                first = minOf(first, row)
                last = maxOf(last, row)
            }
        }
        if (last >= first && (last - first + 1) < bitmap.height) {
            val matrix = FloatArray(9)
            image.imageMatrix.getValues(matrix)
            val height = (last - first + 1f) * drawable.bounds.height() / bitmap.height *
                kotlin.math.abs(matrix[android.graphics.Matrix.MSCALE_Y])
            // Foreground y bounds including the 4.5-unit stroke: (78.25 - 33.75) * 1.16.
            return height * 108f / (44.5f * 1.16f)
        }
    }
    return view.width * 1.5f // Android's two-thirds safe-area mask for this static launch icon.
}

/** One finite timeline. Live content is already laid out underneath the reveal, never a screenshot. */
@Composable
internal fun LaunchPresentation(
    enabled: Boolean, ready: Boolean, originInWindow: Offset?, iconWidthPx: Float,
    onFinished: () -> Unit, content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val accessibility = context.getSystemService(AccessibilityManager::class.java)
    val motion = LocalMotionEnabled.current && accessibility?.isTouchExplorationEnabled != true
    var running by rememberSaveable { mutableStateOf(enabled && motion) }
    val playing = running && enabled && motion
    val progress = remember { Animatable(if (running) 0f else 1f) }
    val finished by rememberUpdatedState(onFinished)
    fun finish() { running = false; finished() }
    LaunchedEffect(ready, motion, running, enabled) {
        if (!running) return@LaunchedEffect
        if (!enabled || !motion) { progress.snapTo(1f); finish() }
        else if (ready) {
            progress.animateTo(1f, tween(AppMotion.launchScene, easing = LinearEasing))
            finish()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val reveal = launchSegment(progress.value, .44f, 1f)
            translationY = if (playing) (1f - reveal) * 9.dp.toPx() else 0f
        }.then(if (playing) Modifier.semantics { hideFromAccessibility() } else Modifier)) { content() }
        if (playing) LaunchScene({ progress.value }, originInWindow, iconWidthPx,
            Modifier.fillMaxSize().testTag("launch-scene").pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                    finish()
                    // The skip tap cannot accidentally activate a control hidden under the scene.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
    }
}

private fun launchSegment(time: Float, start: Float, end: Float): Float =
    AppMotion.easing.transform(((time - start) / (end - start)).coerceIn(0f, 1f))

/** Clock, two task/data rails and a moving reveal. All animated reads stay in the draw phase. */
@Composable
internal fun LaunchScene(progress: () -> Float, originInWindow: Offset?, iconWidthPx: Float,
                         modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    Spacer(modifier.onGloballyPositioned { windowOrigin = it.positionInWindow() }
        .semantics { hideFromAccessibility() }.drawWithCache {
            val center = originInWindow?.minus(windowOrigin) ?: Offset(size.width / 2, size.height / 2)
            val iconScale = (if (iconWidthPx > 0f) iconWidthPx else 288.dp.toPx()) * 1.16f / 108f
            val radius = 27f * iconScale
            val farthest = listOf(Offset.Zero, Offset(size.width, 0f),
                Offset(0f, size.height), Offset(size.width, size.height))
                .maxOf { hypot((it.x - center.x).toDouble(), (it.y - center.y).toDouble()).toFloat() }
            val clock = Path().apply {
                moveTo(55f, 36f); cubicTo(44f, 36f, 35f, 45f, 35f, 56f)
                cubicTo(35f, 67f, 44f, 76f, 55f, 76f); cubicTo(62f, 76f, 68f, 73f, 72f, 67f)
            }
            val hand = Path().apply { moveTo(55f, 44f); lineTo(55f, 56f); lineTo(63f, 61f) }
            val opening = Path()
            val railWidth = 49.dp.toPx()
            val railHeight = 38.dp.toPx()
            val railCorner = CornerRadius(10.dp.toPx())
            val line = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                val time = progress().coerceIn(0f, 1f)
                val reveal = launchSegment(time, .44f, 1f)
                val instrument = 1f - launchSegment(time, .42f, .68f)
                if (reveal == 0f) drawRect(scheme.background) else {
                    opening.reset()
                    opening.addOval(Rect(center - Offset(farthest * reveal, farthest * reveal),
                        center + Offset(farthest * reveal, farthest * reveal)))
                    clipPath(opening, ClipOp.Difference) { drawRect(scheme.background) }
                }
                val build = launchSegment(time, .02f, .48f)
                val orbitAlpha = instrument * build
                val logoCenter = center + Offset(0f, -18.dp.toPx() * reveal)
                val arcRadius = radius * (1f + .11f * build)
                drawArc(scheme.primary.copy(alpha = .22f * orbitAlpha), -90f, 305f * build, false,
                    logoCenter - Offset(arcRadius, arcRadius),
                    androidx.compose.ui.geometry.Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
                repeat(12) { tick ->
                    rotate(tick * 30f, logoCenter) {
                        drawLine(scheme.primary.copy(alpha = .35f * orbitAlpha),
                            logoCenter + Offset(0f, -arcRadius - 5.dp.toPx()),
                            logoCenter + Offset(0f, -arcRadius - (if (tick % 3 == 0) 10 else 7).dp.toPx()),
                            strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                // The planning and data motifs unfold once and return to the clock as the page opens.
                val spread = sin(build * PI).toFloat()
                repeat(2) { side ->
                    val direction = if (side == 0) -1 else 1
                    val anchor = logoCenter + Offset(direction * (radius + railWidth * .55f +
                        spread * 10.dp.toPx()), if (side == 0) -8.dp.toPx() else 14.dp.toPx())
                    val top = anchor - Offset(railWidth / 2, railHeight / 2)
                    val alpha = orbitAlpha * .85f
                    drawRoundRect(scheme.surface.copy(alpha = alpha), top,
                        androidx.compose.ui.geometry.Size(railWidth, railHeight), railCorner)
                    drawRoundRect(scheme.outline.copy(alpha = alpha), top,
                        androidx.compose.ui.geometry.Size(railWidth, railHeight), railCorner, style = line)
                    if (side == 0) repeat(3) { row ->
                        val y = top.y + (11 + row * 8).dp.toPx()
                        drawCircle(scheme.primary.copy(alpha = alpha), 1.6.dp.toPx(), Offset(top.x + 10.dp.toPx(), y))
                        drawLine(scheme.onSurfaceVariant.copy(alpha = alpha * .7f),
                            Offset(top.x + 17.dp.toPx(), y), Offset(top.x + (36 - row * 4).dp.toPx(), y),
                            1.4.dp.toPx(), StrokeCap.Round)
                    } else repeat(3) { bar ->
                        val x = top.x + (13 + bar * 11).dp.toPx()
                        drawLine(scheme.primary.copy(alpha = alpha), Offset(x, top.y + 28.dp.toPx()),
                            Offset(x, top.y + (22 - bar * 6 * build).dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                    }
                }
                withTransform({
                    translate(logoCenter.x, logoCenter.y)
                    scale(iconScale * (1f - .12f * reveal), iconScale * (1f - .12f * reveal), Offset.Zero)
                    translate(-54f, -54f)
                }) {
                    drawPath(clock, scheme.primary.copy(alpha = instrument), style = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    rotate(32f * sin(build * PI).toFloat(), Offset(55f, 56f)) {
                        drawPath(hand, scheme.onBackground.copy(alpha = instrument), style = Stroke(4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawCircle(scheme.primary.copy(alpha = instrument), 4f,
                        Offset(71f + 3f * sin(build * PI).toFloat(), 38f))
                }
            }
        })
}
