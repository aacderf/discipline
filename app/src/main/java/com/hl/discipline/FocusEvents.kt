package com.hl.discipline

import android.content.Context
import android.content.Intent
import androidx.compose.material3.*
import androidx.compose.runtime.*

/** A persisted boundary event, produced only by the real timer transition. */
internal object FocusEvents {
    const val ACTION = "com.hl.discipline.FOCUS_PHASE_CHANGED"

    @JvmStatic fun publish(context: Context, kind: String, title: String, message: String) {
        val db = Store.get(context)
        db.put("state", Store.obj("id", "focusEvent", "token", java.util.UUID.randomUUID().toString(),
            "kind", kind, "title", title, "message", message, "time", System.currentTimeMillis(),
            "acknowledged", false))
        val interactive = context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val hasPopup = ComposeMainActivity.visible ||
            (interactive && android.provider.Settings.canDrawOverlays(context))
        Scheduler.focusNotice(context, title, message, hasPopup)
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }

    fun acknowledge(context: Context, token: String) {
        val db = Store.get(context)
        synchronized(db) {
            val event = db.one("state", "focusEvent") ?: return
            if (event.optString("token") != token) return
            Store.set(event, "acknowledged", true)
            db.put("state", event)
        }
    }
}

@Composable
internal fun FocusPhaseDialog(activity: ComposeMainActivity) {
    val revision = activity.revision
    val event = remember(revision) { activity.db.one("state", "focusEvent") }
    if (event != null && !event.optBoolean("acknowledged")) {
        val close = {
            FocusEvents.acknowledge(activity, event.optString("token"))
            activity.refresh()
        }
        AlertDialog(onDismissRequest = close, title = { Text(event.optString("title")) },
            text = { Text(event.optString("message")) },
            confirmButton = { TextButton(onClick = close) { Text("知道了") } })
    }
}
