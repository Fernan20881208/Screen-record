package com.zaid.screenrecorder.root

data class ScreenrecordCliCapabilities(
    val available: Boolean,
    val size: Boolean,
    val bitRate: Boolean,
    val frameRateFlag: String?,
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
        return ScreenrecordCliCapabilities(
            available = help.isNotBlank() && !lower.contains("not found"),
            size = "--size" in lower,
            bitRate = "--bit-rate" in lower || "--bitrate" in lower,
            frameRateFlag = fpsFlag,
            hevc = "hevc" in lower || "h265" in lower || "h.265" in lower,
            rotate = "--rotate" in lower,
            rawHelp = help
        )
    }
}
