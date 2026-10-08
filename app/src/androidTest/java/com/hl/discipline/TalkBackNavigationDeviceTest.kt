package com.hl.discipline

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.File

/** Run separately with TalkBack enabled on the emulator; preserve services in UiAutomation. */
class TalkBackNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComposeMainActivity>()
    @Test fun liveTalkBackCanSwitchViewsAndNavigationHasOneName() {
        check(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(AccessibilityManager::class.java)
        assertTrue("Enable TalkBack before this test", manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
            })
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("today-range-switch").assertIsDisplayed()
            fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
                add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let { addAll(nodes(it)) }
            }
            fun find(description: String): AccessibilityNodeInfo {
                repeat(40) {
                    rule.waitForIdle()
                    val roots = automation.windows.mapNotNull { it.root }
                        .filter { it.packageName?.toString() == context.packageName }
                    if (roots.isNotEmpty()) roots.flatMap { nodes(it) }.firstOrNull { node ->
                        node.contentDescription?.toString() == description || node.text?.toString() == description
                    }?.let { return it }
                    Thread.sleep(100)
                }
                val trace = automation.windows.joinToString("\n") { window ->
                    "Window ${window.type} ${window.isActive}: " + (window.root?.let { root ->
                        nodes(root).joinToString(" | ") { node ->
                            "${node.packageName}: ${node.text}/${node.contentDescription} ${node.actionList}"
                        }
                    } ?: "null root")
                }
                File(context.getExternalFilesDir(null), "talkback-window-inspection.txt").writeText(trace)
                throw AssertionError("Accessible action not found: $description; window trace saved")
            }
            fun click(label: AccessibilityNodeInfo) {
                // UiAutomation may expose the label leaf separately from its clickable Compose parent.
                var action: AccessibilityNodeInfo? = label
                while (action != null && action.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    action = action.parent
                }
                assertNotNull("Label must belong to an actionable control", action)
                assertTrue(action!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            val creation = find("创建计划")
            assertTrue(creation.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            click(creation)
            find("计划名称")
            click(find("返回"))
            val before = find("今天视图")
            assertTrue(before.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            click(before)
            val week = find("这周视图")
            assertTrue(week.text.isNullOrBlank())
            click(week)
            find("今天视图")
            val nav = find("课表")
            assertTrue("Tab must not repeat its name in text and description", nav.text.isNullOrBlank())
            click(nav)
            click(find("日程视图"))
            val grid = find("周网格视图")
            assertTrue(grid.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(manager.isTouchExplorationEnabled)
            click(find("专注"))
            val quota = find("应用限额")
            assertTrue(quota.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            click(quota)
            find("添加应用限额")
            click(find("返回"))
            click(find("设置"))
            assertTrue("Settings group must be a readable heading", find("运行状态").isHeading)
            File(context.getExternalFilesDir(null), "talkback-0.10.3.txt").writeText(
                "TalkBack enabled; touch exploration active; accessibility-focus and click actions passed.\n" +
                    "Today/week and agenda/grid switched; navigation text empty, single description.\n" +
                    "Focus app quota entry, back and settings group heading passed.\n")
    }
}
