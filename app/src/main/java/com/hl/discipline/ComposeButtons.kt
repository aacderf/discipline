package com.hl.discipline

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal enum class GlassButtonState { Idle, Loading, Success }
internal enum class GlassSecondaryTone { Neutral, Destructive }

@Composable
private fun pressDepth(pressed: Boolean, raised: Dp): Pair<Float, Dp> {
    val motion = LocalMotionEnabled.current
    val richMotion = richMotionEnabled()
    val transition = updateTransition(pressed, label = "按钮统一按压")
    val scale by transition.animateFloat(transitionSpec = {
        if (!motion) snap() else if (targetState) tween(AppMotion.pressIn, easing = AppMotion.easing)
        else spring(dampingRatio = AppMotion.pressSpringDamping, stiffness = AppMotion.pressSpringStiffness)
    }, label = "尺度") { if (it && motion) AppMotion.pressScale else 1f }
    val elevation by transition.animateDp(transitionSpec = {
        if (!richMotion) snap() else if (targetState) tween(AppMotion.pressIn, easing = AppMotion.easing)
        else spring(dampingRatio = AppMotion.pressSpringDamping, stiffness = AppMotion.pressSpringStiffness)
    }, label = "高度") { if (it && richMotion) AppElevation.pressed else raised }
    return scale to elevation
}

@Composable
internal fun CreatePlanAction(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (scale, _) = pressDepth(pressed, AppElevation.flat)
    val palette = AppColors.current
    val fill = Brush.verticalGradient(if (pressed)
        listOf(palette.primaryPressedTop, palette.primaryPressedBottom)
        else listOf(palette.primaryTop, palette.primaryBottom))
    val shape = AppShapes.material.large
    Box(modifier.size(56.dp).testTag("create-plan-fab")
        .graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .background(fill, shape)
        .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else palette.primaryBottom, shape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "创建计划", onClick = onClick)
        .semantics { contentDescription = "创建计划" }, contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_plus), contentDescription = null,
            tint = AppColors.ink, modifier = Modifier.size(26.dp))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GlassPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: GlassButtonState = GlassButtonState.Idle,
    @DrawableRes iconRes: Int? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    loadingLabel: String = "处理中",
    successLabel: String = "已完成",
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = enabled && state == GlassButtonState.Idle
    val (scale, elevation) = pressDepth(pressed && active,
        if (active) AppElevation.primaryButton else AppElevation.flat)
    val palette = AppColors.current
    val shape = AppShapes.primaryButton
    val top = when {
        !enabled && state == GlassButtonState.Idle -> palette.primaryDisabledTop
        pressed -> palette.primaryPressedTop
        else -> palette.primaryTop
    }
    val bottom = when {
        !enabled && state == GlassButtonState.Idle -> palette.primaryDisabledBottom
        pressed -> palette.primaryPressedBottom
        else -> palette.primaryBottom
    }
    val ink = if (!enabled && state == GlassButtonState.Idle)
        palette.disabledText else AppColors.ink
    val stateModifier = if (state == GlassButtonState.Idle) Modifier else Modifier.semantics {
        stateDescription = if (state == GlassButtonState.Loading) "正在处理" else "操作成功"
        liveRegion = LiveRegionMode.Polite
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    Button(
        onClick = onClick,
        enabled = active,
        interactionSource = interaction,
        modifier = modifier.widthIn(min = AppButtonSize.primaryMinWidth)
            .heightIn(min = AppButtonSize.primaryHeight).graphicsLayer(scaleX = scale, scaleY = scale)
            .shadow(elevation, shape, ambientColor = palette.primaryBorder.copy(alpha = .16f),
                spotColor = palette.primaryBorder.copy(alpha = .22f))
            .background(Brush.verticalGradient(listOf(top, bottom)), shape)
            .drawWithContent {
                drawContent()
                val inset = AppSpacing.page.toPx()
                if (size.width > inset * 2) {
                    drawLine(palette.primaryHighlight,
                        Offset(inset, 2.dp.toPx()), Offset(size.width - inset, 2.dp.toPx()),
                        strokeWidth = 1.dp.toPx())
                    drawLine(palette.primaryBorder,
                        Offset(inset, size.height - 2.dp.toPx()),
                        Offset(size.width - inset, size.height - 2.dp.toPx()),
                        strokeWidth = 1.dp.toPx())
                }
            }.then(stateModifier),
        shape = shape,
        border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else palette.primaryBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = ink,
            disabledContainerColor = Color.Transparent, disabledContentColor = ink),
        contentPadding = PaddingValues(horizontal = AppSpacing.page + 2.dp, vertical = AppSpacing.sm)
    ) {
        // All three labels stay measured so a state change cannot resize a wrap-content button.
        Box(contentAlignment = Alignment.Center) {
            GlassPrimaryContent(label, GlassButtonState.Idle, state, ink, iconRes, leadingIcon)
            GlassPrimaryContent(loadingLabel, GlassButtonState.Loading, state, ink, null, null)
            GlassPrimaryContent(successLabel, GlassButtonState.Success, state, ink, null, null)
        }
    }
    }
}

@Composable
private fun GlassPrimaryContent(
    label: String,
    variant: GlassButtonState,
    current: GlassButtonState,
    ink: Color,
    @DrawableRes iconRes: Int?,
    leadingIcon: (@Composable () -> Unit)?
) {
    val visible = variant == current
    val opacity by animateFloatAsState(if (visible) 1f else 0f,
        animationSpec = tween(if (LocalMotionEnabled.current) AppMotion.stateChange else 0),
        label = "按钮状态")
    Row(
        modifier = Modifier.alpha(opacity)
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (variant == GlassButtonState.Idle) {
            val motion = LocalMotionEnabled.current
            // Text and glyph share a single target; paused/resumed cannot disagree visually.
            AnimatedContent(label to iconRes, transitionSpec = {
                fadeIn(tween(if (motion) AppMotion.stateChange else 0, easing = AppMotion.easing)) togetherWith
                    fadeOut(tween(if (motion) 100 else 0))
            }, label = "主操作内容") { (shownLabel, shownIcon) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = if (shownLabel == label) Modifier else Modifier.clearAndSetSemantics {}) {
                    if (leadingIcon != null) {
                        Box(Modifier.size(AppIconSize.button).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                            leadingIcon()
                        }
                    } else if (shownIcon != null) {
                        Icon(painterResource(shownIcon), contentDescription = null,
                            modifier = Modifier.size(AppIconSize.button))
                    }
                    if (shownIcon != null || leadingIcon != null) Spacer(Modifier.width(AppSpacing.xs + 1.dp))
                    Text(shownLabel, style = AppTypography.primaryButton, textAlign = TextAlign.Center)
                }
            }
            return@Row
        }
        when (variant) {
            GlassButtonState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.loading).clearAndSetSemantics {},
                strokeWidth = 2.dp, color = ink)
            GlassButtonState.Success -> Icon(painterResource(R.drawable.ic_check),
                contentDescription = null, modifier = Modifier.size(AppIconSize.button))
            GlassButtonState.Idle -> if (leadingIcon != null) {
                Box(Modifier.size(AppIconSize.button).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                    leadingIcon()
                }
            } else if (iconRes != null) {
                Icon(painterResource(iconRes), contentDescription = null,
                    modifier = Modifier.size(AppIconSize.button))
            }
        }
        if (variant != GlassButtonState.Idle || iconRes != null || leadingIcon != null)
            Spacer(Modifier.width(AppSpacing.xs + 1.dp))
        Text(label, style = AppTypography.primaryButton, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun GlassSecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: GlassSecondaryTone = GlassSecondaryTone.Neutral,
    @DrawableRes iconRes: Int? = null,
    onClick: () -> Unit
) = SecondaryButtonBody(label, modifier, enabled, tone, iconRes, glass = false, onClick = onClick)

/** A lighter local action for a glass panel; no per-button blur or shader. */
@Composable
internal fun GlassSurfaceButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes iconRes: Int? = null,
    onClick: () -> Unit
) = SecondaryButtonBody(label, modifier, enabled, GlassSecondaryTone.Neutral, iconRes,
    glass = true, onClick = onClick)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SecondaryButtonBody(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    tone: GlassSecondaryTone,
    @DrawableRes iconRes: Int?,
    glass: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (scale, _) = pressDepth(pressed && enabled, AppElevation.flat)
    val palette = AppColors.current
    val shape = AppShapes.secondaryButton
    val fill = when {
        !enabled -> palette.secondaryDisabled
        tone == GlassSecondaryTone.Destructive -> MaterialTheme.colorScheme.errorContainer
        glass && pressed -> palette.glassButtonPressed
        glass -> palette.glassButtonFill
        pressed -> palette.secondaryPressed
        else -> palette.secondaryFill
    }
    val ink = when {
        !enabled -> palette.disabledText
        tone == GlassSecondaryTone.Destructive -> MaterialTheme.colorScheme.onErrorContainer
        else -> palette.secondaryText
    }
    val edge = if (focused) MaterialTheme.colorScheme.primary
    else if (tone == GlassSecondaryTone.Destructive)
        MaterialTheme.colorScheme.error.copy(alpha = .48f)
    else if (glass) palette.glassButtonBorder else palette.secondaryBorder
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.heightIn(min = AppButtonSize.secondaryHeight).graphicsLayer(scaleX = scale, scaleY = scale)
            .background(fill, shape).drawWithContent {
                drawContent()
                if (size.width > 36.dp.toPx()) drawLine(palette.secondaryHighlight,
                    Offset(18.dp.toPx(), 2.dp.toPx()), Offset(size.width - 18.dp.toPx(), 2.dp.toPx()),
                    strokeWidth = 1.dp.toPx())
            },
        shape = shape, border = BorderStroke(1.dp, edge),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent, contentColor = ink,
            disabledContainerColor = Color.Transparent, disabledContentColor = ink),
        contentPadding = PaddingValues(horizontal = AppSpacing.md + 2.dp, vertical = AppSpacing.xs + 2.dp)) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(AppIconSize.secondaryButton))
            Spacer(Modifier.width(AppSpacing.xs))
        }
        Text(label, style = AppTypography.secondaryButton, textAlign = TextAlign.Center)
    }
    }
}

@Composable
internal fun GlassIconButton(
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: String? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (scale, _) = pressDepth(pressed && enabled, AppElevation.flat)
    val palette = AppColors.current
    val shape = AppShapes.iconButton
    val fill by animateColorAsState(
        if (enabled && pressed) palette.iconPressed
        else if (enabled) palette.glassButtonFill else palette.secondaryDisabled,
        tween(if (LocalMotionEnabled.current) AppMotion.pressIn else 0, easing = AppMotion.easing),
        label = "图标薄荷反馈")
    Box(modifier = modifier.size(AppButtonSize.iconTarget).graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(shape).background(fill, shape)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else palette.iconBorder, shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            }, contentAlignment = Alignment.Center) {
        Box(Modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun GlassPillButton(label: String, selected: Boolean, modifier: Modifier = Modifier,
                             enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val (scale, _) = pressDepth(pressed && enabled, AppElevation.flat)
    val palette = AppColors.current
    val fill = when {
        !enabled -> palette.secondaryDisabled
        pressed -> palette.secondaryPressed
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> palette.secondaryFill
    }
    val ink = when {
        !enabled -> palette.disabledText
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(modifier.heightIn(min = AppButtonSize.pillTarget).graphicsLayer(scaleX = scale, scaleY = scale)
        .clip(AppShapes.pill)
        .then(if (focused) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, AppShapes.pill) else Modifier)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }, contentAlignment = Alignment.Center) {
        Surface(shape = AppShapes.pill, color = fill,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (selected) .55f else .85f))) {
            Box(Modifier.heightIn(min = AppButtonSize.pillVisible).padding(horizontal = AppSpacing.md), contentAlignment = Alignment.Center) {
                Text(label, style = AppTypography.pill, color = ink)
            }
        }
    }
}

@Composable
internal fun GlassLoadingButton(modifier: Modifier = Modifier, label: String = "处理中") {
    GlassPrimaryButton(label, modifier, state = GlassButtonState.Loading, loadingLabel = label,
        iconRes = R.drawable.ic_check, onClick = {})
}

@Composable
internal fun GlassSuccessButton(modifier: Modifier = Modifier, label: String = "已完成") {
    GlassPrimaryButton(label, modifier, state = GlassButtonState.Success, successLabel = label,
        iconRes = R.drawable.ic_check, onClick = {})
}

@DrawableRes
internal fun taskActionIcon(action: String): Int = when (action) {
    "标记完成" -> R.drawable.ic_check
    "记录数量" -> R.drawable.ic_target
    "开始专注" -> R.drawable.ic_play
    else -> R.drawable.ic_check
}

/** Native dialog behavior with a solid card, one edge and a predictable 32% scrim. */
@Composable
internal fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    AlertDialog(onDismissRequest = onDismissRequest,
        modifier = Modifier.border(1.dp, AppColors.current.glassBorder, AppShapes.glass),
        shape = AppShapes.glass,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
        tonalElevation = 0.dp,
        title = {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(.32f)
            }
            title()
        }, text = text, confirmButton = confirmButton, dismissButton = dismissButton)
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: () -> Unit, dismiss: () -> Unit) {
    GlassAlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("返回") } })
}
