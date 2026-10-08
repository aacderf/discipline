package com.hl.discipline

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateBounds
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.channels.Channel

@Composable
internal fun DisciplineTheme(darkTheme: Boolean? = null, themeMode: AppThemeMode? = null,
                             content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = darkTheme ?: (themeMode ?: remember(context) {
        AppThemeMode.fromValue(Store.get(context).settings().optString("themeMode", "system"))
    }).isDark(isSystemInDarkTheme())
    CompositionLocalProvider(LocalAppPalette provides AppColors.palette(dark)) {
        MaterialTheme(colorScheme = AppColors.scheme(dark),
            typography = AppTypography.material, shapes = AppShapes.material, content = content)
    }
}

@Composable
internal fun SystemBars(activity: ComponentActivity) {
    val dark = AppColors.current.dark
    val barColor = AppColors.scheme(dark).background.toArgb()
    val backdrop = remember(barColor) { android.graphics.drawable.ColorDrawable(barColor) }
    DisposableEffect(activity, backdrop) {
        activity.window.setBackgroundDrawable(backdrop)
        onDispose { }
    }
    SideEffect {
        activity.window.statusBarColor = barColor
        activity.window.navigationBarColor = barColor
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (dark) 0 else
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}

class ComposeMainActivity : ComponentActivity() {
    companion object { @Volatile internal var visible = false }
    private val phaseReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) { refresh() }
    }
    internal val messages = Channel<String>(Channel.BUFFERED)
    internal var motionEnabled by mutableStateOf(true)
    internal var launchIntro = false
    internal var launchReady by mutableStateOf(false)
    internal var launchOrigin by mutableStateOf<Offset?>(null)
    internal var launchIconWidth by mutableFloatStateOf(0f)
    private val splashFallback = Runnable { launchReady = true }
    internal lateinit var db: Store
    internal lateinit var engine: Engine
    internal var revision by mutableIntStateOf(0)
    internal var themeMode by mutableStateOf(AppThemeMode.SYSTEM)
    internal var preparedRestore by mutableStateOf<JSONObject?>(null)
    internal var focusTaskId by mutableStateOf("")
    internal var focusMinutes by mutableStateOf("25")
    internal var focusPreset by mutableStateOf("custom")
    internal var focusShortBreak by mutableStateOf("5")
    internal var focusLongBreak by mutableStateOf("15")
    internal var focusCycles by mutableStateOf("4")
    internal var focusForward by mutableStateOf(false)
    internal var focusApps by mutableStateOf("[]")
    internal var createdPlace by mutableStateOf("")
    internal var selectedTab by mutableStateOf("today")
    private val mapEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) createdPlace = result.data?.getStringExtra("id").orEmpty()
        refresh()
    }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) act { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(db.exportJson().toByteArray(Charsets.UTF_8)) }; toast("已导出") }
    }
    private val exportSnapshot = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) act {
            val snapshot = db.latestRestoreSnapshot() ?: throw IllegalStateException("找不到恢复前数据")
            contentResolver.openOutputStream(uri, "wt")!!.use { output -> snapshot.inputStream().use { it.copyTo(output) } }
            toast("已导出")
        }
    }
    private val exportStatistics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) act { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(csv().toByteArray(Charsets.UTF_8)) }; toast("已导出") }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act {
            val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= 20_000_000) { "备份超过 20MB" }
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            preparedRestore = db.prepareImport(bytes.toString(Charsets.UTF_8))
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val tickRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pulse = object : Runnable {
        private var ticks = 0
        override fun run() {
            if (tickRunning.compareAndSet(false, true)) {
                ticker.execute {
                    try { engine.tick() }
                    catch (e: Exception) { android.util.Log.e("DisciplineCompose", "Refresh failed", e) }
                    finally { tickRunning.set(false) }
                }
            }
            if (++ticks % 15 == 0) refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchIntro = savedInstanceState == null && ValueAnimator.areAnimatorsEnabled()
        launchReady = !launchIntro || android.os.Build.VERSION.SDK_INT < 31
        if (launchIntro && android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                handler.removeCallbacks(splashFallback)
                splash.iconView?.let { icon ->
                    val position = IntArray(2)
                    icon.getLocationInWindow(position)
                    launchOrigin = Offset(position[0] + icon.width / 2f, position[1] + icon.height / 2f)
                    launchIconWidth = nativeLaunchMarkWidth(icon)
                }
                // Hand the existing system mark to the first live Compose frame; no timed startup hold.
                launchReady = true
                splash.remove()
            }
        }
        db = Store.get(this)
        engine = Engine(this)
        themeMode = AppThemeMode.fromValue(db.settings().optString("themeMode", "system"))
        focusMinutes = savedInstanceState?.getString("focusMinutes") ?: db.settings().optInt("focusMinutes", 25).toString()
        focusPreset = savedInstanceState?.getString("focusPreset") ?: "custom"
        focusShortBreak = savedInstanceState?.getString("focusShortBreak") ?: db.settings().optInt("shortBreak", 5).toString()
        focusLongBreak = savedInstanceState?.getString("focusLongBreak") ?: db.settings().optInt("longBreak", 15).toString()
        focusCycles = savedInstanceState?.getString("focusCycles") ?: db.settings().optInt("cycles", 4).toString()
        focusTaskId = savedInstanceState?.getString("focusTaskId") ?: db.one("state", "focusSelection")?.optString("task").orEmpty()
        focusForward = savedInstanceState?.getBoolean("focusForward") ?: false
        focusApps = savedInstanceState?.getString("focusApps") ?: db.settings().optJSONArray("defaultApps")?.toString() ?: "[]"
        selectedTab = savedInstanceState?.getString("selectedTab")?.takeIf {
            it in listOf("today", "timetable", "focus", "stats")
        } ?: "today"
        Scheduler.channels(this)
        setContent { DisciplineTheme(themeMode = themeMode) { DisciplineApp(this) } }
        // OEM splash callbacks and optional system integrations cannot hold the first live frame.
        if (!launchReady) handler.postDelayed(splashFallback, 1200)
        ticker.execute {
            try {
                engine.tick()
                Reminders.deliver(this, db, System.currentTimeMillis())
            } catch (error: Exception) {
                android.util.Log.e("DisciplineCompose", "Initial refresh failed", error)
                toast("后台更新暂未完成，可在运行与权限中检查")
            } finally {
                handler.post { if (!isFinishing && !isDestroyed) refresh() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        androidx.core.content.ContextCompat.registerReceiver(this, phaseReceiver,
            android.content.IntentFilter(FocusEvents.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        motionEnabled = ValueAnimator.areAnimatorsEnabled()
        refresh()
        FallbackBlockService.sync(this)
        FocusCompanionService.sync(this)
        handler.removeCallbacks(pulse)
        handler.postDelayed(pulse, 1000)
        if (db.settings().optBoolean("monitor") &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            AndroidCompat.startService(this, Intent(this, LocationService::class.java))
        }
    }

    override fun onPause() {
        visible = false
        unregisterReceiver(phaseReceiver)
        handler.removeCallbacks(pulse)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pulse)
        handler.removeCallbacks(splashFallback)
        ticker.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("focusMinutes", focusMinutes)
        outState.putString("focusPreset", focusPreset)
        outState.putString("focusShortBreak", focusShortBreak)
        outState.putString("focusLongBreak", focusLongBreak)
        outState.putString("focusCycles", focusCycles)
        outState.putString("focusTaskId", focusTaskId)
        outState.putBoolean("focusForward", focusForward)
        outState.putString("focusApps", focusApps)
        outState.putString("selectedTab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    internal fun refresh() {
        themeMode = AppThemeMode.fromValue(db.settings().optString("themeMode", "system"))
        revision++
    }
    internal fun exportJson() { exportBackup.launch("自律备份.json") }
    internal fun exportOldData() { exportSnapshot.launch("恢复前备份.json") }
    internal fun exportCsv() { exportStatistics.launch("自律统计.csv") }
    internal fun chooseBackup() { importBackup.launch(arrayOf("application/json")) }
    internal fun openMap(id: String? = null) {
        mapEditor.launch(Intent(this, ComposePlaceActivity::class.java).also { if (id != null) it.putExtra("id", id) })
    }
    private fun csvCell(value: String): String {
        val safe = if (value.matches(Regex("^[=+@-].*"))) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    private fun csv(): String = buildString {
        append("\uFEFF任务,开始,截止,状态,进度,目标,单位\r\n")
        db.all("instances").forEach { task ->
            append(listOf(csvCell(task.optString("name")), csvCell(timeText(task.optLong("start"))),
                csvCell(timeText(task.optLong("due"))), csvCell(status(task)),
                numberText(engine.progress(task, System.currentTimeMillis())),
                numberText(task.optDouble("target")), csvCell(unit(task))).joinToString(","))
            append("\r\n")
        }
    }
    internal fun toast(message: String) { messages.trySend(message) }
    internal fun act(block: () -> Unit): Boolean {
        return try { block(); refresh(); FocusCompanionService.sync(this); FallbackBlockService.sync(this); true }
        catch (e: Exception) { toast(e.message ?: "操作失败"); false }
    }

    internal fun startFocusFor(id: String, nav: NavHostController) {
        focusTaskId = id
        if (db.one("state", "focus") == null)
            db.put("state", Store.obj("id", "focusSelection", "task", id))
        openTab("focus", nav)
    }
    internal fun openTab(tab: String, nav: NavHostController) {
        selectedTab = tab
        if (nav.currentDestination?.route != "main") nav.navigate("main") {
            popUpTo("main") { inclusive = false }
            launchSingleTop = true
        }
    }
    internal fun startFocusDraft() = act {
        val minutes = focusMinutes.toIntOrNull() ?: throw IllegalArgumentException("请填写 1—720 分钟")
        require(minutes in 1..720) { "请填写 1—720 分钟" }
        val shortBreak = focusShortBreak.toIntOrNull() ?: throw IllegalArgumentException("请填写短休息分钟")
        val longBreak = focusLongBreak.toIntOrNull() ?: throw IllegalArgumentException("请填写长休息分钟")
        val cycles = focusCycles.toIntOrNull() ?: throw IllegalArgumentException("请填写轮次")
        require(shortBreak in 1..120 && longBreak in 1..180 && cycles in 1..12) { "休息或轮次超出范围" }
        val task = db.one("instances", focusTaskId)
        engine.startFocus(focusTaskId, task?.optString("place") ?: "", minutes, focusForward,
            jsonArray(focusApps), shortBreak, longBreak, cycles)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DisciplineApp(activity: ComposeMainActivity) {
    SystemBars(activity)
    val nav = rememberNavController()
    val taskTransitionId = rememberSaveable { mutableStateOf<String?>(null) }
    val currentEntry by nav.currentBackStackEntryAsState()
    DisposableEffect(currentEntry) {
        val entry = currentEntry
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && entry?.destination?.route != "task/{id}")
                taskTransitionId.value = null
        }
        entry?.lifecycle?.addObserver(observer)
        if (entry?.destination?.route != "task/{id}" &&
            entry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
            taskTransitionId.value = null
        onDispose { entry?.lifecycle?.removeObserver(observer) }
    }
    val snackbar = remember { SnackbarHostState() }
    val pageTravelPx = with(LocalDensity.current) { AppMotion.pageTravel.roundToPx() }
    LaunchedEffect(activity) { for (message in activity.messages) snackbar.showSnackbar(message) }
    CompositionLocalProvider(LocalMotionEnabled provides activity.motionEnabled,
        LocalTaskTransitionId provides taskTransitionId) {
    LaunchPresentation(activity.launchIntro, activity.launchReady, activity.launchOrigin,
        activity.launchIconWidth, onFinished = { activity.launchIntro = false }) {
    // Destination alpha must never expose the platform's light window background.
    SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val shared = this
    Box(Modifier.fillMaxSize()) {
    NavHost(navController = nav, startDestination = "main",
        enterTransition = {
            val task = initialState.destination.route == "task/{id}" || targetState.destination.route == "task/{id}"
            if (!activity.motionEnabled) EnterTransition.None
            else if (task) fadeIn(tween(AppMotion.taskShared, easing = AppMotion.easing))
            else fadeIn(tween(AppMotion.pageEnter, easing = AppMotion.easing)) +
                slideInHorizontally(tween(AppMotion.pageEnter, easing = AppMotion.easing)) { pageTravelPx }
        },
        exitTransition = {
            val task = initialState.destination.route == "task/{id}" || targetState.destination.route == "task/{id}"
            if (!activity.motionEnabled) ExitTransition.None
            else if (task) fadeOut(tween(AppMotion.sharedContentExit, easing = AppMotion.easing))
            else fadeOut(tween(AppMotion.pageExit, easing = AppMotion.easing)) +
                slideOutHorizontally(tween(AppMotion.pageExit, easing = AppMotion.easing)) { -pageTravelPx / 2 }
        },
        popEnterTransition = {
            val task = initialState.destination.route == "task/{id}" || targetState.destination.route == "task/{id}"
            if (!activity.motionEnabled) EnterTransition.None
            else if (task) fadeIn(tween(AppMotion.taskShared, easing = AppMotion.easing))
            else fadeIn(tween(AppMotion.pageEnter, easing = AppMotion.easing)) +
                slideInHorizontally(tween(AppMotion.pageEnter, easing = AppMotion.easing)) { -pageTravelPx }
        },
        popExitTransition = {
            val task = initialState.destination.route == "task/{id}" || targetState.destination.route == "task/{id}"
            if (!activity.motionEnabled) ExitTransition.None
            else if (task) fadeOut(tween(AppMotion.sharedContentExit, easing = AppMotion.easing))
            else fadeOut(tween(AppMotion.pageExit, easing = AppMotion.easing)) +
                slideOutHorizontally(tween(AppMotion.pageExit, easing = AppMotion.easing)) { pageTravelPx / 2 }
        }) {
        composable("main") {
            CompositionLocalProvider(LocalTaskMotion provides TaskMotionScope(shared, this)) {
                MainScreen(activity, nav)
            }
        }
        composable("task/{id}") { entry ->
            CompositionLocalProvider(LocalTaskMotion provides TaskMotionScope(shared, this)) {
                TaskDetail(activity, nav, entry.arguments?.getString("id").orEmpty())
            }
        }
        composable("plan/{id}") { entry ->
            CompositionLocalProvider(LocalTaskMotion provides TaskMotionScope(shared, this)) {
                PlanOverview(activity, nav, entry.arguments?.getString("id").orEmpty())
            }
        }
        composable("plans") { PlansPage(activity, nav) }
        composable("form/{id}") { entry ->
            PlanFormScreen(activity, nav, entry.arguments?.getString("id").orEmpty())
        }
        composable("settings") { SettingsScreen(activity, nav) }
        composable("app-limits") { AppLimitsScreen(activity, onBack = { nav.popBackStack() }) }
        composable("quotes") { QuotesScreen(activity, nav) }
        composable("events") { RecordScreen(activity, nav, "events", "操作与异常记录") }
        composable("penalties") { RecordScreen(activity, nav, "penalties", "限制记录") }
        composable("about") { AboutScreen(activity, nav) }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 68.dp))
    FocusPhaseDialog(activity)
    }
    }
    }
    }
}

@Composable
internal fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, lifted: Boolean = false) {
    ScrollChrome(lifted) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = AppSpacing.page),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            GlassIconButton("返回", onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = null,
                    modifier = Modifier.size(AppIconSize.action))
            }
            Spacer(Modifier.width(AppSpacing.xs))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    }
    }
}

@Composable
internal fun Screen(title: String, onBack: (() -> Unit)? = null, bottom: @Composable (() -> Unit)? = null,
                    scrollState: ScrollState? = null,
                    content: @Composable ColumnScope.() -> Unit) {
    val contentScroll = scrollState ?: rememberScrollState()
    val liftPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val lifted by remember(contentScroll, liftPx) { derivedStateOf { contentScroll.value > liftPx } }
    Scaffold(
        topBar = { ScreenHeader(title, onBack, scrollState != null && lifted) },
        bottomBar = { if (bottom != null) Box(Modifier.navigationBarsPadding()) { bottom() } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)
            .then(if (bottom == null) Modifier.navigationBarsPadding() else Modifier)
            .verticalScroll(contentScroll)
            .padding(start = AppSpacing.page, end = AppSpacing.page,
                top = AppSpacing.md, bottom = AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm), content = content)
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = AppShapes.card,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .48f))) {
        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs), content = content)
    }
}

@Composable
internal fun Section(text: String) {
    Text(text, Modifier.padding(top = 15.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun Primary(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                     onClick: () -> Unit) {
    GlassPrimaryButton(label, modifier, enabled = enabled, onClick = onClick)
}

@Composable
internal fun Secondary(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassSecondaryButton(label, modifier, onClick = onClick)
}

@Composable
internal fun Caption(text: String) { Text(text, style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant) }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MainScreen(activity: ComposeMainActivity, nav: NavHostController) {
    val tab = activity.selectedTab
    val tabTransition = androidx.compose.animation.core.updateTransition(tab, label = "导航统一时间轴")
    var todayRange by rememberSaveable { mutableIntStateOf(0) }
    val taskTransitionId = LocalTaskTransitionId.current
    val haptics = LocalHapticFeedback.current
    val titles = linkedMapOf("today" to "今天", "timetable" to "课表", "focus" to "专注", "stats" to "统计")
    val tabOrder = titles.keys.toList()
    var navDragging by remember { mutableStateOf(false) }
    var navDragPosition by remember { mutableFloatStateOf(0f) }
    var navCandidate by remember { mutableIntStateOf(tabOrder.indexOf(tab).coerceAtLeast(0)) }
    val tabTravelPx = with(LocalDensity.current) { AppMotion.tabTravel.roundToPx() }
    val leaving = LocalTaskMotion.current?.visibility?.transition?.targetState == androidx.compose.animation.EnterExitState.PostExit
    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
    BackHandler(enabled = tab != "today" && currentRoute == "main") {
        taskTransitionId?.value = null
        activity.selectedTab = "today"
    }
    val todayScroll = rememberScrollState()
    val weekScroll = rememberScrollState()
    val timetableScroll = rememberScrollState()
    var timetableLifted by rememberSaveable { mutableStateOf(false) }
    val focusScroll = rememberScrollState()
    val statsScroll = rememberScrollState()
    val activeScroll = when (tab) {
        "timetable" -> timetableScroll
        "focus" -> focusScroll
        "stats" -> statsScroll
        else -> if (todayRange == 0) todayScroll else weekScroll
    }
    val liftPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val lifted by remember(tab, activeScroll, liftPx, timetableLifted) {
        derivedStateOf { if (tab == "timetable") timetableLifted else activeScroll.value > liftPx }
    }
    val tabStates = rememberSaveableStateHolder()
    ScreenExitGuard(leaving) {
    Scaffold(
        topBar = {
            ScrollChrome(lifted) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                tabTransition.AnimatedContent(modifier = Modifier.weight(1f).clipToBounds(),
                    transitionSpec = {
                        (fadeIn(tween(if (activity.motionEnabled) AppMotion.state else 0, easing = AppMotion.easing)) +
                            slideInVertically(tween(if (activity.motionEnabled) AppMotion.spatial else 0,
                                easing = AppMotion.easing)) { it / 6 }) togetherWith
                            (fadeOut(tween(if (activity.motionEnabled) AppMotion.outgoing else 0)) +
                                slideOutVertically(tween(if (activity.motionEnabled) AppMotion.spatial else 0,
                                    easing = AppMotion.easing)) { -it / 6 })
                    }) { shown ->
                    ScreenExitGuard(shown != tab, Modifier.wrapContentSize(Alignment.CenterStart)) {
                        if (shown == "today") {
                            ModeSwitch(if (todayRange == 0) "今天" else "这周",
                                if (todayRange == 0) "这周" else "今天", MaterialTheme.typography.headlineMedium,
                                Modifier.testTag("today-range-switch")) { todayRange = 1 - todayRange }
                        } else Text(titles[shown].orEmpty(), Modifier.heightIn(min = 48.dp).wrapContentHeight()
                            .semantics { heading() },
                            style = MaterialTheme.typography.headlineMedium)
                    }
                }
                GlassIconButton("设置", onClick = { nav.navigate("settings") }) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = null, modifier = Modifier.size(AppIconSize.action))
                }
            }
            }
        },
        bottomBar = {
            Box(Modifier.navigationBarsPadding()) {
            BoxWithConstraints(Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .97f))) {
                val itemWidth = maxWidth / titles.size
                val thumbWidth = minOf(64.dp, itemWidth - 12.dp)
                val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
                val thumbWidthPx = with(LocalDensity.current) { thumbWidth.toPx() }
                val maxThumbX = (itemWidthPx * tabOrder.size - thumbWidthPx).coerceAtLeast(0f)
                val thumbX = animateFloatAsState(
                    if (navDragging) navDragPosition else
                        itemWidthPx * tabOrder.indexOf(tab).coerceAtLeast(0) + (itemWidthPx - thumbWidthPx) / 2f,
                    animationSpec = if (!activity.motionEnabled || navDragging) snap() else spring(
                        stiffness = AppMotion.navigationSnapStiffness,
                        dampingRatio = AppMotion.navigationSnapDamping),
                    label = "底部导航选中位置")
                Box(Modifier.offset(y = 12.dp).size(thumbWidth, 32.dp)
                    .graphicsLayer { translationX = thumbX.value }
                    .clip(RoundedCornerShape(18.dp))
                    .testTag("nav-selection")
                    .background(AppColors.current.navigationFill))
            Row(Modifier.fillMaxWidth().height(80.dp).then(
                if (activity.motionEnabled && richMotionEnabled()) Modifier.pointerInput(
                    itemWidthPx, thumbWidthPx, tab) {
                    detectHorizontalDragGestures(
                        onDragStart = { start ->
                            navDragging = true
                            navCandidate = tabOrder.indexOf(tab).coerceAtLeast(0)
                            navDragPosition = (navCandidate * itemWidthPx +
                                (itemWidthPx - thumbWidthPx) / 2f).coerceIn(0f, maxThumbX)
                        },
                        onHorizontalDrag = { change, amount ->
                            navDragPosition = (navDragPosition + amount).coerceIn(0f, maxThumbX)
                            val candidate = ((navDragPosition + thumbWidthPx / 2f) /
                                itemWidthPx).toInt().coerceIn(0, tabOrder.lastIndex)
                            if (candidate != navCandidate) {
                                navCandidate = candidate
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            val route = tabOrder[navCandidate]
                            navDragging = false
                            if (route != activity.selectedTab) {
                                taskTransitionId?.value = null
                                activity.selectedTab = route
                            }
                        },
                        onDragCancel = { navDragging = false }
                    )
                } else Modifier)) {
                titles.entries.forEach { (route, label) ->
                    val selected = tab == route
                    val visualSelected = if (navDragging) navCandidate == tabOrder.indexOf(route) else selected
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val iconScale = animateFloatAsState(if (pressed) .98f else if (visualSelected) 1.03f else 1f,
                        animationSpec = tween(if (activity.motionEnabled) AppMotion.navigationIcon else 0, easing = AppMotion.easing),
                        label = "$label 图标选中")
                    val selectedProgress = animateFloatAsState(if (visualSelected) 1f else 0f,
                        tween(if (activity.motionEnabled) AppMotion.navigationIcon else 0, easing = AppMotion.easing),
                        label = "$label 导航反色")
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(
                        selected = selected, role = Role.Tab, interactionSource = interaction,
                        indication = null, onClick = { if (!selected) {
                            taskTransitionId?.value = null
                            activity.selectedTab = route
                        } }).semantics { contentDescription = label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        NavigationMark(route, { selectedProgress.value },
                            Modifier.size(AppIconSize.navigation).testTag("nav-mark-$route")
                                .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value })
                        Spacer(Modifier.height(4.dp))
                        NavigationLabel(label, visualSelected, Modifier.clearAndSetSemantics { })
                    }
                }
            }
            }
            }
        }, floatingActionButton = {
            if (tab == "today") CreatePlanAction { nav.navigate("form/new") }
        }, containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        tabTransition.AnimatedContent(modifier = Modifier.fillMaxSize().padding(padding).clipToBounds(),
            transitionSpec = {
                val direction = if (tabOrder.indexOf(targetState) >= tabOrder.indexOf(initialState)) 1 else -1
                (fadeIn(tween(if (activity.motionEnabled) AppMotion.tabEnter else 0, easing = AppMotion.easing), initialAlpha = .92f) +
                    slideInHorizontally(tween(if (activity.motionEnabled) AppMotion.tabEnter else 0,
                        easing = AppMotion.easing)) { direction * tabTravelPx }) togetherWith
                    (fadeOut(tween(if (activity.motionEnabled) AppMotion.outgoing else 0, easing = AppMotion.easing)) +
                        slideOutHorizontally(tween(if (activity.motionEnabled) AppMotion.tabEnter else 0,
                            easing = AppMotion.easing)) { -direction * tabTravelPx })
            }) { shownTab ->
        tabStates.SaveableStateProvider(shownTab) {
            ScreenExitGuard(leaving || shownTab != tab) {
                Column(Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .then(if (shownTab == "today" || shownTab == "timetable" || shownTab == "focus") Modifier else Modifier.verticalScroll(when (shownTab) {
                        "timetable" -> timetableScroll
                        "focus" -> focusScroll
                        "stats" -> statsScroll
                        else -> todayScroll
                    })).then(if (shownTab == "focus") Modifier else Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (shownTab) {
                            "timetable" -> TimetableScreen(activity) { timetableLifted = it }
                            "focus" -> FocusScreen(activity, nav, focusScroll)
                            "stats" -> StatsScreen(activity, nav)
                            else -> TodayScreen(activity, nav, todayRange, todayScroll, weekScroll)
                        }
                    }
            }
        }
        }
    }
    }
}

private data class HomeHero(val key: String, val task: JSONObject?, val done: Int)

private val planOrderBounds = BoundsTransform { _, _ ->
    tween(AppMotion.selection, easing = AppMotion.easing)
}

private fun ComposeMainActivity.togglePlanPin(planId: String): Boolean =
    act {
        val plan = db.one("plans", planId) ?: throw IllegalArgumentException("找不到计划")
        Store.set(plan, "pinned", !plan.optBoolean("pinned"))
        db.put("plans", plan)
    }

private fun ComposeMainActivity.dismissToday(task: JSONObject, dismissed: Boolean) {
    act {
        val saved = db.one("instances", task.optString("id")) ?: throw IllegalArgumentException("找不到任务")
        Store.set(saved, "dismissedToday", dismissed)
        db.put("instances", saved)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TodayScreen(activity: ComposeMainActivity, nav: NavHostController, range: Int,
                        todayScroll: ScrollState, weekScroll: ScrollState) {
    val travel = with(LocalDensity.current) { 6.dp.roundToPx() }
    ViewTransitionLayout {
        AnimatedContent(range, transitionSpec = {
            (fadeIn(tween(if (activity.motionEnabled) 180 else 0), initialAlpha = .65f) +
                slideInVertically(tween(if (activity.motionEnabled) AppMotion.rangeChange else 0,
                    easing = AppMotion.easing)) { if (targetState == 1) travel else -travel }) togetherWith
                fadeOut(tween(if (activity.motionEnabled) 60 else 0))
        }, modifier = Modifier.fillMaxSize(), label = "今天与这周连续切换") { shown ->
            CompositionLocalProvider(LocalViewMotion provides ViewMotionScope(this@ViewTransitionLayout,
                this@AnimatedContent, AppMotion.rangeChange, "today-range")) {
                ScreenExitGuard(shown != range) {
                    Column(Modifier.fillMaxSize().verticalScroll(if (shown == 0) todayScroll else weekScroll)
                        .padding(bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TodayContent(activity, nav, shown)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayContent(activity: ComposeMainActivity, nav: NavHostController, range: Int) {
    val date = Rules.date(System.currentTimeMillis())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        if (range == 0) Caption(date.format(
            java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)))
        else Spacer(Modifier.width(1.dp))
        TextButton(onClick = { nav.navigate("plans") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("管理计划")
        }
    }
    if (range == 1) {
        WeeklyPlansScreen(activity, nav)
        return
    }
    val revision = activity.revision
    val now = remember(revision) { System.currentTimeMillis() }
    val pinned = remember(revision) { activity.db.all("plans").filter { it.optBoolean("pinned") }
        .map { it.optString("id") }.toSet() }
    val tasks = remember(revision) { activity.engine.current(now).sortedWith(
        compareBy<JSONObject> { it.optString("planId") !in pinned }.thenBy { it.optLong("due") }) }
    val done = tasks.count { activity.done(it) }
    val active = tasks.filter { !activity.done(it) && it.optString("status") in listOf("open", "review") }
    val completed = tasks.filter { activity.done(it) && !it.optBoolean("dismissedToday") }
    val settled = tasks.filter { !activity.done(it) && it.optString("status") !in listOf("open", "review") }
    val next = active.firstOrNull()
    val hero = HomeHero(when {
        tasks.isEmpty() -> "empty"
        next == null -> if (settled.isEmpty()) "complete" else "settled"
        else -> "task:${next.optString("id")}"
    }, next, done)
    var completedOpen by rememberSaveable { mutableStateOf(false) }
    var recentlyDismissed by remember { mutableStateOf<JSONObject?>(null) }
    val glyphPositions = remember { mutableMapOf<String, Offset>() }
    var heroBoxOrigin by remember { mutableStateOf(Offset.Zero) }
    var progressOrigin by remember { mutableStateOf(Offset.Zero) }
    var progressWidth by remember { mutableFloatStateOf(0f) }
    var handoffFrom by remember { mutableStateOf(Offset.Zero) }
    var handoffTargetFraction by remember { mutableFloatStateOf(0f) }
    var handoffCount by remember { mutableIntStateOf(0) }
    var handoffVisible by remember { mutableStateOf(false) }
    val handoffProgress = remember { Animatable(0f) }
    val view = LocalView.current
    val bottomInset = with(LocalDensity.current) { 88.dp.toPx() }
    val handoffColor = AppColors.current.chartFocus
    LaunchedEffect(handoffCount) {
        if (handoffCount > 0) {
            handoffProgress.snapTo(0f)
            handoffVisible = true
            handoffProgress.animateTo(1f, tween(AppMotion.handoff - AppMotion.handoffMark,
                delayMillis = AppMotion.handoffMark, easing = AppMotion.easing))
            handoffVisible = false
            handoffCount = 0
        }
    }
    fun completeHandoff(id: String) {
        val from = glyphPositions[id] ?: return
        if (!activity.motionEnabled || progressWidth <= 0f || tasks.isEmpty()) return
        val to = progressOrigin + Offset(progressWidth * ((done + 1f) / tasks.size).coerceAtMost(1f), 2f)
        if (from.y !in 0f..(view.height - bottomInset) ||
            to.y !in 0f..(view.height - bottomInset)) return
        handoffFrom = from - heroBoxOrigin
        handoffTargetFraction = ((done + 1f) / tasks.size).coerceAtMost(1f)
        handoffCount++
    }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { heroBoxOrigin = it.positionInRoot() }) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (hero.task != null) Section("下一步")
    val heroId = hero.task?.optString("id")
    val navigationShared = richMotionEnabled() && heroId != null && LocalTaskTransitionId.current?.value == heroId
    val shell = Modifier
        .then(if (heroId != null) Modifier.taskShared("card-${heroId}", navigationShared, bounds = true)
            .then(if (!navigationShared) Modifier.viewShared("task-${heroId}", AppShapes.glass) else Modifier)
            else Modifier)
        .animateContentSize(tween(if (activity.motionEnabled) AppMotion.expand else 0, easing = AppMotion.easing))
    // Keep the material and boundary outside the changing content: one continuous shell.
    GlassTaskPanel(shell) {
        AnimatedContent(targetState = hero, contentKey = { it.key }, label = "首页主任务",
            transitionSpec = {
                val travel = if (activity.motionEnabled) AppMotion.expand else 0
                ((fadeIn(tween(if (activity.motionEnabled) AppMotion.stateChange else 0, easing = AppMotion.easing)) +
                    slideInVertically(tween(travel, easing = AppMotion.easing)) { it / 40 }) togetherWith
                    fadeOut(tween(if (activity.motionEnabled) 100 else 0)))
                .using(androidx.compose.animation.SizeTransform(clip = false,
                    sizeAnimationSpec = { _, _ -> tween(0) }))
            }) { shown ->
            Column(Modifier.semantics { if (shown.key != hero.key) hideFromAccessibility() },
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    shown.key == "empty" -> {
                        Text("今天，专注一件事", style = MaterialTheme.typography.titleLarge)
                        Caption("点右下角的加号，安排一件要做的事。")
                        Image(painterResource(R.drawable.empty_desk_mist), contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(96.dp))
                    }
                    shown.task != null -> TaskCard(activity, nav, shown.task, true,
                        onCompleted = { completeHandoff(shown.task.optString("id")) },
                        onGlyphPosition = { glyphPositions[shown.task.optString("id")] = it }, embedded = true)
                    else -> {
                        Text(if (shown.key == "complete") "今日任务已完成" else "今日任务已到期",
                            style = MaterialTheme.typography.titleLarge)
                        Caption(if (shown.key == "complete") "已完成 ${shown.done} 项任务。"
                            else "下方保留未完成与待核验任务。")
                    }
                }
            }
        }
    }
    if (tasks.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("今日进度", style = MaterialTheme.typography.titleMedium)
            Text("$done / ${tasks.size}", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                style = AppTypography.number)
        }
        ProgressTrack(done.toFloat() / tasks.size, "今日完成 $done 项，共 ${tasks.size} 项", Modifier.fillMaxWidth()
            .onGloballyPositioned {
                progressOrigin = it.positionInRoot()
                progressWidth = it.size.width.toFloat()
            })
    }
    }
    if (handoffVisible) Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
        val fraction = handoffProgress.value
        val handoffTo = progressOrigin + Offset(progressWidth * handoffTargetFraction, 2f) - heroBoxOrigin
        val center = Offset(
            handoffFrom.x + (handoffTo.x - handoffFrom.x) * fraction,
            handoffFrom.y + (handoffTo.y - handoffFrom.y) * fraction)
        drawCircle(handoffColor.copy(alpha = 1f - .2f * fraction),
            radius = (6f - 2f * fraction).dp.toPx(), center = center)
    }
    }
    if (tasks.isNotEmpty()) {
        val remaining = active.filter { it !== next }
        if (remaining.isNotEmpty()) Section("接下来")
        remaining.forEach { task ->
            key(task.optString("id")) { TaskCard(activity, nav, task, false) }
        }
        if (settled.isNotEmpty()) {
            Section("需要处理")
            settled.forEach { task -> key(task.optString("id")) { TaskCard(activity, nav, task, false) } }
        }
        if (completed.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .quietClickable(if (completedOpen) "收起已完成任务" else "展开已完成任务") {
                    completedOpen = !completedOpen
                }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已完成 ${completed.size}", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (completedOpen) "收起" else "查看", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    DisclosureMark(completedOpen)
                }
            }
            AnimatedVisibility(completedOpen,
                enter = expandVertically(tween(if (activity.motionEnabled) AppMotion.expand else 0, easing = AppMotion.easing)) +
                    fadeIn(tween(if (activity.motionEnabled) AppMotion.contentEnter else 0, easing = AppMotion.easing)),
                exit = shrinkVertically(tween(if (activity.motionEnabled) AppMotion.collapse else 0, easing = AppMotion.easing)) +
                    fadeOut(tween(if (activity.motionEnabled) AppMotion.contentExit else 0, easing = AppMotion.easing))) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    completed.forEach { task -> key(task.optString("id")) {
                        val dismiss = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismiss.currentValue) {
                            if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                recentlyDismissed = task
                                activity.dismissToday(task, true)
                            }
                        }
                        SwipeToDismissBox(state = dismiss, enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(Modifier.fillMaxSize().padding(end = 18.dp),
                                    contentAlignment = Alignment.CenterEnd) {
                                    Text("从日常列表移除", Modifier.clearAndSetSemantics {},
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }) { TaskCard(activity, nav, task, false,
                                onDismissToday = {
                                    recentlyDismissed = task
                                    activity.dismissToday(task, true)
                                }) }
                    } }
                }
            }
        }
        if (recentlyDismissed != null) {
            TextButton(onClick = {
                recentlyDismissed?.let { activity.dismissToday(it, false) }
                recentlyDismissed = null
                completedOpen = true
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("撤销移除") }
        }
    }
    Secondary("开始专注") { activity.openTab("focus", nav) }
    val location = remember(revision) { activity.db.one("state", "location") }
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).quietClickable("查看地点监测设置") { nav.navigate("settings") },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(if (activity.db.settings().optBoolean("monitor")) "地点监测已开启" else "地点监测未开启")
        Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    location?.optString("status")?.takeIf(String::isNotBlank)?.let { Caption(it) }
    if (activity.db.settings().optBoolean("trial")) Caption("任务限制仅记录，暂不拦截")
    val settings = activity.db.settings()
    val quotes = settings.optString("quotes").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val quote = if (quotes.isEmpty()) "" else quotes[if (settings.optBoolean("rotate"))
        Math.floorMod(Rules.date(now).toEpochDay(), quotes.size.toLong()).toInt() else 0]
    if (quote.isNotBlank()) Text("“ $quote ”", style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun WeeklyPlansScreen(activity: ComposeMainActivity, nav: NavHostController) {
    val revision = activity.revision
    val today = remember(revision) { Rules.date(System.currentTimeMillis()) }
    val monday = Rules.monday(today)
    val weekEnd = monday.plusDays(7)
    val weekStartMillis = Rules.at(monday, 0)
    val weekEndMillis = Rules.at(weekEnd, 0)
    val plans = remember(revision) { activity.db.all("plans").associateBy { it.optString("id") } }
    val allWeekTasks = remember(revision) { activity.db.all("instances").filter {
        it.optLong("start") < weekEndMillis && it.optLong("due") > weekStartMillis
    } }
    val tasks = allWeekTasks.filter { !it.optBoolean("dismissedToday") }
    val completed = tasks.filter { activity.done(it) }
    val pending = tasks - completed.toSet()
    val weekly = pending.filter {
        plans[it.optString("planId")]?.optString("cycle") == "weekly" ||
            Rules.date(it.optLong("start")) != Rules.date((it.optLong("due") - 1).coerceAtLeast(0))
    }
    val daily = pending - weekly.toSet()
    var removed by remember { mutableStateOf<JSONObject?>(null) }
    var completedOpen by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("本周进度", style = MaterialTheme.typography.titleMedium)
            Caption("${monday.format(java.time.format.DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}—" +
                monday.plusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)))
        }
        if (allWeekTasks.isNotEmpty()) Text("${allWeekTasks.count { activity.done(it) }} / ${allWeekTasks.size}",
            style = AppTypography.number.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
    }
    if (allWeekTasks.isNotEmpty()) ProgressTrack(
        allWeekTasks.count { activity.done(it) }.toFloat() / allWeekTasks.size,
        "本周完成 ${allWeekTasks.count { activity.done(it) }} 项，共 ${allWeekTasks.size} 项")
    if (tasks.isEmpty()) {
        Panel {
            Text(if (allWeekTasks.isEmpty()) "这周暂无计划" else "这周的任务已从列表移除",
                style = MaterialTheme.typography.titleMedium)
            Caption(if (allWeekTasks.isEmpty()) "点右下角的加号创建计划，这里会按日期整理。"
                else "完成记录仍保留在统计中。")
        }
    }
    if (weekly.isNotEmpty()) {
        Section("本周累计与跨日目标")
        weekly.sortedBy { it.optLong("due") }.forEach { task ->
            key(task.optString("id")) { TaskCard(activity, nav, task, false,
                onDismissToday = { removed = task; activity.dismissToday(task, true) }) }
        }
    }
    var emptyDays = 0
    (0..6).forEach { offset ->
        val date = monday.plusDays(offset.toLong())
        val dayTasks = daily.filter { task ->
            val plan = plans[task.optString("planId")]
            val groupingDate = if (plan?.optString("cycle") == "once")
                Rules.date((task.optLong("due") - 1).coerceAtLeast(0)) else Rules.date(task.optLong("start"))
            groupingDate == date
        }.sortedWith(compareBy<JSONObject> { !plans[it.optString("planId")].let { plan -> plan?.optBoolean("pinned") == true } }
            .thenBy { it.optLong("due") })
        if (dayTasks.isEmpty()) emptyDays++ else {
            Section(date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) +
                if (date == today) " · 今天" else "")
            dayTasks.forEach { task -> key(task.optString("id")) {
                TaskCard(activity, nav, task, false,
                    onDismissToday = { removed = task; activity.dismissToday(task, true) })
            } }
        }
    }
    if (emptyDays > 0 && tasks.isNotEmpty())
        Caption("本周另有 $emptyDays 天暂无安排")
    if (completed.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .quietClickable(if (completedOpen) "收起已完成任务" else "展开已完成任务") {
                completedOpen = !completedOpen
            }, horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("已完成 ${completed.size}", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (completedOpen) "收起" else "查看", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                DisclosureMark(completedOpen)
            }
        }
        AnimatedVisibility(completedOpen,
            enter = expandVertically(tween(if (activity.motionEnabled) AppMotion.expand else 0, easing = AppMotion.easing)),
            exit = shrinkVertically(tween(if (activity.motionEnabled) AppMotion.collapse else 0, easing = AppMotion.easing))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                completed.forEach { task -> key(task.optString("id")) {
                    TaskCard(activity, nav, task, false,
                        onDismissToday = { removed = task; activity.dismissToday(task, true) })
                } }
            }
        }
    }
    if (removed != null) TextButton(onClick = {
        removed?.let { activity.dismissToday(it, false) }
        removed = null
    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("撤销移除") }
}

@Composable
internal fun TaskCard(activity: ComposeMainActivity, nav: NavHostController, task: JSONObject,
    featured: Boolean, onDismissToday: (() -> Unit)? = null,
    onCompleted: (() -> Unit)? = null, onGlyphPosition: ((Offset) -> Unit)? = null,
    embedded: Boolean = false) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val visual = task.visual()
    val taskTransitionId = LocalTaskTransitionId.current
    var locallyCompleted by remember(visual.id) { mutableStateOf(false) }
    var open by rememberSaveable(visual.id) { mutableStateOf(featured) }
    val shared = richMotionEnabled() && taskTransitionId?.value == visual.id
    fun openDetail() {
        taskTransitionId?.value = visual.id
        nav.navigate("task/${visual.id}")
    }
    val modifier = Modifier.testTag("task-card-${visual.id}")
        .then(if (!embedded) Modifier.taskShared("card-${visual.id}", shared, bounds = true)
            .then(if (!shared) Modifier.viewShared("task-${visual.id}") else Modifier) else Modifier)
        .pressableCard(shape = if (featured) AppShapes.glass else AppShapes.card,
            clipContent = !embedded,
            onClickLabel = "查看${visual.name}详情") { openDetail() }
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TaskTypeGlyph(visual.metric, Modifier.taskShared("glyph-${visual.id}", shared)
                .onGloballyPositioned { coordinates ->
                    onGlyphPosition?.invoke(coordinates.positionInRoot() + Offset(
                        coordinates.size.width / 2f, coordinates.size.height / 2f))
                },
                if (featured) 56.dp else 42.dp, activity.done(task) || locallyCompleted)
            Text(visual.name, Modifier.weight(1f).taskShared("title-${visual.id}", shared, bounds = true),
                style = MaterialTheme.typography.titleMedium,
                fontSize = if (featured) 20.sp else 17.sp,
                fontWeight = if (featured) FontWeight.Bold else FontWeight.SemiBold,
                textDecoration = if (activity.done(task) || locallyCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough
                    else null)
            GlassIconButton(if (open) "收起任务" else "展开任务",
                modifier = Modifier.taskTransitionAccessory(shared),
                state = if (open) "已展开" else "已收起", onClick = { open = !open }) {
                DisclosureMark(open)
            }
        }
        val status = if (locallyCompleted) "已完成" else activity.status(task)
        val summary = status to activity.amount(task)
        AnimatedContent(targetState = summary, label = "任务状态", transitionSpec = {
            fadeIn(tween(if (activity.motionEnabled) AppMotion.contentEnter else 0, easing = AppMotion.easing)) togetherWith
                fadeOut(tween(if (activity.motionEnabled) AppMotion.contentExit else 0, easing = AppMotion.easing))
        }) { shown ->
            Text("${shown.first} · ${shown.second}",
                Modifier.taskShared("status-${visual.id}", shared, bounds = true)
                    .semantics { if (shown != summary) hideFromAccessibility() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val target = task.optDouble("target", 1.0).coerceAtLeast(.001)
        val actualProgress = (activity.engine.progress(task, System.currentTimeMillis()) / target)
            .toFloat().coerceIn(0f, 1f)
        if (featured) {
            Caption("截止 ${timeText(task.optLong("due"))}")
            ProgressTrack(actualProgress, "${visual.name}完成进度")
        }
        val taskAction: @Composable () -> Unit = {
            if (!activity.engine.hasChildren(task)) when (visual.action) {
                "标记完成" -> GlassPrimaryButton("标记完成",
                    Modifier.fillMaxWidth().taskShared("action-${visual.id}", shared),
                    state = if (locallyCompleted) GlassButtonState.Success else GlassButtonState.Idle,
                    iconRes = taskActionIcon("标记完成")) {
                    if (activity.act { activity.engine.manual(task, 1.0, "手动确认完成", false) }) {
                        locallyCompleted = true
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onCompleted?.invoke()
                    }
                }
                "记录数量" -> GlassPrimaryButton("记录数量",
                    Modifier.fillMaxWidth().taskShared("action-${visual.id}", shared),
                    iconRes = taskActionIcon("记录数量")) { openDetail() }
                "开始专注" -> GlassPrimaryButton("开始专注",
                    Modifier.fillMaxWidth().taskShared("action-${visual.id}", shared),
                    iconRes = taskActionIcon("开始专注")) {
                    activity.startFocusFor(visual.id, nav)
                }
            }
        }
        AnimatedVisibility(open,
            enter = expandVertically(tween(if (activity.motionEnabled) AppMotion.expand else 0, easing = AppMotion.easing)) + fadeIn(tween(if (activity.motionEnabled) AppMotion.contentEnter else 0, easing = AppMotion.easing)),
            exit = shrinkVertically(tween(if (activity.motionEnabled) AppMotion.collapse else 0, easing = AppMotion.easing)) + fadeOut(tween(if (activity.motionEnabled) AppMotion.contentExit else 0, easing = AppMotion.easing))) {
            ScreenExitGuard(!open, Modifier.fillMaxWidth()) {
            Column(Modifier.then(if (featured) Modifier.taskTransitionAccessory(shared) else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!featured) {
                    Caption("截止 ${timeText(task.optLong("due"))}")
                    ProgressTrack(actualProgress, "${visual.name}完成进度")
                }
                Caption("有效时段 ${clockText(task.optInt("from"))}—${clockText(task.optInt("until", 1440))}")
                if (task.optString("status") == "open") {
                    val pinned = activity.db.one("plans", task.optString("planId"))?.optBoolean("pinned") == true
                    TextButton(onClick = { activity.togglePlanPin(task.optString("planId")) },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (pinned) "取消置顶" else "置顶计划")
                    }
                }
                if (activity.done(task) && !featured) {
                    TextButton(onClick = { (onDismissToday ?: { activity.dismissToday(task, true) })() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("从日常列表移除") }
                }
                if (!featured) taskAction()
            }
            }
        }
        if (featured) taskAction()
    }
    if (embedded) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    else if (featured) GlassTaskPanel(modifier, content) else Panel(modifier, content)
}

@Composable
private fun PlansPage(activity: ComposeMainActivity, nav: NavHostController) {
    Screen("管理计划", onBack = { nav.popBackStack() }) { PlansScreen(activity, nav) }
}

@Composable
private fun PlansScreen(activity: ComposeMainActivity, nav: NavHostController) {
    val revision = activity.revision
    val taskTransitionId = LocalTaskTransitionId.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selectedPlace by rememberSaveable { mutableStateOf("") }
    val places = remember(revision) { activity.db.all("places").filter { !it.optBoolean("archived") } }
    LaunchedEffect(places.map { it.optString("id") }) {
        if (selectedPlace.isNotEmpty() && places.none { it.optString("id") == selectedPlace }) selectedPlace = ""
    }
    val plans = remember(revision) { activity.db.all("plans") }
    val instances = remember(revision) { activity.db.all("instances") }
    var exitingPlan by remember { mutableStateOf<JSONObject?>(null) }
    var exitingCurrent by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(exitingPlan?.optString("id"), activity.motionEnabled) {
        if (exitingPlan != null) {
            kotlinx.coroutines.delay(if (activity.motionEnabled) AppMotion.collapse.toLong() + 40 else 0)
            exitingPlan = null
            exitingCurrent = null
        }
    }
    GlassTaskPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("我的计划", style = MaterialTheme.typography.titleLarge)
                Caption("${plans.size} 项计划，按状态查看与执行")
            }
            Secondary("新建计划", Modifier.widthIn(min = 88.dp)) { nav.navigate("form/new") }
        }
    }
    DraggableSegment(listOf("全部", "进行中", "已完成"), filter, activity.motionEnabled) { filter = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Caption("按地点查看计划")
        Secondary("地图与地点") { activity.openMap() }
    }
    if (places.isNotEmpty()) SelectField("地点筛选", listOf("全部地点") + places.map { it.optString("name") },
        (places.indexOfFirst { it.optString("id") == selectedPlace } + 1).coerceAtLeast(0)) {
        selectedPlace = if (it == 0) "" else places[it - 1].optString("id")
    }
    if (selectedPlace.isNotEmpty()) Secondary("查看所选地点范围") { activity.openMap(selectedPlace) }
    val visible = plans.filter { plan ->
        val periods = instances.filter { it.optString("planId") == plan.optString("id") }
        val completed = periods.isNotEmpty() && periods.none { it.optString("status") == "open" } &&
            activity.done(periods.maxByOrNull { it.optLong("due") }!!)
        (when (filter) {
            1 -> !plan.optBoolean("archived") && periods.any { it.optString("status") == "open" }
            2 -> completed
            else -> true
        }) &&
            (selectedPlace.isEmpty() || plan.optString("place") == selectedPlace)
    }.sortedWith(compareBy<JSONObject> { !it.optBoolean("pinned") }
        .thenBy { it.optString("name") })
    if (visible.isEmpty() && exitingPlan == null) Panel {
        Image(painterResource(R.drawable.empty_desk_mist), contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(120.dp))
        Text(if (plans.isEmpty()) "暂无计划" else "没有符合条件的计划")
        Caption(if (plans.isEmpty()) "添加计划，设定完成条件与执行时间。" else "切换状态或地点筛选查看其他计划。")
        if (plans.isEmpty()) Primary("创建计划") { nav.navigate("form/new") }
    }
    LookaheadScope {
    val presented = exitingPlan?.takeIf { leaving -> visible.none { it.optString("id") == leaving.optString("id") } }
        ?.let { (visible + it).sortedWith(compareBy<JSONObject> { plan -> !plan.optBoolean("pinned") }
            .thenBy { plan -> plan.optString("name") }) } ?: visible
    presented.forEach { plan ->
        key(plan.optString("id")) {
        var expanded by rememberSaveable(plan.optString("id")) { mutableStateOf(false) }
        var deleteConfirm by remember { mutableStateOf(false) }
        var pinPulse by remember { mutableIntStateOf(0) }
        val pinLift = remember { Animatable(0f) }
        LaunchedEffect(pinPulse) {
            if (pinPulse > 0) {
                pinLift.snapTo(1f)
                pinLift.animateTo(0f, spring(stiffness = AppMotion.navigationSnapStiffness,
                    dampingRatio = AppMotion.navigationSnapDamping))
            }
        }
        val current = if (exitingPlan?.optString("id") == plan.optString("id")) exitingCurrent
            else instances.filter { it.optString("planId") == plan.optString("id") &&
                it.optString("status") == "open" }.minByOrNull { it.optLong("start") }
        val placeName = places.firstOrNull { it.optString("id") == plan.optString("place") }
            ?.optString("name")
        val progress = current?.let { (activity.engine.progress(it, System.currentTimeMillis()) /
            it.optDouble("target", 1.0)).toFloat().coerceIn(0f, 1f) } ?: 0f
        AnimatedVisibility(visible = exitingPlan?.optString("id") != plan.optString("id"),
            enter = EnterTransition.None,
            exit = shrinkVertically(tween(if (activity.motionEnabled) AppMotion.collapse else 0, easing = AppMotion.easing)) +
                fadeOut(tween(if (activity.motionEnabled) AppMotion.briefExit else 0, easing = AppMotion.easing))) {
        Panel(Modifier.then(if (activity.motionEnabled) Modifier.animateBounds(
            this@LookaheadScope, boundsTransform = planOrderBounds) else Modifier)
            .graphicsLayer {
                if (activity.motionEnabled) {
                    translationY = -pinLift.value * 3.dp.toPx()
                }
            }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .quietClickable("${if (expanded) "收起" else "展开"}${plan.optString("name")}") { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.optString("name"), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                AnimatedVisibility(plan.optBoolean("pinned"),
                    enter = fadeIn(tween(if (activity.motionEnabled) AppMotion.stateChange else 0, easing = AppMotion.easing)),
                    exit = fadeOut(tween(if (activity.motionEnabled) AppMotion.stateChange else 0, easing = AppMotion.easing))) {
                    Caption("置顶")
                }
                DisclosureMark(expanded)
            }
            Caption("目标 ${numberText(plan.optDouble("target"))}${activity.unit(plan)} · ${cycleText(plan.optString("cycle"))}")
            if (placeName != null) Caption("地点 · $placeName")
            if (current != null) {
                Caption("当前 ${activity.amount(current)} · 截止 ${timeText(current.optLong("due"))}")
                ProgressTrack(progress, "${plan.optString("name")}完成进度")
            } else Caption(if (plan.optBoolean("archived")) "已停止后续重复" else "当前没有待执行任务")
            AnimatedVisibility(expanded,
                enter = expandVertically(tween(if (activity.motionEnabled) AppMotion.expand else 0, easing = AppMotion.easing)) +
                    fadeIn(tween(if (activity.motionEnabled) AppMotion.state else 0, easing = AppMotion.easing)),
                exit = shrinkVertically(tween(if (activity.motionEnabled) AppMotion.collapse else 0, easing = AppMotion.easing)) +
                    fadeOut(tween(if (activity.motionEnabled) AppMotion.outgoing else 0))) {
                ScreenExitGuard(!expanded, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                TextButton(onClick = {
                    if (activity.togglePlanPin(plan.optString("id")) && activity.motionEnabled) pinPulse++
                },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (plan.optBoolean("pinned")) "取消置顶" else "置顶计划")
                }
                if (current != null && plan.optString("metric") in listOf("focus", "placeFocus"))
                    Primary("开始专注") { activity.startFocusFor(current.optString("id"), nav) }
                Secondary("查看任务周期") {
                    taskTransitionId?.value = null
                    nav.navigate("plan/${plan.optString("id")}")
                }
                Secondary("编辑计划") { nav.navigate("form/${plan.optString("id")}") }
                TextButton(onClick = { deleteConfirm = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("删除整个计划", color = MaterialTheme.colorScheme.error)
                }
                }
                }
            }
        }
        }
        if (deleteConfirm) ConfirmDialog("删除整个计划？",
            "将停止后续重复，并清除所属任务、关联专注与处罚记录；统计会变化。子计划会独立保留，无法归属的旧日志与共享到场记录仍保留。此操作无法撤销。", {
                if (activity.act { PlanDeletion.delete(activity, plan.optString("id")) }) {
                    exitingCurrent = current
                    exitingPlan = plan
                    deleteConfirm = false
                }
            }) { deleteConfirm = false }
        }
    }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PlanOverview(activity: ComposeMainActivity, nav: NavHostController, id: String) {
    val revision = activity.revision
    val plan = remember(id, revision) { activity.db.one("plans", id) }
    if (plan == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val instances = remember(id, revision) { activity.db.all("instances").filter { it.optString("planId") == id } }
    var deleteConfirm by remember { mutableStateOf(false) }
    Screen(plan.optString("name"), onBack = { nav.popBackStack() }) {
        GlassTaskPanel { Text("计划概览", style = MaterialTheme.typography.titleLarge)
            Caption("${cycleText(plan.optString("cycle"))} · ${instances.size} 个任务周期") }
        Caption("修改仅影响尚未开始的任务周期。")
        Section("任务周期")
        instances.forEach { task ->
            key(task.optString("id")) { TaskCard(activity, nav, task, false) }
        }
        Secondary("调整后续计划") { nav.navigate("form/$id") }
        TextButton(onClick = { deleteConfirm = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("删除整个计划", color = MaterialTheme.colorScheme.error)
        }
        if (!plan.optBoolean("archived")) {
            var confirm by remember { mutableStateOf(false) }
            Secondary("停止后续重复") { confirm = true }
            if (confirm) ConfirmDialog("停止重复？", "已开始的周期继续按原规则结算。", {
                val saved = activity.act {
                    Store.set(plan, "archived", true)
                    activity.db.put("plans", plan)
                    val now = System.currentTimeMillis()
                    activity.db.all("instances").filter { it.optString("planId") == id && it.optLong("start") > now }
                        .forEach { activity.db.remove("instances", it.optString("id")) }
                    activity.engine.tick()
                }
                if (saved) { confirm = false; nav.popBackStack() }
            }) { confirm = false }
        }
    }
    if (deleteConfirm) ConfirmDialog("删除整个计划？",
        "将清除所属任务、关联专注与处罚记录，统计随之变化。子计划独立保留；无法归属的旧日志与共享到场记录保留。此操作无法撤销。", {
            if (activity.act { PlanDeletion.delete(activity, id) }) {
                deleteConfirm = false
                nav.popBackStack()
            }
        }) { deleteConfirm = false }
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("返回") } })
}

internal fun ComposeMainActivity.done(task: JSONObject): Boolean =
    task.optString("status") == "done" ||
        (engine.hasChildren(task) && engine.childrenDone(task)) ||
        engine.progress(task, System.currentTimeMillis()) >= task.optDouble("target", 1.0)

internal fun ComposeMainActivity.status(task: JSONObject): String = when {
    task.optString("status") == "leave" -> "已请假"
    task.optString("status") == "review" -> "待核验"
    done(task) -> "已完成"
    task.optString("status") == "failed" -> "未完成"
    else -> "进行中"
}

internal fun ComposeMainActivity.unit(task: JSONObject): String = taskUnit(task)

internal fun taskUnit(task: JSONObject): String = when (task.optString("metric")) {
    "check" -> " 项"
    "count" -> " ${task.optString("unit", "次")}"
    else -> " 分钟"
}

internal fun ComposeMainActivity.amount(task: JSONObject): String =
    "${numberText(engine.progress(task, System.currentTimeMillis()))} / ${numberText(task.optDouble("target", 1.0))}${unit(task)}"

internal fun numberText(n: Double): String = if (kotlin.math.abs(n - kotlin.math.round(n)) < .05) kotlin.math.round(n).toLong().toString() else "%.1f".format(Locale.CHINA, n)
internal fun timeText(ms: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).apply {
    timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
}.format(Date(ms))
internal fun clockText(minutes: Int): String = "%02d:%02d".format(Locale.CHINA, minutes / 60, minutes % 60)
internal fun cycleText(cycle: String): String = when (cycle) { "daily" -> "每周指定日"; "weekly" -> "每周累计"; else -> "单次目标" }
internal fun duration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
}
internal fun countdown(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%02d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
