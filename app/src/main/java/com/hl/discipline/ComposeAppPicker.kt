package com.hl.discipline

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class SelectableApp(val packageName: String, val label: String)

@Composable
internal fun AppPicker(
    activity: ComposeMainActivity, initial: String, visible: Boolean,
    onDismiss: () -> Unit, singleSelection: Boolean = false, onHidden: () -> Unit = {},
    onSave: (String) -> Unit
) {
    AppModalSheet(visible, onDismiss, skipPartiallyExpanded = true, onHidden = onHidden) {
        var retry by remember { mutableIntStateOf(0) }
        var loadError by remember { mutableStateOf(false) }
        val options by produceState<List<SelectableApp>?>(null, retry) {
            value = null
            loadError = false
            try {
                value = withContext(Dispatchers.IO) {
                    activity.packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    ).distinctBy { it.activityInfo.packageName }
                        .filter { !BlockService.protectedApp(activity, it.activityInfo.packageName) }
                        .map { SelectableApp(it.activityInfo.packageName,
                            it.loadLabel(activity.packageManager).toString()) }
                        .sortedBy { it.label }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                loadError = true
            }
        }
        AppSelectionContent(options, initial, singleSelection, loadError,
            onRetry = { retry++ }, onDismiss = onDismiss, onSave = onSave)
    }
}

/** One semantics target per row; the checkbox is a visual part of that target. */
@Composable
internal fun AppSelectionContent(
    apps: List<SelectableApp>?, initial: String, singleSelection: Boolean = false,
    loadError: Boolean = false, onRetry: () -> Unit = {},
    onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable(initial, stateSaver = listSaver<List<String>, String>(
        save = { it }, restore = { it })) {
        mutableStateOf(jsonArray(initial).toStringList())
    }
    val filtered = remember(apps, query) {
        val needle = query.trim()
        apps?.filter { it.label.contains(needle, ignoreCase = true) ||
            it.packageName.contains(needle, ignoreCase = true) }.orEmpty()
    }
    val maxHeight = editorSheetHeight()
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
        .padding(horizontal = AppSpacing.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (singleSelection) "选择一个应用" else "选择限制应用",
                Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
            label = { Text("搜索应用名称或包名") }, singleLine = true)
        if (!typing) Text(if (singleSelection) "选择应用后设置每日或每周上限" else "已选 ${chosen.size} 个应用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when {
            loadError -> Column(Modifier.weight(1f, fill = false).padding(vertical = 16.dp)) {
                Text("应用列表读取失败")
                TextButton(onClick = onRetry) { Text("重新读取") }
            }
            apps == null -> Text("正在读取应用…", Modifier.weight(1f, fill = false).padding(vertical = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite })
            filtered.isEmpty() -> Text(if (query.isBlank()) "没有可选择的应用" else "没有匹配的应用",
                Modifier.weight(1f, fill = false).padding(vertical = 16.dp))
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(filtered, key = { it.packageName }) { app ->
                    val interaction = remember(app.packageName) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(app.packageName in chosen, interactionSource = interaction,
                            indication = ripple(color = MaterialTheme.colorScheme.primary), role = Role.Checkbox) { checked ->
                            chosen = if (singleSelection) {
                                if (checked) listOf(app.packageName) else emptyList()
                            } else if (checked) chosen + app.packageName else chosen - app.packageName
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(app.packageName in chosen, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Primary(if (singleSelection) "设置使用上限" else "保存选择", Modifier.fillMaxWidth(),
            enabled = apps != null && !loadError && (!singleSelection || chosen.size == 1)) {
            onSave(JSONArray(chosen).toString())
        }
    }
}
