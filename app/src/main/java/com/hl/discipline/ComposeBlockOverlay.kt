package com.hl.discipline

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Owns the composition separately from the accessibility service's window. */
class ComposeBlockOverlayHost(
    context: Context,
    private val appLabel: String,
    private val taskName: String,
    private val onHome: Runnable,
    private val onUnlock: java.util.function.Consumer<String>
) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    private var remaining by mutableLongStateOf(0)
    private var waitRemaining by mutableLongStateOf(0)
    private var waiting by mutableStateOf(false)
    private var disposed = false
    val view = FrameLayout(context).also { root ->
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = AppThemeMode.fromValue(Store.get(context).settings().optString("themeMode", "system")).isDark(systemDark)
        val scheme = AppColors.scheme(dark)
        root.setBackgroundColor(scheme.background.toArgb())
        root.isClickable = true
        val prompt = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "应用已受限"; textSize = 26f; setTextColor(scheme.onBackground.toArgb())
            })
            addView(TextView(context).apply {
                text = appLabel; textSize = 18f; setTextColor(scheme.onSurfaceVariant.toArgb())
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            })
        }
        root.addView(prompt, FrameLayout.LayoutParams(-1, -1))
        // Paint and receive touches before expensive cold Compose initialization.
        // This is the real restriction window, not a screenshot or a delayed Activity.
        var mounted = false
        root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (mounted) return
                mounted = true
                root.post {
                    root.viewTreeObserver.removeOnDrawListener(this)
                    if (disposed || !root.isAttachedToWindow) return@post
                    val content = ComposeView(context).apply {
                        setViewTreeLifecycleOwner(this@ComposeBlockOverlayHost)
                        setViewTreeSavedStateRegistryOwner(this@ComposeBlockOverlayHost)
                        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    }
                    content.setContent {
            DisciplineTheme {
                var reason by remember { mutableStateOf("") }
                var error by remember { mutableStateOf(false) }
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
                        verticalArrangement = Arrangement.Center) {
                        Text("应用已受限", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        Text(appLabel, fontSize = 18.sp)
                        Caption("原因：$taskName")
                        Spacer(Modifier.height(24.dp))
                        GlassTaskPanel { Caption("限制倒计时"); Text(countdown(remaining),
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp, fontFeatureSettings = "tnum")) }
                        Spacer(Modifier.height(28.dp))
                        Primary("返回桌面", Modifier.fillMaxWidth(), onClick = { onHome.run() })
                        Spacer(Modifier.height(16.dp))
                        Input("临时使用原因", reason, { reason = it; error = false },
                            error = if (error) "请先填写原因" else "")
                        Secondary(if (!waiting) "申请应急解锁" else if (waitRemaining > 0) "请等待 ${(waitRemaining + 999) / 1000} 秒" else "临时放行 10 分钟",
                            Modifier.fillMaxWidth()) {
                            if (reason.trim().isEmpty()) error = true else onUnlock.accept(reason.trim())
                        }
                    }
                }
            }
                    }
                    prompt.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    root.addView(content, FrameLayout.LayoutParams(-1, -1))
                }
            }
        })
    }

    fun update(endRemaining: Long, waitMillis: Long, isWaiting: Boolean) {
        remaining = endRemaining
        waitRemaining = waitMillis
        waiting = isWaiting
    }

    fun dispose() { disposed = true; lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
}
