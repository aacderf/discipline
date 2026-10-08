package com.hl.discipline

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow

/** The active silhouette has cut-out detail; the inactive mark retains its original outline. */
@Composable
internal fun NavigationMark(route: String, selected: () -> Float, modifier: Modifier = Modifier) {
    val outline = when (route) {
        "today" -> R.drawable.ic_nav_today
        "timetable" -> R.drawable.ic_nav_plans
        "focus" -> R.drawable.ic_nav_focus
        else -> R.drawable.ic_nav_stats
    }
    val solid = when (route) {
        "today" -> R.drawable.ic_nav_today_selected
        "timetable" -> R.drawable.ic_nav_plans_selected
        "focus" -> R.drawable.ic_nav_focus_selected
        else -> R.drawable.ic_nav_stats_selected
    }
    Box(modifier) {
        Icon(painterResource(outline), null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - selected() })
        Icon(painterResource(solid), null, tint = AppColors.current.navigationInk,
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = selected() })
    }
}

/** Keep changing label ink local: it must not recompose the entire main screen each frame. */
@Composable
internal fun NavigationLabel(label: String, selected: Boolean, modifier: Modifier = Modifier) {
    val ink by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(if (LocalMotionEnabled.current) AppMotion.navigationIcon else 0, easing = AppMotion.easing),
        label = "$label 导航文字")
    Text(label, modifier, style = MaterialTheme.typography.labelMedium,
        color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
