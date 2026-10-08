package com.hl.discipline

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Extract actual screenrecord frames for inspection; only enabled for an explicit QA recording. */
class LaunchRecordingDeviceTest {
    @Test fun exportRecordedColdStart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("exportRecording") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = File(context.filesDir, "launch-cold-0.10.4.mp4")
        assertTrue(video.exists())
        val output = File(context.filesDir, "launch-recorded-0.10.4").apply { mkdirs() }
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(video.absolutePath)
            for (millis in listOf(500L, 1800L, 2000L, 2100L, 2200L, 2400L, 2600L, 2800L, 3000L, 3200L, 3400L, 3800L, 4500L)) {
                val frame = requireNotNull(retriever.getFrameAtTime(millis * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                File(output, "recorded-${millis}ms.png").outputStream().use {
                    assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                frame.recycle()
            }
        }
    }
}
