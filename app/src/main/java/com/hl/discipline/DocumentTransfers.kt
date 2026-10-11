package com.hl.discipline

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Blocking document I/O only; callers run this on Dispatchers.IO. */
internal object DocumentTransfers {
    fun read(context: Context, uri: Uri, maxBytes: Int, limitMessage: String): String {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取文件，请重新选择")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { limitMessage }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    fun write(context: Context, uri: Uri, text: String) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入文件，请重新选择保存位置")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
