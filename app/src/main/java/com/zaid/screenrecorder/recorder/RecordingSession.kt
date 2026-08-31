package com.zaid.screenrecorder.recorder

import com.zaid.screenrecorder.audio.AudioCaptureHandle
import com.zaid.screenrecorder.core.RecordingConfig
import com.zaid.screenrecorder.video.ResolvedVideoConfig
import com.zaid.screenrecorder.video.VideoCaptureHandle
import java.io.File

data class RecordingSession(
    val config: RecordingConfig,
    val resolvedVideo: ResolvedVideoConfig,
    val videoHandle: VideoCaptureHandle,
    val audioHandle: AudioCaptureHandle?,
    val tempVideo: File,
    val tempAudio: File,
    val outputFile: File,
    val videoLog: File,
    val startedNs: Long
)
