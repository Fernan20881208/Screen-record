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

    private fun firstInternalBackend(): AudioCaptureBackend? = internalBackends.firstOrNull { backend ->
        val caps = backend.probe()
        caps.available && caps.internalAudio
    }

    private fun firstMixedBackend(): AudioCaptureBackend? = internalBackends.firstOrNull { backend ->
        val caps = backend.probe()
        caps.available && caps.internalAudio && caps.microphone
    }

    fun start(config: RecordingConfig, output: File): AudioSelection {
        return when (config.audioMode) {
            AudioMode.NONE -> AudioSelection(null, null, "Audio disabled")
            AudioMode.MICROPHONE -> AudioSelection(microphoneBackend.start(config, output), microphoneBackend.id, "Microphone capture active")
            AudioMode.INTERNAL -> {
                val backend = firstInternalBackend()
                if (backend == null) AudioSelection(null, null, "Privileged internal-audio backend unavailable; video will continue without internal audio")
                else AudioSelection(backend.start(config, output), backend.id, "Internal audio capture active · ${backend.id}")
            }
            AudioMode.INTERNAL_AND_MIC -> {
                val mixed = firstMixedBackend()
                if (mixed != null) {
                    AudioSelection(mixed.start(config, output), mixed.id, "Internal audio + microphone mixer active · ${mixed.id}")
                } else {
                    val internal = firstInternalBackend()
                    if (internal != null) AudioSelection(internal.start(config, output), internal.id, "Mixed capture unavailable; internal audio active")
                    else AudioSelection(microphoneBackend.start(config, output), microphoneBackend.id, "Privileged internal audio unavailable; microphone fallback active")
                }
            }
        }
    }
}
