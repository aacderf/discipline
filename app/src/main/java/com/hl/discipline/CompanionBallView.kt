package com.hl.discipline

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Only this small window receives gestures; no transparent screen-sized interceptor. */
internal class CompanionBallView(
    context: Context, private val onTapStart: () -> Unit,
    private val onTap: () -> Unit, private val onHide: () -> Unit,
    private val onDrag: (Float, Float) -> Unit, private val onSnap: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private var dark = false
    private var phase = ""
    private var fraction = 0f
    private var expanded = false
    private var expansion = 0f
    private var pressure = 0f
    private var pressAnimator: ValueAnimator? = null
    private var expansionAnimator: ValueAnimator? = null
    private var progressAnimator: ValueAnimator? = null
    private var targetFraction = 0f
    private var surface: Shader? = null
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    private val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!dragging) performClick()
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            onHide(); return true
        }
    })
    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "自律悬浮球，点按查看，双击隐藏，拖动调整位置"
    }
    override fun performClick(): Boolean { super.performClick(); onTap(); return true }

    fun updateStatus(isDark: Boolean, progress: Float, state: String) {
        if (dark != isDark) { dark = isDark; cacheSurface() }
        val next = progress.coerceIn(0f, 1f)
        if (phase != state || targetFraction != next) {
            progressAnimator?.cancel()
            val running = state == "work" || state == "break"
            if (running && phase == state && abs(next - fraction) < .2f && motion()) {
                progressAnimator = ValueAnimator.ofFloat(fraction, next).apply {
                    duration = 300; interpolator = easing
                    addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else fraction = next
            targetFraction = next
        }
        phase = state
        invalidate()
    }

    fun setExpanded(value: Boolean, animate: Boolean = true) {
        if (expanded == value) return
        expanded = value
        contentDescription = if (value) "自律悬浮球，点按收起面板，双击隐藏" else
            "自律悬浮球，点按查看，双击隐藏，拖动调整位置"
        expansionAnimator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || !motion()) { expansion = target; invalidate(); return }
        expansionAnimator = ValueAnimator.ofFloat(expansion, target).apply {
            duration = 220; interpolator = easing
            addUpdateListener { expansion = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun motion() = ValueAnimator.areAnimatorsEnabled() && !lowRam
    private fun press(value: Boolean) {
        pressAnimator?.cancel()
        val target = if (value) 1f else 0f
        if (!motion()) { pressure = target; invalidate(); return }
        pressAnimator = ValueAnimator.ofFloat(pressure, target).apply {
            duration = if (value) 90 else 180; interpolator = easing
            addUpdateListener { pressure = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { cacheSurface() }
    private fun cacheSurface() {
        val y = height / 2f; val radius = 18 * density
        surface = LinearGradient(0f, y - radius, width.toFloat(), y + radius,
            if (dark) intArrayOf(0xFF3D625D.toInt(), 0xFF284541.toInt()) else
                intArrayOf(0xFFF1FAF7.toInt(), 0xFFC3E7DF.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDetachedFromWindow() {
        pressAnimator?.cancel(); expansionAnimator?.cancel(); progressAnimator?.cancel()
        animate().cancel()
        super.onDetachedFromWindow()
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "隐藏悬浮球"))
    }
    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) { onHide(); return true }
        if (action == AccessibilityNodeInfo.ACTION_CLICK) return performClick()
        return super.performAccessibilityAction(action, args)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTapStart()
                startX = event.rawX; startY = event.rawY; lastX = startX; lastY = startY
                dragging = false
                press(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - startX) + abs(event.rawY - startY) > slop) dragging = true
                if (dragging) {
                    onDrag(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                press(false)
                if (dragging) {
                    val cancel = MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL }
                    detector.onTouchEvent(cancel); cancel.recycle(); onSnap(); return true
                }
            }
        }
        if (!dragging) detector.onTouchEvent(event)
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = if (dark) 0xFFA9DCD1.toInt() else 0xFF76BDB4.toInt()
        val x = width / 2f; val y = height / 2f; val radius = 18 * density
        val ink = if (dark) 0xFFC7E7DF.toInt() else 0xFF3B7971.toInt()
        canvas.save()
        val scale = if (motion()) 1f - .025f * pressure else 1f
        canvas.scale(scale, scale, x, y)
        // All material is drawn inside the orb. No black shadow or full-window halo.
        paint.shader = surface
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (dark) 0xFF52776F.toInt() else 0xFFAAD3C8.toInt()
        canvas.drawCircle(x, y, radius - .5f * density, paint)
        bounds.set(x - radius + density, y - radius + density, x + radius - density, y + radius - density)
        paint.color = if (dark) 0x556DA598 else 0xBBFFFFFF.toInt()
        canvas.drawArc(bounds, 200f, 105f, false, paint)
        if (pressure > 0f) {
            paint.style = Paint.Style.FILL; paint.color = color(ink, .08f * pressure)
            canvas.drawCircle(x, y, radius - density, paint)
            paint.style = Paint.Style.STROKE
        }

        val clockAlpha = 1f - expansion
        val ring = 7.5f * density
        paint.color = color(ink, clockAlpha); paint.strokeWidth = 1.25f * density
        bounds.set(x - ring, y - ring, x + ring, y + ring)
        canvas.drawArc(bounds, -65f, 310f, false, paint)
        canvas.drawLine(x - 1.3f * density, y - 10 * density, x + 1.3f * density, y - 10 * density, paint)
        for (index in 0..3) {
            val angle = index * Math.PI / 2
            canvas.drawLine(x + cos(angle).toFloat() * 5.7f * density,
                y + sin(angle).toFloat() * 5.7f * density,
                x + cos(angle).toFloat() * 6.2f * density,
                y + sin(angle).toFloat() * 6.2f * density, paint)
        }
        paint.color = ink; paint.strokeWidth = 1.45f * density
        fun point(from: Float, to: Float) = (from + (to - from) * expansion) * density
        canvas.drawLine(x + point(0f, -4.5f), y + point(0f, -4.5f),
            x + point(0f, 4.5f), y + point(-5f, 4.5f), paint)
        canvas.drawLine(x + point(0f, -4.5f), y + point(0f, 4.5f),
            x + point(4f, 4.5f), y + point(2f, -4.5f), paint)
        if (phase.isNotBlank()) {
            bounds.set(x - radius + 3 * density, y - radius + 3 * density,
                x + radius - 3 * density, y + radius - 3 * density)
            paint.strokeWidth = 1.05f * density; paint.color = color(ink, .13f * clockAlpha)
            canvas.drawArc(bounds, -90f, 360f, false, paint)
            paint.color = color(ink, .7f * clockAlpha)
            if (fraction > 0) canvas.drawArc(bounds, -90f, 360f * fraction, false, paint)
            if (phase == "paused") {
                canvas.drawLine(x - 1.4f * density, y + 10.2f * density,
                    x - 1.4f * density, y + 12.6f * density, paint)
                canvas.drawLine(x + 1.4f * density, y + 10.2f * density,
                    x + 1.4f * density, y + 12.6f * density, paint)
            } else {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y + 11.4f * density, 1f * density, paint)
            }
        }
        paint.style = Paint.Style.FILL
        canvas.restore()
    }
    private fun color(rgb: Int, alpha: Float) = (rgb and 0x00FFFFFF) or
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
