package com.zaid.screenrecorder.recorder

import com.zaid.screenrecorder.audio.AudioCaptureHandle
import com.zaid.screenrecorder.core.RecordingConfig
import com.zaid.screenrecorder.video.ResolvedVideoConfig
import com.zaid.screenrecorder.video.VideoCaptureHandle
import java.io.File

data class RecordingSegment(
    val index: Int,
    val videoHandle: VideoCaptureHandle,
    val audioHandle: AudioCaptureHandle?,
    val tempVideo: File,
    val tempAudio: File,
    val muxedFile: File,
    val startedNs: Long
)

data class RecordingSession(
    val config: RecordingConfig,
    val resolvedVideo: ResolvedVideoConfig,
    val outputFile: File,
    val videoLog: File,
    val cachePrefix: String,
    val startedNs: Long,
    val completedSegments: MutableList<File> = mutableListOf(),
    var currentSegment: RecordingSegment? = null,
    var nextSegmentIndex: Int = 0,
    var paused: Boolean = false,
    var elapsedBeforeSegmentMs: Long = 0L
) {
    fun elapsedMs(nowNs: Long): Long {
        val current = currentSegment
        val running = if (!paused && current != null) ((nowNs - current.startedNs) / 1_000_000L).coerceAtLeast(0L) else 0L
        return elapsedBeforeSegmentMs + running
    }
}
