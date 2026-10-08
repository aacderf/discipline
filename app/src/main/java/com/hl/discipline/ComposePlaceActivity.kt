package com.hl.discipline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.*
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.location.AMapLocationClient
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class ComposePlaceActivity : ComponentActivity() {
    private lateinit var db: Store
    private var mapView: MapView? = null
    private var map: AMap? = null
    private var polygon: Polygon? = null
    private val handles = mutableListOf<Marker>()
    private var traceLine: Polyline? = null
    private var handleDescriptor: BitmapDescriptor? = null
    private var beforeDrag: List<LatLng> = emptyList()
    private var existing by mutableStateOf<JSONObject?>(null)
    private var locator: AMapLocationClient? = null
    private var locateCancellation: CancellationSignal? = null
    private var locating by mutableStateOf(false)
    private var fusionNotice by mutableStateOf(false)
    private var locateGeneration = 0
    private val locateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var locateTimeout: Runnable? = null
    private data class TraceSnapshot(val points: List<LatLng>, val bounds: Rules.Rect?,
        val picked: Boolean, val latitude: Double, val longitude: Double, val dirty: Boolean)
    private var beforeTrace: TraceSnapshot? = null
    private var rect: Rules.Rect? = null
    private var vertices by mutableStateOf<List<LatLng>>(emptyList())
    private var centerLat = 31.2304
    private var centerLng = 121.4737
    private var needsGpsConversion = false
    private var picked by mutableStateOf(false)
    private var drawing by mutableStateOf(false)
    private var step by mutableIntStateOf(0) // position, trace, review, name
    private var dirty by mutableStateOf(false)
    private var saved = false
    private var name by mutableStateOf("")
    private var status by mutableStateOf("搜索或移动地图，找到要圈选的地点")
    private var searching by mutableStateOf(false)
    private var resultItems by mutableStateOf<List<PoiItem>>(emptyList())
    private var privacyAccepted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Store.get(this)
        existing = db.one("places", savedInstanceState?.getString("editingId") ?: intent.getStringExtra("id"))
        existing?.let {
            centerLat = it.optDouble("lat"); centerLng = it.optDouble("lng")
            rect = Rules.Rect(it.optDouble("north"), it.optDouble("south"), it.optDouble("east"), it.optDouble("west"))
            if (it.optString("shape") == "polygon") {
                vertices = Store.polygon(it)?.points?.map { pair -> LatLng(pair[0], pair[1]) }.orEmpty()
            }
            picked = true
            step = 2
            needsGpsConversion = it.optString("coordinateSystem", "WGS84") != "GCJ02"
            name = it.optString("name")
        } ?: run {
            db.one("state", "location")?.takeIf {
                System.currentTimeMillis() - it.optLong("observedAt", 0) < 20_000 && it.optDouble("accuracy", 99999.0) <= 100
            }?.let {
                centerLat = it.optDouble("lat"); centerLng = it.optDouble("lng")
                needsGpsConversion = it.optString("coordinateSystem", "WGS84") != "GCJ02"
            }
        }
        savedInstanceState?.let {
            centerLat = it.getDouble("centerLat", centerLat); centerLng = it.getDouble("centerLng", centerLng)
            if (it.getBoolean("picked")) rect = Rules.Rect(it.getDouble("north"), it.getDouble("south"), it.getDouble("east"), it.getDouble("west"))
            it.getDoubleArray("vertices")?.let { values ->
                vertices = values.toList().chunked(2).filter { pair -> pair.size == 2 }
                    .map { pair -> LatLng(pair[0], pair[1]) }
            }
            picked = it.getBoolean("picked"); dirty = it.getBoolean("dirty")
            step = it.getInt("step", step).let { value -> if (value == 1) 0 else value }
            needsGpsConversion = it.getBoolean("needsGpsConversion", needsGpsConversion)
            name = it.getString("name", name)
        }
        privacyAccepted = getSharedPreferences("PlaceActivity", 0).getBoolean("mapPrivacyAccepted", false)
        if (privacyAccepted && BuildConfig.AMAP_CONFIGURED) createMap(savedInstanceState?.getBundle("amap"))
        setContent { DisciplineTheme { MapScreen() } }
    }

    private fun createMap(state: Bundle?) {
        if (mapView != null) return
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        if (needsGpsConversion) {
            try {
                rect?.let {
                    val nw = MapCoordinates.gpsToMap(this, it.north, it.west)
                    val se = MapCoordinates.gpsToMap(this, it.south, it.east)
                    rect = Rules.Rect(nw.latitude, se.latitude, se.longitude, nw.longitude)
                }
                if (vertices.isNotEmpty()) vertices = vertices.map { point ->
                    MapCoordinates.gpsToMap(this, point.latitude, point.longitude).let { LatLng(it.latitude, it.longitude) }
                }
                if (vertices.isNotEmpty()) rect = shapeArea()?.bounds
                val point = MapCoordinates.gpsToMap(this, centerLat, centerLng)
                centerLat = point.latitude; centerLng = point.longitude
            } catch (_: RuntimeException) { picked = false; rect = null; vertices = emptyList() }
            needsGpsConversion = false
        }
        mapView = MapView(this).also { it.onCreate(state) }
        map = mapView!!.map.also { a ->
            a.uiSettings.isZoomControlsEnabled = false
            a.uiSettings.isRotateGesturesEnabled = false
            a.uiSettings.isTiltGesturesEnabled = false
            a.setOnMapClickListener { point -> if (!drawing && step == 0) moveTo(point.latitude, point.longitude) }
            a.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
                override fun onCameraChange(position: CameraPosition) {}
                override fun onCameraChangeFinish(position: CameraPosition) {
                    if (step == 0 && !drawing) {
                        centerLat = position.target.latitude; centerLng = position.target.longitude
                    }
                }
            })
            a.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
                override fun onMarkerDragStart(marker: Marker) { beforeDrag = vertices.toList(); dirty = true }
                override fun onMarkerDrag(marker: Marker) { updateHandle(marker) }
                override fun onMarkerDragEnd(marker: Marker) {
                    updateHandle(marker)
                    if (!validShape()) {
                        vertices = beforeDrag; rect = shapeArea()?.bounds; renderShape()
                        status = "边界交叉或范围超限，已恢复调整前的形状"
                    }
                }
            })
            a.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(centerLat, centerLng), 16f, 0f, 0f)))
            a.setOnMapLoadedListener { if (picked && !drawing) fitBoundary() }
        }
        renderShape()
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) mapView?.onResume()
    }

    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onDestroy() { cancelLocation(); mapView?.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editingId", existing?.optString("id"))
        val snapshot = if (drawing) beforeTrace else null
        outState.putDouble("centerLat", snapshot?.latitude ?: centerLat); outState.putDouble("centerLng", snapshot?.longitude ?: centerLng)
        outState.putBoolean("picked", snapshot?.picked ?: picked); outState.putBoolean("dirty", snapshot?.dirty ?: dirty)
        outState.putInt("step", if (drawing) if (snapshot?.picked == true) 2 else 0 else step)
        outState.putDoubleArray("vertices", (snapshot?.points ?: vertices).flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
        outState.putBoolean("needsGpsConversion", needsGpsConversion); outState.putString("name", name)
        (snapshot?.bounds ?: rect)?.let { outState.putDouble("north", it.north); outState.putDouble("south", it.south)
            outState.putDouble("east", it.east); outState.putDouble("west", it.west) }
        val mapState = Bundle(); mapView?.onSaveInstanceState(mapState); outState.putBundle("amap", mapState)
        super.onSaveInstanceState(outState)
    }

    private fun back() {
        if (!saved && dirty) status = "退出将丢失未保存的地点修改"
        else finish()
    }

    @Composable
    private fun MapScreen() {
        SystemBars(this)
        if (!BuildConfig.AMAP_CONFIGURED) {
            Screen("地图与地点", onBack = { finish() }) {
                Section("地图服务未配置")
                Caption("当前构建没有地图密钥，地图与融合定位不可用；其他功能可以继续使用。自行构建时请按 README 配置自己的高德 Android Key。")
                Secondary("返回") { finish() }
            }
            return
        }
        var showLeave by remember { mutableStateOf(false) }
        var showPrivacy by remember { mutableStateOf(!privacyAccepted) }
        var coordinates by remember { mutableStateOf(false) }
        var savedPlacesOpen by remember { mutableStateOf(false) }
        var deleteOpen by remember { mutableStateOf(false) }
        var loadingPlace by remember { mutableStateOf<JSONObject?>(null) }
        var query by rememberSaveable { mutableStateOf("") }
        val trace = remember { mutableListOf<Offset>() }
        val mapTrace = remember { mutableListOf<LatLng>() }
        val root = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(root, step, status, picked) {
            val observer = root.viewTreeObserver
            val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    root.post { root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
                    return true
                }
            }
            observer.addOnPreDrawListener(listener); root.invalidate()
            onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
        }
        val motionEnabled = LocalMotionEnabled.current
        val handleBack: () -> Unit = {
            when {
                drawing -> cancelTrace()
                step == 3 -> step = 2
                dirty && !saved -> showLeave = true
                else -> finish()
            }
        }
        BackHandler(onBack = handleBack)
        Box(Modifier.fillMaxSize()) {
            mapView?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
            if (step == 0) Surface(Modifier.align(androidx.compose.ui.Alignment.Center).size(42.dp),
                shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
                Icon(painterResource(R.drawable.ic_target), contentDescription = "地图中心",
                    modifier = Modifier.padding(10.dp))
            }
            if (drawing && map != null) Box(Modifier.fillMaxSize().pointerInput(drawing) {
                detectDragGestures(onDragStart = { point ->
                    trace.clear(); mapTrace.clear(); trace.add(point); traceLine?.remove(); traceLine = null
                    map?.projection?.fromScreenLocation(android.graphics.Point(point.x.toInt(), point.y.toInt()))?.let { mapTrace.add(it) }
                }, onDragEnd = {
                    finishTrace(trace.toList()); trace.clear(); mapTrace.clear()
                }, onDragCancel = { traceLine?.remove(); traceLine = null; trace.clear(); mapTrace.clear() },
                    onDrag = { change, _ ->
                        if (trace.isEmpty() || (change.position - trace.last()).getDistance() >= 3.dp.toPx()) {
                            trace.add(change.position)
                            map?.projection?.fromScreenLocation(android.graphics.Point(change.position.x.toInt(), change.position.y.toInt()))
                                ?.let { mapTrace.add(it) }
                            if (mapTrace.size >= 2) {
                                if (traceLine == null) traceLine = map?.addPolyline(PolylineOptions().addAll(mapTrace)
                                    .color(0xFF3B7971.toInt()).width(5.dp.toPx()))
                                else traceLine?.points = mapTrace.toList()
                            }
                        }
                        change.consume()
                    })
            })
            Column(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter)
                .statusBarsPadding().padding(12.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp)) {
                        if (!drawing) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = handleBack, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_back), contentDescription = "返回", modifier = Modifier.size(22.dp))
                            }
                            OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("搜索地点") }, singleLine = true)
                            IconButton(onClick = { search(query) }, enabled = !searching,
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = if (searching) "正在搜索地点" else "搜索地点"
                                    if (searching) stateDescription = "正在搜索"
                                }) {
                                AnimatedContent(searching, transitionSpec = {
                                    fadeIn(tween(if (motionEnabled) AppMotion.stateChange else 0, easing = AppMotion.easing)) togetherWith
                                        fadeOut(tween(if (motionEnabled) AppMotion.briefExit else 0, easing = AppMotion.easing))
                                }, label = "地点搜索状态") { busy ->
                                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    else Icon(painterResource(R.drawable.ic_search), contentDescription = null,
                                        modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                        if (!drawing) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton(onClick = { if (PositionSource.consented(this@ComposePlaceActivity)) current()
                                else fusionNotice = true }, enabled = !locating, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_locate), contentDescription = "定位到当前位置", modifier = Modifier.size(22.dp)) }
                            IconButton(onClick = { coordinates = true }, modifier = Modifier.size(48.dp)) {
                                Icon(painterResource(R.drawable.ic_target), contentDescription = "输入坐标", modifier = Modifier.size(22.dp)) }
                            TextButton(onClick = { savedPlacesOpen = true }) { Text("已存地点") }
                            if (existing != null) TextButton(onClick = { deleteOpen = true }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (drawing) Text("沿边界画一圈，抬手自动闭合", Modifier.padding(8.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            GlassTaskPanel(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.BottomCenter)
                .navigationBarsPadding().imePadding().padding(10.dp)
                .animateContentSize(tween(if (motionEnabled) AppMotion.magneticSettle else 0, easing = AppMotion.easing))) {
                    Text(when (step) { 1 -> "手绘范围"; 2 -> "确认范围"; 3 -> "命名地点"; else -> "选择地点" },
                        style = MaterialTheme.typography.titleMedium)
                    Caption(status)
                    val bounds = rect
                    if (picked && bounds != null) Caption("横向 %.0f 米 · 纵向 %.0f 米".format(Locale.CHINA,
                        bounds.width(), bounds.height()))
                    when (step) {
                        0 -> Primary("手绘圈选范围", Modifier.fillMaxWidth()) { beginTrace() }
                        1 -> Secondary("取消绘制") { cancelTrace() }
                        2 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Secondary("重画") { beginTrace() }
                            Primary("确认范围", Modifier.weight(1f), enabled = validShape()) { step = 3 }
                        }
                        else -> {
                            Input("地点名称", name, { name = it; dirty = true })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Secondary("返回调整") { step = 2 }
                                Primary(if (existing == null) "保存地点" else "保存修改", Modifier.weight(1f),
                                    enabled = validShape() && name.isNotBlank()) { save() }
                            }
                        }
                    }
            }
        }
        if (showPrivacy) AlertDialog(onDismissRequest = { finish() }, title = { Text("使用地图和地点搜索") },
            text = { Text("高德地图会处理地图视野、搜索词和用于选点的位置。请先阅读高德地图开放平台隐私权政策。") },
            confirmButton = { TextButton(onClick = {
                getSharedPreferences("PlaceActivity", 0).edit().putBoolean("mapPrivacyAccepted", true).apply()
                privacyAccepted = true; showPrivacy = false; createMap(null)
            }) { Text("同意并继续") } },
            dismissButton = { TextButton(onClick = { finish() }) { Text("返回") } })
        if (showLeave) ConfirmDialog("放弃地点修改？", "尚未保存的名称和范围会丢失。", { showLeave = false; finish() }) { showLeave = false }
        if (fusionNotice) FusionLocationNotice(accept = {
            getSharedPreferences("PlaceActivity", 0).edit().putBoolean("locationPrivacyAccepted", true).apply()
            fusionNotice = false
            if (db.settings().optBoolean("monitor") && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                startForegroundService(Intent(this, LocationService::class.java))
            current()
        }, systemOnly = { fusionNotice = false; current() }, dismiss = { fusionNotice = false })
        if (savedPlacesOpen) AlertDialog(onDismissRequest = { savedPlacesOpen = false }, title = { Text("已存地点") }, text = {
            val places = db.all("places").filter { !it.optBoolean("archived") }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (places.isEmpty()) Text("还没有保存地点。移动地图，沿边界画一圈即可添加。")
                places.forEach { place -> TextButton(onClick = {
                    savedPlacesOpen = false
                    if (dirty) loadingPlace = place else loadPlace(place)
                }) { Text(place.optString("name")) } }
            }
        }, confirmButton = { TextButton(onClick = { savedPlacesOpen = false }) { Text("关闭") } })
        loadingPlace?.let { place -> ConfirmDialog("切换地点？", "当前未保存的修改将被放弃。", {
            loadingPlace = null; loadPlace(place)
        }) { loadingPlace = null } }
        if (deleteOpen) {
            val id = existing?.optString("id").orEmpty()
            val linked = PlaceDeletion.blockers(db, id)
            AlertDialog(onDismissRequest = { deleteOpen = false }, title = { Text(if (linked.isEmpty()) "删除地点？" else "地点仍在使用") },
                text = { Text(if (linked.isEmpty()) "将移除“${existing?.optString("name")}”。历史到场记录与统计保留。"
                    else "关联：${linked.joinToString("、")}。请先在管理计划中更换地点或移除关联计划，再删除。") },
                confirmButton = { TextButton(onClick = {
                    if (linked.isNotEmpty()) deleteOpen = false
                    else try { PlaceDeletion.delete(db, id); saved = true; setResult(RESULT_OK); finish() }
                    catch (e: Exception) { status = e.message ?: "删除失败"; deleteOpen = false }
                }) { Text(if (linked.isEmpty()) "删除地点" else "知道了") } },
                dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } })
        }
        if (resultItems.isNotEmpty()) AlertDialog(onDismissRequest = { resultItems = emptyList() },
            title = { Text("选择地点") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    resultItems.forEach { item -> TextButton(onClick = {
                    moveTo(item.latLonPoint.latitude, item.latLonPoint.longitude)
                    if (name.isBlank()) name = item.title
                    resultItems = emptyList()
                }) { Text("${item.title} · ${item.cityName}") } } }
            }, confirmButton = { TextButton(onClick = { resultItems = emptyList() }) { Text("关闭") } })
        if (coordinates) CoordinateDialog({ coordinates = false }) { lat, lng ->
            try { val p = MapCoordinates.gpsToMap(this, lat, lng); moveTo(p.latitude, p.longitude); coordinates = false }
            catch (_: Exception) { status = "坐标转换暂不可用" }
        }
    }

    private fun placeAt(lat: Double, lng: Double) {
        centerLat = lat; centerLng = lng; dirty = true
        if (picked) { picked = false; rect = null; vertices = emptyList(); step = 0; clearOverlay() }
        status = "位置已确定，沿实际边界手绘圈选"
    }
    private fun moveTo(lat: Double, lng: Double) {
        if (!picked) placeAt(lat, lng)
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition(LatLng(lat, lng), 16f, 0f, 0f)))
    }
    private fun beginTrace() {
        cancelLocation(); map?.stopAnimation()
        if (!projectionReady()) {
            status = "地图尚未准备好，请等待地图显示后再重画；原范围已保留"
            return
        }
        beforeTrace = TraceSnapshot(vertices.toList(), rect, picked, centerLat, centerLng, dirty)
        drawing = true; step = 1; picked = false; rect = null; vertices = emptyList()
        map?.uiSettings?.setAllGesturesEnabled(false)
        dirty = true; clearOverlay(); status = "用手指沿边界画一圈"
    }
    private fun cancelTrace() {
        traceLine?.remove(); traceLine = null; drawing = false
        restoreGestures()
        beforeTrace?.let {
            vertices = it.points; rect = it.bounds; picked = it.picked
            centerLat = it.latitude; centerLng = it.longitude; dirty = it.dirty
        }
        beforeTrace = null; step = if (picked) 2 else 0
        clearOverlay(); renderShape(); status = if (picked) "已恢复原范围" else "移动地图后手绘圈选范围"
    }
    private fun restoreGestures() {
        map?.uiSettings?.setAllGesturesEnabled(true)
        map?.uiSettings?.isRotateGesturesEnabled = false; map?.uiSettings?.isTiltGesturesEnabled = false
    }
    private fun shapeArea(): Rules.PolygonArea? = vertices.takeIf { it.size >= 3 }?.let { points ->
        Rules.PolygonArea(points.map { doubleArrayOf(it.latitude, it.longitude) })
    }
    private fun validShape(): Boolean = if (vertices.isNotEmpty()) shapeArea()?.valid() == true else rect?.valid() == true
    private fun projectionReady(): Boolean {
        val view = mapView ?: return false
        val projection = map?.projection ?: return false
        if (view.width <= 32 || view.height <= 32) return false
        return try {
            val a = projection.fromScreenLocation(android.graphics.Point(view.width / 2 - 16, view.height / 2))
            val b = projection.fromScreenLocation(android.graphics.Point(view.width / 2 + 16, view.height / 2))
            Rules.meters(a.latitude, a.longitude, b.latitude, b.longitude).let { it.isFinite() && it > 0.01 }
        } catch (_: RuntimeException) { false }
    }
    private fun finishTrace(screen: List<Offset>) {
        traceLine?.remove(); traceLine = null
        if (!projectionReady()) { status = "地图尚未准备好，可取消绘制恢复原范围"; return }
        if (screen.size < 3) { status = "请沿边界完整画一圈，或取消恢复原范围"; return }
        val raw = screen.mapNotNull { offset -> map?.projection?.fromScreenLocation(
            android.graphics.Point(offset.x.toInt(), offset.y.toInt())) }.toMutableList()
        val points = TraceBoundary.simplify(raw.map { doubleArrayOf(it.latitude, it.longitude) })
            .map { LatLng(it[0], it[1]) }
        val area = Rules.PolygonArea(points.map { doubleArrayOf(it.latitude, it.longitude) })
        if (!area.valid()) {
            status = when {
                area.bounds.width() < 19.5 || area.bounds.height() < 19.5 ->
                    "范围太小：横向和纵向至少各 20 米。画大一些，或缩小地图后重画"
                area.bounds.width() > 6000.5 || area.bounds.height() > 6000.5 ->
                    "范围太大：横向和纵向最多各 6000 米"
                else -> "边界交叉或笔迹过短，请放大地图后沿边界重画"
            }
            return
        }
        drawing = false; beforeTrace = null; restoreGestures()
        vertices = points; rect = area.bounds; picked = true; dirty = true; step = 2
        centerLat = (area.bounds.north + area.bounds.south) / 2
        centerLng = (area.bounds.east + area.bounds.west) / 2
        status = if (area.bounds.width() < 60 || area.bounds.height() < 60)
            "范围较窄，定位精度不足时不会累计到场；可拖动圆点微调"
        else "可拖动边界上的圆点微调，再确认范围"
        renderShape()
    }
    private fun corners(bounds: Rules.Rect) = listOf(LatLng(bounds.north, bounds.west), LatLng(bounds.north, bounds.east),
        LatLng(bounds.south, bounds.east), LatLng(bounds.south, bounds.west))
    private fun clearOverlay() {
        traceLine?.remove(); traceLine = null
        polygon?.remove(); polygon = null
        handles.forEach { it.remove() }; handles.clear()
    }
    private fun renderShape() {
        val bounds = rect ?: return
        val a = map ?: return
        val points = if (vertices.isNotEmpty()) vertices else corners(bounds)
        if (polygon == null) polygon = a.addPolygon(PolygonOptions().addAll(points)
            .strokeColor(0xFF3B7971.toInt()).strokeWidth(3f).fillColor(0x5576BDB4))
        else polygon?.points = points
        if (vertices.isEmpty()) { handles.forEach { it.remove() }; handles.clear(); return }
        if (handleDescriptor == null) {
            val size = (16 * resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(3)
            paint.color = 0xFF263331.toInt(); canvas.drawCircle(size / 2f, size / 2f, size * .45f, paint)
            paint.color = 0xFF76BDB4.toInt(); canvas.drawCircle(size / 2f, size / 2f, size * .33f, paint)
            handleDescriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        }
        if (handles.size != points.size) { handles.forEach { it.remove() }; handles.clear() }
        points.forEachIndexed { index, point ->
            if (index >= handles.size) handles.add(a.addMarker(MarkerOptions().position(point)
                .icon(handleDescriptor).draggable(true).anchor(.5f, .5f)))
            else handles[index].position = point
        }
    }
    private fun updateHandle(marker: Marker) {
        val index = handles.indexOfFirst { it == marker }
        if (index < 0 || vertices.isEmpty()) return
        vertices = vertices.toMutableList().also { it[index] = marker.position }
        rect = shapeArea()?.bounds
        rect?.let { centerLat = (it.north + it.south) / 2; centerLng = (it.east + it.west) / 2 }
        dirty = true; renderShape()
    }
    private fun loadPlace(place: JSONObject) {
        cancelLocation(); clearOverlay(); beforeTrace = null; drawing = false; restoreGestures()
        existing = place; name = place.optString("name"); dirty = false; picked = true; step = 2
        centerLat = place.optDouble("lat"); centerLng = place.optDouble("lng")
        rect = Rules.Rect(place.optDouble("north"), place.optDouble("south"), place.optDouble("east"), place.optDouble("west"))
        vertices = if (place.optString("shape") == "polygon") Store.polygon(place)?.points
            ?.map { LatLng(it[0], it[1]) }.orEmpty() else emptyList()
        if (place.optString("coordinateSystem", "WGS84") != "GCJ02") {
            rect?.let { r ->
                val nw = MapCoordinates.gpsToMap(this, r.north, r.west)
                val se = MapCoordinates.gpsToMap(this, r.south, r.east)
                rect = Rules.Rect(nw.latitude, se.latitude, se.longitude, nw.longitude)
            }
            vertices = vertices.map { MapCoordinates.gpsToMap(this, it.latitude, it.longitude) }
            if (vertices.isNotEmpty()) rect = shapeArea()?.bounds
            val center = MapCoordinates.gpsToMap(this, centerLat, centerLng)
            centerLat = center.latitude; centerLng = center.longitude
        }
        renderShape()
        fitBoundary()
        status = "拖动圆点调整，或重画范围；保存后更新此地点"
    }
    private fun fitBoundary() {
        rect?.let { r -> map?.animateCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds(LatLng(r.south, r.west), LatLng(r.north, r.east)), 80)) }
    }
    private fun search(query: String) {
        if (query.trim().isEmpty() || searching) { status = "请输入地点名称"; return }
        searching = true; status = "正在搜索…"
        try {
            val request = PoiSearch.Query(query.trim(), "", "").apply { pageSize = 10 }
            PoiSearch(this, request).also { search ->
                search.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
                    override fun onPoiSearched(result: PoiResult?, code: Int) {
                        searching = false
                        resultItems = if (code == 1000) result?.pois ?: emptyList() else emptyList()
                        status = if (resultItems.isEmpty()) "未找到地点，换个关键词试试" else "找到 ${resultItems.size} 个地点"
                    }
                    override fun onPoiItemSearched(item: PoiItem?, code: Int) {}
                })
                search.searchPOIAsyn()
            }
        } catch (_: Exception) { searching = false; status = "搜索暂不可用，可直接移动地图后手绘圈选" }
    }
    private fun current() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
            return
        }
        cancelLocation()
        val generation = locateGeneration
        locating = true; status = "正在获取准确位置…"
        locateTimeout = Runnable {
            if (generation == locateGeneration) { cancelLocation(); status = "暂未取得准确位置，可移动地图后手绘圈选" }
        }.also { locateHandler.postDelayed(it, 20_000) }
        if (PositionSource.consented(this)) try {
            locator = PositionSource.start(this, true) { location -> runOnUiThread {
                if (generation == locateGeneration) {
                    if (location != null && location.errorCode == 0) position(location, location.coordType == "GCJ02", generation)
                    else systemCurrent(generation)
                }
            } }
        } catch (_: Exception) { systemCurrent(generation) }
        else systemCurrent(generation)
    }
    private fun systemCurrent(generation: Int) {
        val manager = getSystemService(LocationManager::class.java)
        try {
            val best = manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
                .filter { freshAccurate(it) && it.accuracy <= 50 }.minByOrNull { it.accuracy }
            if (best != null) { position(best, false, generation); return }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                locateCancellation = CancellationSignal()
                manager.getCurrentLocation(provider, locateCancellation, mainExecutor) { location ->
                    if (generation == locateGeneration && location != null) position(location, false, generation)
                }
            } else { cancelLocation(); status = "请先开启地点监测，取得准确位置后再试" }
        } catch (_: RuntimeException) { cancelLocation(); status = "定位暂不可用，可直接移动地图后手绘圈选" }
    }
    private fun freshAccurate(location: Location): Boolean {
        val sample = if (location is com.amap.api.location.AMapLocation)
            SystemClock.elapsedRealtime() - (System.currentTimeMillis() - location.time)
            else location.elapsedRealtimeNanos / 1_000_000
        val age = SystemClock.elapsedRealtime() - sample
        val mock = if (android.os.Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        return age in 0..20_000 && location.hasAccuracy() && location.accuracy in 0.1f..100f && !mock
    }
    private fun position(location: Location, gcj: Boolean, generation: Int) {
        if (generation != locateGeneration) return
        if (!freshAccurate(location)) {
            status = "定位精度不足，未移动地图；可直接圈选实际范围"; cancelLocation(); return
        }
        try {
            val point = if (gcj) LatLng(location.latitude, location.longitude)
                else MapCoordinates.gpsToMap(this, location.latitude, location.longitude)
            moveTo(point.latitude, point.longitude)
            status = "估计误差 %.0f 米 · %s".format(Locale.CHINA, location.accuracy,
                if (picked) "原范围已保留，需要调整时重画" else "请沿实际边界圈选")
        } catch (_: RuntimeException) { status = "坐标转换暂不可用，可直接移动地图后手绘圈选" }
        cancelLocation()
    }
    private fun cancelLocation() {
        locateGeneration++; locateCancellation?.cancel(); locateCancellation = null
        PositionSource.close(locator); locator = null; locating = false
        locateTimeout?.let { locateHandler.removeCallbacks(it) }; locateTimeout = null
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) current()
    }
    private fun save() {
        val bounds = rect ?: return
        val title = name.trim()
        if (title.isEmpty() || !validShape()) { status = "请填写名称，并绘制有效范围"; return }
        val id = existing?.optString("id") ?: Store.id()
        val place = Store.obj("id", id, "name", title, "lat", centerLat, "lng", centerLng,
            "north", bounds.north, "south", bounds.south, "east", bounds.east, "west", bounds.west,
            "coordinateSystem", "GCJ02", "shape", if (vertices.isEmpty()) "rectangle" else "polygon")
        if (vertices.isNotEmpty()) place.put("points", JSONArray().apply {
            vertices.forEach { point -> put(JSONArray().put(point.latitude).put(point.longitude)) }
        })
        try {
            db.atomic {
                val old = db.one("places", id)
                if (existing != null && (old == null || old.optBoolean("archived"))) error("地点已移除，请重新打开地图")
                db.put("places", place)
                if (old != null && PlaceDeletion.geometryKey(old) != PlaceDeletion.geometryKey(place))
                    PlaceDeletion.invalidate(db, id, "范围已调整，等待重新确认地点")
            }
            saved = true; setResult(RESULT_OK, Intent().putExtra("id", id)); finish()
        } catch (e: Exception) { status = e.message ?: "保存失败，请重试" }
    }
}

@Composable
private fun CoordinateDialog(dismiss: () -> Unit, locate: (Double, Double) -> Unit) {
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("按经纬度定位") }, text = {
        Column { Input("WGS84 纬度", latitude, { latitude = it }); Input("WGS84 经度", longitude, { longitude = it })
            if (error) Text("请输入有效的 WGS84 经纬度", color = MaterialTheme.colorScheme.error) }
    }, confirmButton = { TextButton(onClick = {
        val lat = latitude.toDoubleOrNull(); val lng = longitude.toDoubleOrNull()
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite() || kotlin.math.abs(lat) > 84 || kotlin.math.abs(lng) > 179) error = true
        else locate(lat, lng)
    }) { Text("定位") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}
