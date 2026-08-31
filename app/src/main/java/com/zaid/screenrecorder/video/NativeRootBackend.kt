package com.zaid.screenrecorder.video

import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File

/** Reserved extension point for a future audited native Surface/codec capture path. */
class NativeRootBackend : VideoCaptureBackend {
    override val id = "native-root"
    override fun probe(display: DisplayCapabilities, encoders: EncoderCapabilities) = VideoBackendCapabilities(
        available = false,
        frameRateControl = true,
        supportedFrameRates = emptySet(),
        supportsHevc = false,
        supportsPause = false,
        detail = "Native backend scaffold only; no unreviewed native binary is shipped in v1.0.0"
    )
    override fun start(config: RecordingConfig, output: File, logFile: File): VideoCaptureHandle = error("NativeRootBackend is not implemented")
    override fun stop(handle: VideoCaptureHandle) = Unit
}
