package com.zaid.screenrecorder.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.zaid.screenrecorder.root.RootCommand
import com.zaid.screenrecorder.root.RootManager
import kotlin.math.roundToInt

data class DisplayModeInfo(val width: Int, val height: Int, val refreshRate: Float)
data class DisplayCapabilities(
    val physicalWidth: Int,
    val physicalHeight: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val densityDpi: Int,
    val rotation: Int,
    val currentRefreshRate: Float,
    val modes: List<DisplayModeInfo>,
    val rootWmSize: String,
    val rootWmDensity: String
) {
    fun refreshCandidates(): Set<Int> = buildSet {
        modes.forEach { mode -> listOf(30, 60, 90, 120).forEach { if (mode.refreshRate + 0.75f >= it) add(it) } }
    }
}

class DisplayCapabilityDetector(private val context: Context, private val root: RootManager) {
    fun detect(): DisplayCapabilities {
        val manager = context.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION") val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
        val current = display.mode
        val metrics = context.resources.displayMetrics
        val modes = display.supportedModes.map { DisplayModeInfo(it.physicalWidth, it.physicalHeight, it.refreshRate) }
            .distinctBy { Triple(it.width, it.height, (it.refreshRate * 10).roundToInt()) }
            .sortedWith(compareByDescending<DisplayModeInfo> { it.refreshRate }.thenByDescending { it.width * it.height })
        return DisplayCapabilities(
            physicalWidth = current.physicalWidth,
            physicalHeight = current.physicalHeight,
            logicalWidth = metrics.widthPixels,
            logicalHeight = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            rotation = display.rotation,
            currentRefreshRate = display.refreshRate,
            modes = modes,
            rootWmSize = root.execute(RootCommand.WmSize).stdout.trim(),
            rootWmDensity = root.execute(RootCommand.WmDensity).stdout.trim()
        )
    }
}
