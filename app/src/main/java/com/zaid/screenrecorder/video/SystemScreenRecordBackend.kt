package com.zaid.screenrecorder.video

import android.os.Process
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
            supportsHevc = cli.codecFlag != null && cli.hevc && encoders.supportedFps(VideoCodec.HEVC).isNotEmpty(),
            supportsPause = false,
            detail = if (controllable) "${cli.frameRateFlag} detected; hardware/display intersection=${candidates.sorted()}" else "screenrecord exposes no explicit FPS flag; >60 FPS is withheld until a backend can verify/control it"
        )
    }

    override fun start(config: RecordingConfig, output: File, logFile: File): VideoCaptureHandle {
        output.parentFile?.mkdirs()

        // Create the destination as the app first. Vendor screenrecord implementations normally
        // truncate an existing file instead of replacing it, which preserves the app UID and
        // SELinux label. stop() still repairs ownership as a fallback for implementations that
        // recreate the file as root.
        if (output.exists() && !output.delete()) error("Could not replace stale capture file: ${output.absolutePath}")
        check(output.createNewFile()) { "Could not prepare capture file: ${output.absolutePath}" }

        val cli = cli()
        val args = mutableListOf<String>()
        cli.sizeFlag?.let { args += listOf(it, "${config.width}x${config.height}") }
        cli.bitRateFlag?.let { args += listOf(it, config.videoBitrate.toString()) }
        cli.frameRateFlag?.let { args += listOf(it, config.fps.toString()) }
        if ("--time-limit" in cli.rawHelp) args += listOf("--time-limit", "0")
        if (config.codec == VideoCodec.HEVC && cli.codecFlag != null && cli.hevc) args += listOf(cli.codecFlag, "hevc")
        args += output.absolutePath

        val pidFile = File(output.parentFile ?: logFile.parentFile, ".${output.name}.pid")
        val command = RootCommand.StartScreenrecord(args, pidFile.absolutePath)
        // Timestamp the actual launch, not the later health-check completion. The old code stamped
        // this after a 900 ms wait, which created a fake ~1 s video delay during A/V muxing.
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val process = root.startLongRunning(command, logFile)

        if (process.waitFor(900, TimeUnit.MILLISECONDS)) {
            root.execute(RootCommand.RemoveRootFile(pidFile.absolutePath), 2)
            error(startFailure(logFile, "screenrecord exited immediately with code ${runCatching { process.exitValue() }.getOrDefault(-1)}"))
        }
        val status = root.execute(RootCommand.PidFileStatus(pidFile.absolutePath), 3)
        if (status.code != 0) {
            runCatching { process.destroyForcibly() }
            root.execute(RootCommand.RemoveRootFile(pidFile.absolutePath), 2)
            error(startFailure(logFile, "root screenrecord PID was not alive after startup"))
        }

        return VideoCaptureHandle(output, process, startedNs, pidFile, logFile)
    }

    override fun stop(handle: VideoCaptureHandle) {
        val pidFile = handle.pidFile
        if (pidFile != null) {
            root.execute(RootCommand.SignalPidFile(pidFile.absolutePath, "INT"), 3)
            if (!handle.process.waitFor(6, TimeUnit.SECONDS)) {
                root.execute(RootCommand.SignalPidFile(pidFile.absolutePath, "TERM"), 2)
                if (!handle.process.waitFor(2, TimeUnit.SECONDS)) {
                    root.execute(RootCommand.SignalPidFile(pidFile.absolutePath, "KILL"), 2)
                    handle.process.waitFor(1, TimeUnit.SECONDS)
                }
            }
            root.execute(RootCommand.RemoveRootFile(pidFile.absolutePath), 2)
        } else {
            runCatching { handle.process.destroy() }
            runCatching { handle.process.waitFor(4, TimeUnit.SECONDS) }
        }
        if (handle.process.isAlive) runCatching { handle.process.destroyForcibly() }

        val ownership = root.execute(RootCommand.PrepareAppFile(handle.videoFile.absolutePath, Process.myUid()), 4)
        check(ownership.code == 0) {
            startFailure(handle.logFile, "Could not return captured MP4 ownership to the app: ${ownership.stderr.ifBlank { ownership.stdout }}")
        }
        check(handle.videoFile.canRead()) {
            startFailure(handle.logFile, "Captured MP4 is still not readable by the app after root handoff")
        }
        check(handle.videoFile.exists() && handle.videoFile.length() > 1024L) {
            startFailure(handle.logFile, "screenrecord did not produce a valid MP4 payload")
        }
    }

    private fun startFailure(logFile: File?, prefix: String): String {
        val tail = runCatching { logFile?.takeIf { it.exists() }?.readText()?.takeLast(4_000).orEmpty() }.getOrDefault("")
        return if (tail.isBlank()) prefix else "$prefix\n$tail"
    }
}
