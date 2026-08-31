package com.zaid.screenrecorder.recorder

import android.media.MediaExtractor
import android.media.MediaFormat
import com.zaid.screenrecorder.core.RecordingStats
import java.io.File
import kotlin.math.min

class PerformanceMonitor {
    fun analyze(file: File, screenrecordLog: File? = null): RecordingStats {
        val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: error("No video track")
        extractor.selectTrack(track)
        var frames = 0L
        var firstUs = -1L
        var lastUs = -1L
        val windows = linkedMapOf<Long, Int>()
        while (true) {
            val t = extractor.sampleTime
            if (t < 0) break
            if (firstUs < 0) firstUs = t
            lastUs = t
            frames++
            val second = (t - firstUs).coerceAtLeast(0) / 1_000_000L
            windows[second] = (windows[second] ?: 0) + 1
            extractor.advance()
        }
        extractor.release()
        val durationMs = if (firstUs >= 0 && lastUs >= firstUs) ((lastUs - firstUs) / 1_000L).coerceAtLeast(1) else 1L
        val avgFps = frames * 1000.0 / durationMs
        val minFps = windows.values.filter { it > 0 }.minOrNull()?.toDouble()
        val avgBitrate = file.length() * 8_000L / durationMs
        val dropped = screenrecordLog?.takeIf { it.exists() }?.readText()?.let { text ->
            Regex("(?i)dropped(?: frames)?\\s*[:=]?\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
        return RecordingStats(frames, durationMs, avgFps, minFps, avgBitrate, dropped, if (dropped == null) "backend did not report a trustworthy dropped-frame counter" else "screenrecord backend log")
    }
}
