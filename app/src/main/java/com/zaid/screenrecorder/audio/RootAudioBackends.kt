package com.zaid.screenrecorder.audio

import com.zaid.screenrecorder.core.RecordingConfig
import com.zaid.screenrecorder.root.RootCommand
import com.zaid.screenrecorder.root.RootManager
import java.io.File

class RootAudioBackend(private val root: RootManager) : AudioCaptureBackend {
    override val id = "root-audio"
    override fun probe(): AudioBackendCapabilities {
        val tools = root.execute(RootCommand.AudioTools).stdout.lines().filter { it.isNotBlank() }
        return AudioBackendCapabilities(
            available = false,
            internalAudio = false,
            microphone = false,
            detail = if (tools.isEmpty()) "No audited PCM capture utility detected" else "Detected ${tools.joinToString()}; no generic route is enabled until the device mixer route is verified"
        )
    }
    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle = error("No verified generic root PCM route")
}

class AudioFlingerBackend(private val root: RootManager) : AudioCaptureBackend {
    override val id = "audioflinger"
    override fun probe(): AudioBackendCapabilities {
        val dump = root.execute(RootCommand.AudioFlingerDump).stdout
        return AudioBackendCapabilities(false, false, false, if (dump.isBlank()) "AudioFlinger dump unavailable" else "AudioFlinger is available for diagnostics, but it does not expose a generic PCM capture stream")
    }
    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle = error("AudioFlinger diagnostic backend cannot capture PCM")
}

class VendorAudioBackend(private val root: RootManager) : AudioCaptureBackend {
    override val id = "vendor-audio"
    override fun probe(): AudioBackendCapabilities = AudioBackendCapabilities(false, false, false, "Vendor backend requires a device/ROM-specific audited routing profile")
    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle = error("No vendor profile selected")
}
