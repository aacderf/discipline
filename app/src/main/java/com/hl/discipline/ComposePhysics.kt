package com.hl.discipline

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntSize

internal fun expansionSpec(motion: Boolean): FiniteAnimationSpec<IntSize> =
    if (motion) spring(dampingRatio = .85f, stiffness = 520f) else snap()

internal fun progressSpec(motion: Boolean): AnimationSpec<Float> =
    if (motion) spring(dampingRatio = .88f, stiffness = 400f) else snap()

internal fun precisionNumberTransform(motion: Boolean): ContentTransform =
    if (!motion) EnterTransition.None togetherWith ExitTransition.None
    else (slideInVertically(tween(160, delayMillis = 40, easing = AppMotion.easing)) { it / 2 } +
        fadeIn(tween(160, delayMillis = 40, easing = AppMotion.easing))) togetherWith
        (slideOutVertically(tween(80, easing = AppMotion.easing)) { -it / 2 } + fadeOut(tween(80)))

/** Spring itself has no delayMillis. Shift its finite clock instead of replacing its physics. */
internal class DelayedSpring<T>(private val delayMillis: Int, private val spring: FiniteAnimationSpec<T>) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> {
        val delegate = spring.vectorize(converter)
        val delayNanos = delayMillis * 1_000_000L
        return object : VectorizedFiniteAnimationSpec<V> {
            override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V) =
                delayNanos + delegate.getDurationNanos(initialValue, targetValue, initialVelocity)
            override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                if (playTimeNanos < delayNanos) initialValue else
                    delegate.getValueFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)
            override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
                if (playTimeNanos < delayNanos) initialVelocity else
                    delegate.getVelocityFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)
        }
    }
}
