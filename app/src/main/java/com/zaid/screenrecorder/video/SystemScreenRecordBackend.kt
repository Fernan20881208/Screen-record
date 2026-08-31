package com.zaid.screenrecorder.video

import android.os.SystemClock
import com.zaid.screenrecorder.core.RecordingConfig
import com.zaid.screenrecorder.core.VideoCodec
import com.zaid.screenrecorder.root.RootCommand
import com.zaid.screenrecorder.root.RootManager
import com.zaid.screenrecorder.root.ScreenrecordHelpParser
import java.io.File
import java.util.concurrent.TimeUnit

class SystemScreenRecordBackend(private val root: RootManager) : VideoCaptureBackend {
    override val id = "system-screenrecord"

    private fun cli() = ScreenrecordHelpParser.parse(root.execute(RootCommand.ScreenrecordHelp).let { it.stdout + it.stderr })

    override fun probe(display: DisplayCapabilities, encoders: EncoderCapabilities): VideoBackendCapabilities {
        val cli = cli()
        val encoderFps = encoders.supportedFps(VideoCodec.AVC, hardwareOnly = true)
        val displayFps = display.refreshCandidates()
        val candidates = encoderFps.intersect(displayFps)
        val controllable = cli.frameRateFlag != null
        return VideoBackendCapabilities(
            available = cli.available,
            frameRateControl = controllable,
            supportedFrameRates = if (controllable) candidates else candidates.filter { it <= 60 }.toSet(),
            supportsHevc = cli.hevc && encoders.supportedFps(VideoCodec.HEVC).isNotEmpty(),
            supportsPause = false,
            detail = if (controllable) "${cli.frameRateFlag} detected" else "screenrecord exposes no explicit FPS flag; >60 FPS is withheld until a backend can verify/control it"
        )
    }

    override fun start(config: RecordingConfig, output: File, logFile: File): VideoCaptureHandle {
        output.parentFile?.mkdirs()
        val cli = cli()
        val args = mutableListOf<String>()
        if (cli.size) args += listOf("--size", "${config.width}x${config.height}")
        if (cli.bitRate) args += listOf("--bit-rate", config.videoBitrate.toString())
        cli.frameRateFlag?.let { args += listOf(it, config.fps.toString()) }
        if (config.codec == VideoCodec.HEVC && cli.hevc) args += "--codec=hevc"
        args += output.absolutePath
        val process = root.startLongRunning(RootCommand.StartScreenrecord(args), logFile)
        return VideoCaptureHandle(output, process, SystemClock.elapsedRealtimeNanos())
    }

    override fun stop(handle: VideoCaptureHandle) {
        runCatching { handle.process.destroy() }
        runCatching { handle.process.waitFor(4, TimeUnit.SECONDS) }
        if (handle.process.isAlive) runCatching { handle.process.destroyForcibly() }
    }
}
