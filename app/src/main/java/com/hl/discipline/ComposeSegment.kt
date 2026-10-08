package com.hl.discipline

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DraggableSegment(labels: List<String>, selected: Int, motionEnabled: Boolean, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp).clip(AppShapes.segment)
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        val count = labels.size
        val itemWidth = maxWidth / count
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val itemWidthPx = widthPx / count
        var dragging by remember { mutableStateOf(false) }
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }
        val targetOffset = if (dragging) dragOffsetPx else itemWidthPx * selected
        val offset = animateFloatAsState(targetOffset,
            animationSpec = if (!motionEnabled || dragging) snap()
                else spring(dampingRatio = AppMotion.navigationSnapDamping,
                    stiffness = AppMotion.navigationSnapStiffness), label = "选中分类")
        Box(Modifier.graphicsLayer { translationX = offset.value }.width(itemWidth).fillMaxHeight().padding(3.dp)
            .clip(AppShapes.segmentThumb).background(AppColors.current.navigationFill))
        Row(Modifier.fillMaxSize().pointerInput(count, widthPx) {
            fun update(x: Float) {
                val index = ((x / widthPx) * count).toInt().coerceIn(0, count - 1)
                if (index != currentSelected.value) {
                    currentOnSelect.value(index)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            fun follow(x: Float) {
                dragOffsetPx = (x - itemWidthPx / 2).coerceIn(0f, widthPx - itemWidthPx)
                update(x)
            }
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; follow(it.x) },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
                onHorizontalDrag = { change, _ -> follow(change.position.x); change.consume() })
        }) {
            labels.forEachIndexed { index, label ->
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val focused by interaction.collectIsFocusedAsState()
                val ink by animateColorAsState(
                    if (selected == index) AppColors.ink
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(if (motionEnabled) AppMotion.selection else 0, easing = AppMotion.easing), label = "$label 筛选颜色")
                Box(Modifier.weight(1f).fillMaxHeight().clip(AppShapes.segmentThumb)
                    .background(if (pressed) MaterialTheme.colorScheme.primary.copy(alpha = .06f)
                        else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary,
                        AppShapes.segmentThumb) else Modifier)
                    .selectable(selected == index, interactionSource = interaction, indication = null,
                    role = Role.RadioButton, onClick = {
                        if (selected != index) {
                            onSelect(index)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, color = ink)
                }
            }
        }
    }
}
