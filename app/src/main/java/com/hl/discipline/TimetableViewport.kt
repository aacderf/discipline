package com.hl.discipline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import kotlin.math.max

internal data class TimetableGeometry(val dayWidth: Float, val slotHeight: Float) {
    val pitch get() = dayWidth + 4f
    val width get() = dayWidth * 7f + 24f
    val height get() = slotHeight * 12f
    val overview get() = dayWidth < 60f
}

internal fun timetableGeometry(bodyWidth: Float, fontScale: Float, zoom: Float): TimetableGeometry {
    val minimum = ((bodyWidth - 24f) / 7f).coerceAtLeast(20f)
    val fraction = ((zoom.coerceIn(1f, 4f) - 1f) / 3f)
    // Leave headroom for scaled CJK line metrics, not just the requested line height.
    val minimumSlot = max(42f, 42f * fontScale)
    return TimetableGeometry(minimum + (max(156f, minimum) - minimum) * fraction,
        minimumSlot + (max(64f, minimumSlot) - minimumSlot) * fraction)
}

/** Presentation state only. Offsets are dp so density changes don't corrupt restoration. */
internal class TimetableViewportState(zoom: Float = 2.5f, x: Float = 0f, y: Float = 0f) {
    var zoom by mutableFloatStateOf(zoom.coerceIn(1f, 4f))
    var x by mutableFloatStateOf(x)
    var y by mutableFloatStateOf(y)

    fun clamp(width: Float, height: Float, fontScale: Float) {
        val geometry = timetableGeometry(width, fontScale, zoom)
        x = x.coerceIn(0f, (geometry.width - width).coerceAtLeast(0f))
        y = y.coerceIn(0f, (geometry.height - height).coerceAtLeast(0f))
    }

    fun zoomAt(next: Float, focusX: Float, focusY: Float,
               width: Float, height: Float, fontScale: Float) {
        val before = timetableGeometry(width, fontScale, zoom)
        val day = (x + focusX) / before.pitch
        val period = (y + focusY) / before.slotHeight
        zoom = next.coerceIn(1f, 4f)
        val after = timetableGeometry(width, fontScale, zoom)
        x = day * after.pitch - focusX
        y = period * after.slotHeight - focusY
        clamp(width, height, fontScale)
    }

    companion object {
        val Saver = listSaver<TimetableViewportState, Float>(
            save = { listOf(it.zoom, it.x, it.y) },
            restore = { TimetableViewportState(it[0], it[1], it[2]) }
        )
    }
}

internal fun courseShortName(title: String): String {
    val clean = title.trim()
    return when {
        clean.contains("Verilog", ignoreCase = true) || clean.contains("硬件描述") -> "Verilog"
        clean.contains("概率论") -> "概率统计"
        clean.contains("数字电子") -> "数电"
        clean.contains("大学英语") -> "英语"
        clean.contains("数据恢复") -> "数据探秘"
        clean.contains("音乐") -> "音乐"
        clean.contains("毛泽东") -> "毛概"
        clean.contains("复变") -> "复变函数"
        clean.contains("单片机") -> "单片机"
        clean.contains("形势") -> "形势政策"
        clean.contains("体育") || clean.contains("乒乓") -> "乒乓球"
        clean.length <= 4 -> clean
        else -> clean.take(4)
    }
}
