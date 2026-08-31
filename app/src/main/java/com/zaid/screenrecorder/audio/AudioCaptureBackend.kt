package com.zaid.screenrecorder.audio

import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File

data class AudioBackendCapabilities(
    val available: Boolean,
    val internalAudio: Boolean,
    val microphone: Boolean,
    val detail: String
)

data class AudioCaptureHandle(
    val file: File,
    val startedNs: Long,
    val sampleRate: Int,
    val channels: Int,
    val stopAction: () -> Unit
) {
    fun stop() = stopAction()
}

interface AudioCaptureBackend {
    val id: String
    fun probe(): AudioBackendCapabilities
    fun start(config: RecordingConfig, output: File): AudioCaptureHandle
}
