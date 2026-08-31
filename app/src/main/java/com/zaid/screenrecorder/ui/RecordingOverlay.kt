package com.zaid.screenrecorder.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.zaid.screenrecorder.core.RecordingConfig

class RecordingOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null

    fun show(config: RecordingConfig) {
        if (!Settings.canDrawOverlays(context) || view != null) return
        val text = TextView(context).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 12f
            setPadding(18, 10, 18, 10)
            text = "● REC  ${config.width}x${config.height} · target ${config.fps} FPS · Dropped: n/a"
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 80 }
        wm.addView(text, params)
        view = text
    }

    fun update(elapsedMs: Long) {
        val seconds = elapsedMs / 1000
        val time = "%02d:%02d".format(seconds / 60, seconds % 60)
        view?.let { old -> old.text = old.text.toString().replace(Regex("● REC(?:  \\d{2}:\\d{2})?"), "● REC  $time") }
    }

    fun hide() { view?.let { runCatching { wm.removeView(it) } }; view = null }
}
