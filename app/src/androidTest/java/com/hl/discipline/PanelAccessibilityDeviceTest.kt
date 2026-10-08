package com.hl.discipline

import android.app.UiAutomation
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PanelAccessibilityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()

    @Test fun talkBackSelectsOneMergedApplicationRowAndSavesIt() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        val context = rule.activity
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        rule.waitUntil(5_000) { manager.isTouchExplorationEnabled }
        var saved = ""
        var visible by mutableStateOf(true)
        rule.runOnUiThread { rule.activity.setContent {
            DisciplineTheme {
                AppModalSheet(visible, { visible = false }, skipPartiallyExpanded = true) {
                    AppSelectionContent(listOf(SelectableApp("qa.music", "读屏音乐")), "[]",
                        onDismiss = { visible = false }, onSave = { saved = it; visible = false })
                }
            }
        } }
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(nodes(it)) }
        }
        fun clickable(text: String): List<AccessibilityNodeInfo> = automation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == context.packageName }.flatMap { nodes(it) }
            .filter { it.text?.toString()?.contains(text) == true ||
                it.contentDescription?.toString()?.contains(text) == true }
            .mapNotNull { label ->
                var action: AccessibilityNodeInfo? = label
                while (action != null && !action.isClickable) action = action.parent
                action
            }.distinct()
        rule.waitUntil(5_000) { clickable("读屏音乐").isNotEmpty() }
        val row = clickable("读屏音乐").single()
        assertTrue(row.isCheckable)
        assertFalse(row.isChecked)
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitUntil(5_000) { clickable("读屏音乐").singleOrNull()?.isChecked == true }
        rule.waitUntil(5_000) { clickable("保存选择").isNotEmpty() }
        assertTrue(clickable("保存选择").single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(listOf("qa.music"), jsonArray(saved).toStringList())
    }
}
