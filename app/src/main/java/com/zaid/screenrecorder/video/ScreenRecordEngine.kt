package com.zaid.screenrecorder.video

import com.zaid.screenrecorder.core.FpsPolicy
import com.zaid.screenrecorder.core.RecordingConfig
import com.zaid.screenrecorder.core.VideoCodec
import java.io.File

data class ResolvedVideoConfig(val config: RecordingConfig, val backend: VideoCaptureBackend, val reason: String)

class ScreenRecordEngine(
    private val displayDetector: DisplayCapabilityDetector,
    private val encoderDetector: EncoderCapabilityDetector,
    private val backends: List<VideoCaptureBackend>
) {
    fun resolve(requested: RecordingConfig): ResolvedVideoConfig {
        val display = displayDetector.detect()
        val encoders = encoderDetector.detect()
        val candidate = backends.map { it to it.probe(display, encoders) }.firstOrNull { it.second.available }
            ?: error("No video capture backend is available")
        val (backend, caps) = candidate
        val encoderSupported = encoders.supportedFps(requested.codec, hardwareOnly = true)
        val safe = caps.supportedFrameRates.intersect(encoderSupported).intersect(display.refreshCandidates())
        val fps = FpsPolicy.fallback(requested.fps, safe) ?: error("No hardware-backed FPS candidate is supported for ${requested.width}x${requested.height}")
        val codec = if (requested.codec == VideoCodec.HEVC && !caps.supportsHevc) VideoCodec.AVC else requested.codec
        val effective = requested.copy(fps = fps, codec = codec)
        val reason = buildString {
            if (fps != requested.fps) append("FPS fallback ${requested.fps} -> $fps. ")
            if (codec != requested.codec) append("HEVC unavailable; AVC selected. ")
            append(caps.detail)
        }
        return ResolvedVideoConfig(effective, backend, reason)
    }

    fun start(resolved: ResolvedVideoConfig, output: File, log: File) = resolved.backend.start(resolved.config, output, log)
}
