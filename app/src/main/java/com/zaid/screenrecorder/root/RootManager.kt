package com.zaid.screenrecorder.root

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

sealed interface RootCommand {
    val shell: String
    data object Identity : RootCommand { override val shell = "id" }
    data object SuVersion : RootCommand {
        override val shell = "(command -v magisk >/dev/null && magisk -v) 2>/dev/null; (command -v ksud >/dev/null && ksud --version) 2>/dev/null; (command -v apd >/dev/null && apd --version) 2>/dev/null; su -v 2>/dev/null || true"
    }
    data object ScreenrecordHelp : RootCommand { override val shell = "/system/bin/screenrecord --help 2>&1" }
    data object DisplayDump : RootCommand { override val shell = "dumpsys display 2>/dev/null" }
    data object WmSize : RootCommand { override val shell = "wm size 2>/dev/null" }
    data object WmDensity : RootCommand { override val shell = "wm density 2>/dev/null" }
    data object AudioFlingerDump : RootCommand { override val shell = "dumpsys media.audio_flinger 2>/dev/null" }
    data object AudioTools : RootCommand { override val shell = "for x in tinycap tinymix arecord; do command -v \$x 2>/dev/null || true; done" }
    data class StartScreenrecord(val args: List<String>) : RootCommand {
        override val shell: String = buildString {
            append("exec /system/bin/screenrecord")
            args.forEach { append(' ').append(shellQuote(it)) }
        }
    }

    companion object {
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

data class ShellResult(val code: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

enum class RootImplementation { MAGISK, KERNEL_SU, APATCH, GENERIC_SU, NONE }
data class RootState(val available: Boolean, val implementation: RootImplementation, val detail: String)

class RootManager {
    fun detect(): RootState {
        val id = execute(RootCommand.Identity, 5)
        if (id.code != 0 || !id.stdout.contains("uid=0")) return RootState(false, RootImplementation.NONE, id.stderr.ifBlank { "su denied or unavailable" })
        val v = execute(RootCommand.SuVersion, 5).stdout.lowercase()
        val implementation = when {
            "magisk" in v -> RootImplementation.MAGISK
            "kernelsu" in v || "kernel su" in v || "ksud" in v -> RootImplementation.KERNEL_SU
            "apatch" in v -> RootImplementation.APATCH
            else -> RootImplementation.GENERIC_SU
        }
        return RootState(true, implementation, v.trim().ifBlank { "root shell available" })
    }

    fun execute(command: RootCommand, timeoutSeconds: Long = 10): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command.shell).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = thread(isDaemon = true, name = "ZaidRootReader") {
                runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } }
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(1_500)
            val text = output.toString()
            if (!finished) ShellResult(-1, text, "command timed out", true)
            else ShellResult(process.exitValue(), text, if (process.exitValue() == 0) "" else text)
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message.orEmpty())
        }
    }

    fun startLongRunning(command: RootCommand.StartScreenrecord, logFile: File): Process {
        logFile.parentFile?.mkdirs()
        return ProcessBuilder("su", "-c", command.shell)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }
}
