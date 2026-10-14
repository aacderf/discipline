package com.hl.discipline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.widget.FrameLayout

/** Fixed, bounded viewport; the live child slides upward without scaling its text. */
internal class CompanionRevealLayout(context: Context) : FrameLayout(context) {
    private val path = Path()
    private val region = RectF()
    private val radius = 16f * resources.displayMetrics.density
    private val travel = 28f * resources.displayMetrics.density
    var acceptsInput = true
        set(value) { field = value; updateAccessibilityVisibility() }
    var reveal = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            alpha = field
            getChildAt(0)?.translationY = travel * (1f - field)
            updateAccessibilityVisibility()
            invalidate()
        }
    init { alpha = 0f; clipChildren = true; clipToPadding = true; updateAccessibilityVisibility() }

    private fun updateAccessibilityVisibility() {
        val visibility = if (acceptsInput && reveal > 0f) IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (importantForAccessibility != visibility) importantForAccessibility = visibility
    }

    private fun updateMask() {
        if (width == 0 || height == 0) return
        region.set(0f, 0f, width.toFloat(), height.toFloat())
        path.rewind()
        path.addRoundRect(region, radius, radius, Path.Direction.CW)
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateMask(); getChildAt(0)?.translationY = travel * (1f - reveal)
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (reveal == 0f) return
        val saved = canvas.save()
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsInput || event.actionMasked == MotionEvent.ACTION_DOWN &&
            !region.contains(event.x, event.y)) return true
        return super.dispatchTouchEvent(event)
    }
}
