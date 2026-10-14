package com.hl.discipline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Same opaque chrome as Screen; each card/row is an independent, keyed lazy item. */
@Composable
internal fun LazyScreen(title: String, onBack: (() -> Unit)? = null, subtitle: String = "",
    state: LazyListState = rememberLazyListState(), fixedContent: @Composable (() -> Unit)? = null,
    bottom: @Composable (() -> Unit)? = null, floatingActionButton: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit) {
    val liftPixels = with(LocalDensity.current) { 24.dp.roundToPx() }
    val lifted by remember(state, liftPixels) { derivedStateOf {
        state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > liftPixels
    } }
    Scaffold(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            ScreenHeader(title, onBack, lifted, subtitle)
            fixedContent?.invoke()
        } },
        bottomBar = { if (bottom != null) Box(Modifier.navigationBarsPadding()) { bottom() } },
        floatingActionButton = { if (floatingActionButton != null) Box(Modifier.navigationBarsPadding()) { floatingActionButton() } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).imePadding(),
            state = state, contentPadding = PaddingValues(start = AppSpacing.page, end = AppSpacing.page,
                top = AppSpacing.sm, bottom = if (floatingActionButton == null) AppSpacing.lg else 88.dp),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm), content = content)
    }
}
