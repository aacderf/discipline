package com.hl.discipline

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/** An opaque page remains opaque while its contents move over the previous destination. */
@Composable
internal fun OpaqueSubpage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/** Internal settings pages only. NavHost retains ownership of destination back gestures. */
@Composable
internal fun SubpageTransition(
    page: Int, root: Int = 0, backEnabled: Boolean, onBack: () -> Unit,
    content: @Composable (Int) -> Unit
) {
    val motion = LocalMotionEnabled.current
    val state = remember { SeekableTransitionState(page) }
    val transition = rememberTransition(state, label = "设置子页")
    var seeking by remember { mutableStateOf(false) }
    val travel = with(LocalDensity.current) { AppMotion.pageTravel.roundToPx() }

    // Re-run after a gesture commits or cancels, even if its logical page has not changed.
    LaunchedEffect(page, motion, seeking) {
        if (!seeking) {
            if (motion) state.animateTo(page) else state.snapTo(page)
        }
    }
    PredictiveBackHandler(enabled = backEnabled && page != root) { events ->
        seeking = true
        try {
            events.collect { event ->
                if (motion) state.seekTo(event.progress.coerceIn(0f, 1f), root)
            }
            // Commit the UI state immediately; the remaining movement never delays the action.
            onBack()
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            seeking = false
        }
    }
    transition.AnimatedContent(Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background).clipToBounds(), transitionSpec = {
        val returning = targetState == root
        val duration = if (motion) AppMotion.panel else 0
        (slideInHorizontally(tween(duration, easing = AppMotion.easing)) {
                if (returning) -travel else travel
            } togetherWith slideOutHorizontally(tween(duration, easing = AppMotion.easing)) {
                if (returning) travel / 2 else -travel / 2
            }).apply { targetContentZIndex = if (returning) -1f else 1f } using null
    }) { shown ->
        ScreenExitGuard(shown != page || seeking, Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)) { content(shown) }
    }
}

/** Keep the sheet mounted through hide(), including closes from actions inside the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalSheet(
    visible: Boolean, onDismiss: () -> Unit, skipPartiallyExpanded: Boolean = false,
    onHidden: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    var mounted by remember { mutableStateOf(false) }
    val hidden by rememberUpdatedState(onHidden)
    LaunchedEffect(visible) {
        if (visible) {
            val reopening = mounted
            mounted = true
            // A new sheet runs its native show effect. A reversed exit is already mounted.
            if (reopening) sheet.show()
        } else if (mounted) {
            sheet.hide()
            mounted = false
            hidden()
        }
    }
    if (mounted) ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(visible) {
            if (!visible) { focus.clearFocus(); keyboard?.hide() }
        }
        ScreenExitGuard(!visible, Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

/** The sheet content is wrap-content, so explicitly leave space for the current keyboard. */
@Composable
internal fun editorSheetHeight(): Dp {
    val density = LocalDensity.current
    val usable = LocalWindowInfo.current.containerSize.height - WindowInsets.ime.getBottom(density) -
        WindowInsets.statusBars.getTop(density) - WindowInsets.navigationBars.getBottom(density)
    return with(density) { usable.coerceAtLeast(1).toDp() * .92f }
}

/** Preserve editor data while its native exit runs, without saving transient drafts to the DB. */
@Composable
internal fun <T : Any> AppValueSheet(
    value: T?, onDismiss: () -> Unit, content: @Composable ColumnScope.(T) -> Unit
) {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained = value }
    val displayed = value ?: retained
    AppModalSheet(value != null, onDismiss, skipPartiallyExpanded = true,
        onHidden = { retained = null }) {
        displayed?.let { content(it) }
    }
}
