package com.hl.discipline

import android.animation.ValueAnimator
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One user-started timer owner also hosts the optional, bounded floating dashboard. */
class FocusCompanionService : Service() {
    companion object {
        const val RESTORE = "restore"
        private const val NOTICE = 417
        @JvmStatic fun sync(context: Context) {
            val db = Store.get(context)
            val wanted = db.one("state", "focus") != null ||
                (db.settings().optBoolean("companionEnabled") && Settings.canDrawOverlays(context))
            if (!wanted) { context.stopService(Intent(context, FocusCompanionService::class.java)); return }
            AndroidCompat.startService(context, Intent(context, FocusCompanionService::class.java))
        }
        fun restore(context: Context) {
            AndroidCompat.startService(context, Intent(context, FocusCompanionService::class.java).setAction(RESTORE))
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var db: Store
    private lateinit var engine: Engine
    private lateinit var windows: WindowManager
    private lateinit var windowContext: Context
    private val prefs by lazy { getSharedPreferences("companion-ui", MODE_PRIVATE) }
    private var ball: CompanionBallView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: FocusCompanionOverlayHost? = null
    private var panelClosing = false
    private var closeOnTap: Boolean? = null
    @Volatile private var detailsRequested = false
    private var snap: ValueAnimator? = null
    private var panelMotion: ValueAnimator? = null
    private var destroyed = false
    private var latest = CompanionSnapshot()
    private var mode = AppThemeMode.SYSTEM
    private var shownEvent = ""
    private var pulses = 0
    private val appNames = mutableMapOf<String, String>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!screenAvailable()) { removeBall(); closePanel(false) }
            requestUpdate()
        }
    }
    private val pulse = object : Runnable {
        override fun run() {
            requestUpdate()
            handler.postDelayed(this, if (db.one("state", "focus") != null || detailsRequested) 1000 else 5000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        db = Store.get(this); engine = Engine(this)
        mode = AppThemeMode.fromValue(db.settings().optString("themeMode", "system"))
        windowContext = AndroidCompat.overlayContext(this)
        windows = windowContext.getSystemService(WindowManager::class.java)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(FocusEvents.ACTION); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Scheduler.channels(this)
        if (!AndroidCompat.promote(this, NOTICE, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0))
            return START_NOT_STICKY
        if (intent?.action == RESTORE && Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("hidden", false).apply()
            removeBall()
            if (db.settings().optBoolean("companionEnabled")) ensureBall()
            else showPanel()
        }
        handler.removeCallbacks(pulse); handler.post(pulse)
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = null
    private fun screenAvailable(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
    private fun size(): android.graphics.Rect = if (Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds
        else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    private fun params(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }

    private fun requestUpdate() {
        if (destroyed || !busy.compareAndSet(false, true)) return
        val detailed = detailsRequested
        worker.execute {
            try {
                if (db.one("state", "focus") != null || detailed) engine.tick()
                val settings = db.settings()
                val now = System.currentTimeMillis()
                val focus = db.one("state", "focus")
                val phase = focus?.optString("phase").orEmpty()
                val delta = if (phase == "work" || phase == "break") (now - focus!!.optLong("checkpoint")).coerceAtLeast(0) else 0
                val forward = focus?.optBoolean("forward") == true && phase != "break" && focus.optString("pausedPhase") != "break"
                val remaining = if (forward) focus!!.optLong("total") + delta else
                    ((focus?.optLong("remaining") ?: 0) - delta).coerceAtLeast(0)
                val pinned = db.all("plans").filter { it.optBoolean("pinned") }.map { it.optString("id") }.toSet()
                val tasks = if (detailed) engine.current(now).filter { !it.optBoolean("dismissedToday") }
                    .sortedWith(compareBy<JSONObject> { completed(it, now) }.thenBy { it.optString("planId") !in pinned }.thenBy { it.optLong("due") }) else emptyList()
                val snapshot = CompanionSnapshot(phase,
                    focus?.optString("task")?.takeIf { it.isNotBlank() }?.let { db.one("instances", it)?.optString("name") }
                        ?: "自由专注", remaining, focus?.optInt("cycle", 1) ?: 1, forward,
                    tasks.take(6).map { task ->
                        val progress = engine.progress(task, now)
                        CompanionTask(task.optString("name"), "${numberText(progress)} / ${numberText(task.optDouble("target", 1.0))}${taskUnit(task)}",
                            completed(task, now))
                    }, tasks.size, if (detailed) readLimits(now) else emptyList(), detailed)
                val event = db.one("state", "focusEvent")
                handler.post {
                    if (destroyed) return@post
                    latest = snapshot; mode = AppThemeMode.fromValue(settings.optString("themeMode", "system"))
                    if (!Settings.canDrawOverlays(this) || !screenAvailable()) { removeBall(); closePanel(false) }
                    else {
                        if (settings.optBoolean("companionEnabled")) ensureBall() else removeBall()
                        panel?.update(snapshot, mode)
                        val resting = phase == "break" || phase == "paused" && focus?.optString("pausedPhase") == "break"
                        val stageMinutes = if (resting && focus != null) {
                            val longRest = focus.optInt("cycle", 1) % focus.optInt("cycles", 4).coerceAtLeast(1) == 0
                            focus.optInt(if (longRest) "longBreak" else "shortBreak", if (longRest) 15 else 5).toLong()
                        } else focus?.optLong("minutes") ?: 0
                        ball?.updateStatus(mode.isDark((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES),
                            if (focus != null && !forward) (1f - remaining.toFloat() / (stageMinutes * Rules.MINUTE).coerceAtLeast(1)).coerceIn(0f, 1f) else 0f,
                            phase)
                        if (ComposeMainActivity.visible) {
                            if (panel?.event != null) closePanel(false)
                        } else if (event != null && !event.optBoolean("acknowledged") &&
                            event.optString("token") != shownEvent) showPhase(event)
                    }
                    if (++pulses % 15 == 0) getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
                    if (focus == null && !settings.optBoolean("companionEnabled") && panel == null) stopSelf()
                }
            } catch (error: Exception) {
                android.util.Log.e("FocusCompanion", "Companion refresh failed", error)
                handler.post { panel?.update(latest.copy(error = "暂时无法刷新，请稍后重试"), mode) }
            } finally { busy.set(false) }
        }
    }
    private fun completed(task: JSONObject, now: Long) = task.optString("status") == "done" ||
        (engine.hasChildren(task) && engine.childrenDone(task)) ||
        engine.progress(task, now) >= task.optDouble("target", 1.0)
    private fun readLimits(now: Long): List<CompanionLimit> {
        val rules = UsageWeekPlans.effectiveLimits(db, now)
        return (0 until rules.length()).mapNotNull { index ->
            val rule = rules.optJSONObject(index) ?: return@mapNotNull null
            if (!rule.optBoolean("enabled", true)) return@mapNotNull null
            val pkg = rule.optString("package")
            val label = appNames.getOrPut(pkg) {
                try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { pkg }
            }
            val usage = AppUsageLimits.read(this, pkg, now)
            val daily = rule.optInt("dailyMinutes"); val weekly = rule.optInt("weeklyMinutes")
            val summary = if (!usage.available) "需要使用情况访问授权" else listOfNotNull(
                daily.takeIf { it > 0 }?.let { "今日剩余 ${countdown((it * Rules.MINUTE - usage.todayMillis).coerceAtLeast(0))}" },
                weekly.takeIf { it > 0 }?.let { "本周剩余 ${countdown((it * Rules.MINUTE - usage.weekMillis).coerceAtLeast(0))}" }
            ).joinToString(" · ")
            CompanionLimit(label, summary)
        }
    }
    private fun ensureBall() {
        if (ball != null || prefs.getBoolean("hidden", false) || !Settings.canDrawOverlays(this) || !screenAvailable()) return
        val screen = size()
        val layout = params(dp(48f), dp(48f)).apply {
            x = if (prefs.getBoolean("left", false)) 0 else screen.width() - width
            y = (prefs.getFloat("y", .4f) * screen.height()).toInt().coerceIn(0, (screen.height() - height - dp(48f)).coerceAtLeast(0))
        }
        closeOnTap = null
        val view = CompanionBallView(windowContext,
            onTapStart = { closeOnTap = panel != null },
            onTap = {
                // ACTION_OUTSIDE can precede the delayed single-tap confirmation. Preserve the tap's intent.
                val close = closeOnTap ?: (panel != null && !panelClosing)
                closeOnTap = null
                if (close) closePanel() else showPanel()
            }, onHide = { hideBall() }, onDrag = { dx, dy ->
                closeOnTap = null
                snap?.cancel(); closePanel(false)
                layout.x = (layout.x + dx.toInt()).coerceIn(0, (size().width() - layout.width).coerceAtLeast(0))
                layout.y = (layout.y + dy.toInt()).coerceIn(0, (size().height() - layout.height - dp(48f)).coerceAtLeast(0))
                updateBallLayout(layout)
            }, onSnap = { snapBall() })
        try {
            windows.addView(view, layout); ball = view; ballParams = layout
            view.alpha = 0f; view.scaleX = .92f; view.scaleY = .92f
                view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(motionDuration(220))
                    .setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f)).start()
        } catch (error: RuntimeException) { android.util.Log.w("FocusCompanion", "Overlay unavailable", error) }
    }
    private fun motionDuration(value: Int) = if (ValueAnimator.areAnimatorsEnabled()) value.toLong() else 0L
    private fun updateBallLayout(layout: WindowManager.LayoutParams) {
        ball?.let { if (it.isAttachedToWindow) windows.updateViewLayout(it, layout) }
    }
    private fun snapBall() {
        val layout = ballParams ?: return
        val screen = size(); val left = layout.x + layout.width / 2 < screen.width() / 2
        val destination = if (left) 0 else screen.width() - layout.width
        prefs.edit().putBoolean("left", left).putFloat("y", layout.y.toFloat() / screen.height().coerceAtLeast(1)).apply()
        snap = ValueAnimator.ofInt(layout.x, destination).apply {
            duration = motionDuration(240); interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { layout.x = it.animatedValue as Int; updateBallLayout(layout) }; start()
        }
    }
    private fun hideBall() {
        prefs.edit().putBoolean("hidden", true).apply(); closeOnTap = null; closePanel(false)
        val old = ball ?: return
        // Stop intercepting touches immediately, including during the short visual exit.
        ballParams?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; updateBallLayout(it) }
        old.animate().cancel()
        old.animate().alpha(0f).scaleX(.85f).scaleY(.85f).setDuration(motionDuration(160))
            .setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f)).withEndAction {
            removeBall()
        }.start()
    }
    private fun removeBall() {
        snap?.cancel(); snap = null
        ball?.let { it.animate().cancel(); if (it.isAttachedToWindow) windows.removeView(it) }
        ball = null; ballParams = null
    }
    private fun showPanel(event: JSONObject? = null): Boolean {
        if (!Settings.canDrawOverlays(this) || !screenAvailable()) return false
        panel?.let {
            panelClosing = false; ball?.setExpanded(true)
            it.view.acceptsInput = true
            it.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (event != null) it.event = event
            animatePanel(it, 1f)
            detailsRequested = true; requestUpdate(); return true
        }
        val screen = size()
        val besideBall = ball != null
        val left = (ballParams?.x ?: screen.width()) < screen.width() / 2
        // Leave the orb exposed beside its panel instead of covering it with a wide sheet.
        val width = (screen.width() - dp(if (besideBall) 72f else 24f)).coerceAtMost(dp(228f)).coerceAtLeast(dp(180f))
        val maxHeight = (screen.height() - dp(96f)).coerceAtMost(dp(360f)).coerceAtLeast(dp(120f))
        val host = FocusCompanionOverlayHost(windowContext, maxHeight / resources.displayMetrics.density,
            close = { closePanel() }, timerAction = { timerAction() }, acknowledge = { token ->
                FocusEvents.acknowledge(this, token); closePanel(); requestUpdate()
            })
        host.update(latest, mode)
        host.event = event
        val layout = params(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            x = if (besideBall && left) dp(64f) else if (besideBall) screen.width() - width - dp(64f)
                else (screen.width() - width) / 2
            y = dp(32f)
        }
        fun anchor(height: Int): Int = if (besideBall) {
            ((ballParams?.y ?: 0) + dp(24f) - height / 2)
                .coerceIn(dp(24f), (screen.height() - height - dp(48f)).coerceAtLeast(dp(24f)))
        } else dp(44f)
        layout.y = anchor(maxHeight)
        var anchored = false
        host.view.addOnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (panel === host && bottom - top != oldBottom - oldTop) {
                // Set the anchor once; expanding a section must not pull the whole dashboard around.
                val y = if (!anchored) anchor(view.height) else layout.y.coerceIn(dp(24f),
                    (screen.height() - view.height - dp(48f)).coerceAtLeast(dp(24f)))
                anchored = true
                if (layout.y != y) { layout.y = y; windows.updateViewLayout(view, layout) }
            }
        }
        host.view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                val orb = ball
                val location = IntArray(2)
                orb?.getLocationOnScreen(location)
                val onOrb = orb != null && event.rawX >= location[0] && event.rawX < location[0] + orb.width &&
                    event.rawY >= location[1] && event.rawY < location[1] + orb.height
                if (!onOrb) closePanel()
                true
            } else false
        }
        try { windows.addView(host.view, layout) }
        catch (error: RuntimeException) { host.dispose(); return false }
        panel = host; panelClosing = false; detailsRequested = true; ball?.setExpanded(true)
        animatePanel(host, 1f)
        requestUpdate(); return true
    }
    private fun showPhase(event: JSONObject) {
        if (showPanel(event)) shownEvent = event.optString("token")
    }
    private fun animatePanel(host: FocusCompanionOverlayHost, target: Float, finished: (() -> Unit)? = null) {
        panelMotion?.removeAllListeners(); panelMotion?.cancel(); panelMotion = null
        if (!ValueAnimator.areAnimatorsEnabled()) {
            host.view.reveal = target
            if (target == 1f) host.refreshAccessibility()
            finished?.invoke(); return
        }
        panelMotion = ValueAnimator.ofFloat(host.view.reveal, target).apply {
            duration = if (target == 1f) 280L else 200L
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { host.view.reveal = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (target == 1f) host.refreshAccessibility()
                    finished?.invoke()
                }
            })
            start()
        }
    }
    private fun closePanel(animated: Boolean = true) {
        val old = panel ?: return
        panelClosing = true; ball?.setExpanded(false, animated)
        detailsRequested = false
        panelMotion?.removeAllListeners(); panelMotion?.cancel(); panelMotion = null
        old.view.acceptsInput = false
        old.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val finish = Runnable {
            if (old.view.isAttachedToWindow) windows.removeView(old.view)
            old.dispose(); if (panel === old) { panel = null; panelClosing = false }
        }
        if (!animated || !ValueAnimator.areAnimatorsEnabled()) finish.run()
        else animatePanel(old, 0f) { finish.run() }
    }
    private fun timerAction() {
        worker.execute {
            try {
                synchronized(db) {
                when (db.one("state", "focus")?.optString("phase")) {
                    "work", "break" -> engine.pauseFocus()
                    "paused", "ready" -> engine.resumeFocus()
                }
                }
                handler.post { panel?.update(latest.copy(error = ""), mode) }
            } catch (error: Exception) { handler.post { panel?.update(latest.copy(error = error.message ?: "操作未完成"), mode) } }
            finally { handler.post { requestUpdate() } }
        }
    }
    private fun notification(): Notification {
        val restore = PendingIntent.getService(this, NOTICE, Intent(this, FocusCompanionService::class.java).setAction(RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "focus_companion").apply {
            // The notification is now the recovery control, so it must not be deferred after startup.
            if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }.setSmallIcon(R.drawable.ic_app)
            .setContentTitle(if (latest.phase.isBlank()) "自律 · 悬浮球" else "自律 · ${companionPhase(latest.phase)}")
            .setContentText(if (latest.phase.isBlank()) "点按显示悬浮球" else "${countdown(latest.time)} · 点按显示悬浮球")
            .setContentIntent(restore).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration); closePanel(false); removeBall(); requestUpdate()
    }
    override fun onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null); unregisterReceiver(receiver)
        removeBall(); closePanel(false); worker.shutdown()
        super.onDestroy()
    }
}
