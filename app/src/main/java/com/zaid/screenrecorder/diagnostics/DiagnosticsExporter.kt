package com.zaid.screenrecorder.diagnostics

import android.content.Context
import android.os.Build
import com.zaid.screenrecorder.audio.AudioCaptureEngine
import com.zaid.screenrecorder.root.RootCommand
import com.zaid.screenrecorder.root.RootManager
import com.zaid.screenrecorder.video.DisplayCapabilityDetector
import com.zaid.screenrecorder.video.EncoderCapabilityDetector
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsExporter(
    private val context: Context,
    private val root: RootManager,
    private val displayDetector: DisplayCapabilityDetector,
    private val encoderDetector: EncoderCapabilityDetector,
    private val audioEngine: AudioCaptureEngine
) {
    fun export(lastLog: File? = null, lastStats: String? = null): File {
        val dir = File(context.getExternalFilesDir(null), "diagnostics").apply { mkdirs() }
        val out = File(dir, "zaid-screen-recorder-diagnostics.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            add(zip, "device.txt", buildString {
                appendLine("Android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Manufacturer=${Build.MANUFACTURER}")
                appendLine("Model=${Build.MODEL}")
                appendLine("Device=${Build.DEVICE}")
                appendLine("Build=${Build.DISPLAY}")
                appendLine("Kernel=${System.getProperty("os.version").orEmpty()}")
                appendLine("Root=${root.detect()}")
            })
            add(zip, "display.txt", displayDetector.detect().toString())
            add(zip, "encoders.txt", encoderDetector.detect().encoders.joinToString("\n"))
            add(zip, "screenrecord-help.txt", root.execute(RootCommand.ScreenrecordHelp).let { it.stdout + it.stderr })
            add(zip, "audio-backends.txt", audioEngine.probeInternal().joinToString("\n") { "${it.first}: ${it.second}" })
            add(zip, "last-stats.txt", lastStats.orEmpty())
            if (lastLog?.exists() == true) add(zip, "last-recording.log", lastLog.readText().take(512_000))
        }
        return out
    }

    private fun add(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
