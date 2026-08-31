package com.zaid.screenrecorder.audio

import com.zaid.screenrecorder.core.AudioMode
import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File

data class AudioSelection(val handle: AudioCaptureHandle?, val backendId: String?, val detail: String)

class AudioCaptureEngine(
    private val internalBackends: List<AudioCaptureBackend>,
    private val microphoneBackend: AudioCaptureBackend
) {
    fun probeInternal(): List<Pair<String, AudioBackendCapabilities>> = internalBackends.map { it.id to it.probe() }

    fun start(config: RecordingConfig, output: File): AudioSelection {
        return when (config.audioMode) {
            AudioMode.NONE -> AudioSelection(null, null, "Audio disabled")
            AudioMode.MICROPHONE -> AudioSelection(microphoneBackend.start(config, output), microphoneBackend.id, "Microphone capture active")
            AudioMode.INTERNAL -> {
                val backend = internalBackends.firstOrNull { it.probe().available && it.probe().internalAudio }
                if (backend == null) AudioSelection(null, null, "Internal audio unavailable on this ROM without MediaProjection; video will continue without audio")
                else AudioSelection(backend.start(config, output), backend.id, "Internal root audio backend active")
            }
            AudioMode.INTERNAL_AND_MIC -> {
                val internal = internalBackends.firstOrNull { it.probe().available && it.probe().internalAudio }
                if (internal != null) AudioSelection(internal.start(config, output), internal.id, "Internal backend selected; mixing backend reserved for device profile")
                else AudioSelection(microphoneBackend.start(config, output), microphoneBackend.id, "Internal audio unavailable; microphone fallback active")
            }
        }
    }
}
