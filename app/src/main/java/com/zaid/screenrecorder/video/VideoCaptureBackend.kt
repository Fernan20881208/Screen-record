package com.zaid.screenrecorder.video

import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File

data class VideoBackendCapabilities(
    val available: Boolean,
    val frameRateControl: Boolean,
    val supportedFrameRates: Set<Int>,
    val supportsHevc: Boolean,
    val supportsPause: Boolean,
    val detail: String
)

data class VideoCaptureHandle(
    val videoFile: File,
    val process: Process,
    val startedNs: Long,
    val pidFile: File? = null,
    val logFile: File? = null
)

interface VideoCaptureBackend {
    val id: String
    fun probe(display: DisplayCapabilities, encoders: EncoderCapabilities): VideoBackendCapabilities
    fun start(config: RecordingConfig, output: File, logFile: File): VideoCaptureHandle
    fun stop(handle: VideoCaptureHandle)
}
