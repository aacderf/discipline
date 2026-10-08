package com.hl.discipline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun TimetableWeekGrid(state: JSONObject, week: Int, selectedDay: Int,
    viewport: TimetableViewportState, onFocusedDay: (Int) -> Unit,
    onDaySelected: (Int) -> Unit, onCourseSelected: (JSONObject) -> Unit) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val height = (LocalConfiguration.current.screenHeightDp - 320).coerceIn(300, 620).dp
    val motion = LocalMotionEnabled.current
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val rows = remember(state, week) { (1..7).associateWith { Timetable.forDay(state, week, it) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bodyWidth = (maxWidth - 24.dp).value.coerceAtLeast(140f)
        val bodyHeight = (height - 48.dp).value
        val geometry = timetableGeometry(bodyWidth, fontScale, viewport.zoom)
        SideEffect { viewport.clamp(bodyWidth, bodyHeight, fontScale) }
        fun zoomTo(target: Float) {
            zoomJob?.cancel()
            val end = target.coerceIn(1f, 4f)
            zoomJob = scope.launch {
                Animatable(viewport.zoom).animateTo(end,
                    tween(if (motion) AppMotion.gridZoom else 0, easing = AppMotion.easing)) {
                    viewport.zoomAt(value, bodyWidth / 2f, bodyHeight / 2f, bodyWidth, bodyHeight, fontScale)
                }
            }
        }
        fun focusDay(number: Int) {
            onFocusedDay(number)
            zoomJob?.cancel()
            val initialZoom = viewport.zoom
            val initialX = viewport.x
            zoomJob = scope.launch {
                Animatable(0f).animateTo(1f, tween(if (motion) AppMotion.gridZoom else 0,
                    easing = AppMotion.easing)) {
                    viewport.zoom = initialZoom + (2.5f.coerceAtLeast(initialZoom) - initialZoom) * value
                    val target = (timetableGeometry(bodyWidth, fontScale, viewport.zoom).pitch *
                        (number - .5f) - bodyWidth / 2f).coerceAtLeast(0f)
                    viewport.x = initialX + (target - initialX) * value
                    viewport.clamp(bodyWidth, bodyHeight, fontScale)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Caption(if (geometry.overview) "全周概览 · 点选聚焦当天" else "双指缩放 · 滑动查看")
                Row {
                    IconButton(onClick = { zoomTo(viewport.zoom - .5f) }, enabled = viewport.zoom > 1.001f,
                        modifier = Modifier.size(48.dp).testTag("timetable-zoom-out")
                            .semantics { contentDescription = "缩小周网格" }) { Text("−", fontSize = 20.sp) }
                    IconButton(onClick = { zoomTo(viewport.zoom + .5f) }, enabled = viewport.zoom < 3.999f,
                        modifier = Modifier.size(48.dp).testTag("timetable-zoom-in")
                            .semantics { contentDescription = "放大周网格" }) { Text("+", fontSize = 20.sp) }
                }
            }
            Column(Modifier.fillMaxWidth().height(height).testTag("timetable-week-grid")
                .clip(RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    Box(Modifier.width(24.dp).fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Row(Modifier.offset { IntOffset((-viewport.x * density.density).roundToInt(), 0) }
                            .width(geometry.width.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { number ->
                                val selected = number == selectedDay
                                Column(Modifier.width(geometry.dayWidth.dp).fillMaxHeight()
                                    .then(if (geometry.dayWidth >= 48f)
                                        Modifier.quietClickable("查看周${"一二三四五六日"[number - 1]}日程") {
                                            onDaySelected(number)
                                        } else Modifier)
                                    .testTag("timetable-day-$number"),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    Text("${"一二三四五六日"[number - 1]}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    Text("${Timetable.date(week, number).dayOfMonth}",
                                        style = AppTypography.number.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.width(24.dp).fillMaxHeight().clipToBounds()) {
                        (1..12).forEach { period ->
                            Text("$period", Modifier.offset {
                                IntOffset(0, ((geometry.slotHeight * (period - 1) - viewport.y) * density.density).roundToInt())
                            }, style = AppTypography.number.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val scroller = rememberScrollable2DState { delta ->
                        zoomJob?.cancel()
                        val oldX = viewport.x
                        val oldY = viewport.y
                        viewport.x -= delta.x / density.density
                        viewport.y -= delta.y / density.density
                        viewport.clamp(bodyWidth, bodyHeight, fontScale)
                        Offset((oldX - viewport.x) * density.density, (oldY - viewport.y) * density.density)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()
                        .scrollable2D(scroller)
                        .pointerInput(bodyWidth, bodyHeight, fontScale) {
                            awaitEachGesture {
                                var transforming = false
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        transforming = true
                                        val scale = event.calculateZoom()
                                        if (event.changes.all { it.previousPressed }) {
                                            zoomJob?.cancel()
                                            val center = event.calculateCentroid(useCurrent = false) / density.density
                                            viewport.zoomAt(viewport.zoom * scale, center.x, center.y,
                                                bodyWidth, bodyHeight, fontScale)
                                            val pan = event.calculatePan() / density.density
                                            viewport.x -= pan.x
                                            viewport.y -= pan.y
                                            viewport.clamp(bodyWidth, bodyHeight, fontScale)
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else if (transforming) {
                                        // A pinch remains exclusive until both fingers lift; no stray tap/fling.
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(geometry.overview, week) {
                            if (geometry.overview) detectTapGestures { point ->
                                val current = timetableGeometry(bodyWidth, fontScale, viewport.zoom)
                                val number = ((point.x / density.density + viewport.x) / current.pitch)
                                    .toInt().coerceIn(0, 6) + 1
                                focusDay(number)
                            }
                        }
                        .semantics {
                            contentDescription = "第 $week 周课表"
                            stateDescription = if (geometry.overview) "七天概览" else "已放大"
                            customActions = (1..7).map { number ->
                                CustomAccessibilityAction("查看周${"一二三四五六日"[number - 1]}日程") {
                                    onDaySelected(number); true
                                }
                            } + listOf(
                                CustomAccessibilityAction("放大课表") { zoomTo(viewport.zoom + .5f); true },
                                CustomAccessibilityAction("缩小课表") { zoomTo(viewport.zoom - .5f); true })
                        }) {
                        (1..7).forEach { number ->
                            rows[number].orEmpty().forEach { course ->
                                val first = course.optInt("from")
                                val span = course.optInt("until") - first + 1
                                val courseHeight = geometry.slotHeight * span - 4f
                                val canOpen = !geometry.overview && courseHeight >= 48f && geometry.dayWidth >= 48f
                                val id = course.optString("id")
                                val color = when (Math.floorMod(course.optString("title").hashCode(), 3)) {
                                    1 -> lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondary, .13f)
                                    2 -> lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.tertiary, .12f)
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                }
                                Surface(Modifier.offset {
                                    IntOffset(((number - 1) * geometry.pitch - viewport.x).times(density.density).roundToInt(),
                                        ((first - 1) * geometry.slotHeight - viewport.y).times(density.density).roundToInt())
                                }.width(geometry.dayWidth.dp).height(courseHeight.dp)
                                    .then(if (number == selectedDay)
                                        Modifier.viewShared("course-$week-$number-$id", RoundedCornerShape(12.dp)) else Modifier)
                                    .then(if (canOpen) Modifier.pressableCard(RoundedCornerShape(12.dp),
                                        "编辑${course.optString("title")}") { onCourseSelected(course) } else Modifier)
                                    .testTag("timetable-course-$id")
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = courseDescription(course)
                                        if (!canOpen) customActions = listOf(
                                            CustomAccessibilityAction("查看课程详情") { onCourseSelected(course); true })
                                    }, color = color, shape = RoundedCornerShape(12.dp)) {
                                    Column(Modifier.padding(horizontal = if (geometry.overview) 2.dp else 7.dp,
                                        vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        AnimatedContent(geometry.overview, transitionSpec = {
                                            fadeIn(tween(if (motion) AppMotion.gridLabels else 0)) togetherWith
                                                fadeOut(tween(if (motion) AppMotion.gridLabels else 0))
                                        }, label = "课程信息密度") { compact ->
                                        Text(if (compact) courseShortName(course.optString("title")) else course.optString("title"),
                                            fontFamily = FontFamily.SansSerif, fontSize = if (geometry.overview) 12.sp else 14.sp,
                                            lineHeight = if (geometry.overview) 15.sp else 19.sp,
                                            maxLines = if (compact) 4 else 3, overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag(if (compact) "course-short-name" else "course-full-name")
                                                .semantics { if (compact != geometry.overview) hideFromAccessibility() })
                                        }
                                        if (geometry.dayWidth >= 95f) {
                                            Text("$first–${course.optInt("until")}节", style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(course.optString("room"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
