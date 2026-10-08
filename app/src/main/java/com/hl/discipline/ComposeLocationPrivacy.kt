package com.hl.discipline

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun FusionLocationNotice(accept: () -> Unit, systemOnly: () -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    if (!BuildConfig.AMAP_CONFIGURED) {
        AlertDialog(onDismissRequest = dismiss, title = { Text("融合定位未配置") },
            text = { Text("当前构建没有地图密钥，请继续使用系统定位。自行构建时可按 README 配置自己的高德 Android Key。") },
            confirmButton = { TextButton(onClick = systemOnly) { Text("使用系统定位") } })
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text("启用融合定位") }, text = {
        Column {
            Text("高德定位会处理设备位置、Wi-Fi 和基站信息，以改善室内定位。仅在地点监测开启或主动定位时使用；不同意仍可使用系统定位。")
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://lbs.amap.com/pages/privacy/"))) }) { Text("阅读高德隐私权政策") }
        }
    }, confirmButton = { TextButton(onClick = accept) { Text("同意并启用") } },
        dismissButton = { TextButton(onClick = systemOnly) { Text("仅用系统定位") } })
}
