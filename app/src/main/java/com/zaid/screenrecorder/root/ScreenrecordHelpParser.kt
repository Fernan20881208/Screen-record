package com.zaid.screenrecorder.root

data class ScreenrecordCliCapabilities(
    val available: Boolean,
    val sizeFlag: String?,
    val bitRateFlag: String?,
    val frameRateFlag: String?,
    val codecFlag: String?,
    val hevc: Boolean,
    val rotate: Boolean,
    val rawHelp: String
)

object ScreenrecordHelpParser {
    fun parse(help: String): ScreenrecordCliCapabilities {
        val lower = help.lowercase()
        val fpsFlag = when {
            "--frame-rate" in lower -> "--frame-rate"
            "--fps" in lower -> "--fps"
            else -> null
        }
        val bitRateFlag = when {
            "--bit-rate" in lower -> "--bit-rate"
            "--bitrate" in lower -> "--bitrate"
            else -> null
        }
        return ScreenrecordCliCapabilities(
            available = help.isNotBlank() && !lower.contains("not found"),
            sizeFlag = if ("--size" in lower) "--size" else null,
            bitRateFlag = bitRateFlag,
            frameRateFlag = fpsFlag,
            codecFlag = if ("--codec" in lower) "--codec" else null,
            hevc = "hevc" in lower || "h265" in lower || "h.265" in lower,
            rotate = "--rotate" in lower,
            rawHelp = help
        )
    }
}
