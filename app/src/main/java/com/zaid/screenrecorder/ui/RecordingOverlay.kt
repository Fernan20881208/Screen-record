package com.zaid.screenrecorder.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zaid.screenrecorder.R
import com.zaid.screenrecorder.RecordingService
import com.zaid.screenrecorder.core.RecordingConfig
import kotlin.math.roundToInt

class RecordingOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("recording-overlay", Context.MODE_PRIVATE)
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var pauseButton: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var config: RecordingConfig? = null
    private var elapsedMs: Long = 0L
    private var paused: Boolean = false

    fun show(config: RecordingConfig, paused: Boolean = false) {
        if (!Settings.canDrawOverlays(context) || rootView != null) return
        this.config = config
        this.paused = paused

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = glassBackground()
            elevation = dp(10).toFloat()
        }

        val dragZone = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(5), 0)
        }
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.zaid_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        dragZone.addView(logo, LinearLayout.LayoutParams(dp(34), dp(34)))

        val status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setPadding(dp(7), 0, dp(6), 0)
            setSingleLine(true)
        }
        dragZone.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(dragZone)

        val pause = controlButton(if (paused) "▶" else "Ⅱ").apply {
            contentDescription = if (paused) "Resumir grabación" else "Pausar grabación"
            setOnClickListener { send(if (this@RecordingOverlay.paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE) }
        }
        root.addView(pause, LinearLayout.LayoutParams(dp(35), dp(35)).apply { marginEnd = dp(5) })

        val stop = controlButton("■").apply {
            contentDescription = "Terminar grabación"
            setTextColor(Color.rgb(255, 130, 130))
            setOnClickListener { send(RecordingService.ACTION_STOP) }
        }
        root.addView(stop, LinearLayout.LayoutParams(dp(35), dp(35)))

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(16))
            y = prefs.getInt("y", dp(80))
        }

        installDrag(dragZone, root, layout)
        wm.addView(root, layout)
        rootView = root
        statusView = status
        pauseButton = pause
        params = layout
        render()
    }

    fun update(elapsedMs: Long, paused: Boolean) {
        this.elapsedMs = elapsedMs
        this.paused = paused
        render()
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        render()
    }

    fun hide() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
        statusView = null
        pauseButton = null
        params = null
        config = null
    }

    private fun render() {
        val current = config ?: return
        val seconds = elapsedMs / 1000L
        val time = "%02d:%02d".format(seconds / 60L, seconds % 60L)
        statusView?.text = if (paused) {
            "PAUSA  $time · ${current.height}p · ${current.fps} FPS target"
        } else {
            "● REC  $time · ${current.height}p · ${current.fps} FPS target"
        }
        pauseButton?.text = if (paused) "▶" else "Ⅱ"
        pauseButton?.contentDescription = if (paused) "Resumir grabación" else "Pausar grabación"
    }

    private fun send(action: String) {
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    private fun controlButton(label: String) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 15f
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x2FFFFFFF)
            setStroke(dp(1), 0x35FFFFFF)
        }
    }

    private fun glassBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xD9343744.toInt(), 0xC51B1E28.toInt(), 0xD02B2229.toInt())
    ).apply {
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), 0x4DFFFFFF)
    }

    private fun installDrag(handle: View, root: View, layout: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layout.x
                    startY = layout.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (context.resources.displayMetrics.widthPixels - root.width).coerceAtLeast(0)
                    val maxY = (context.resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0)
                    layout.x = (startX + event.rawX - touchX).roundToInt().coerceIn(0, maxX)
                    layout.y = (startY + event.rawY - touchY).roundToInt().coerceIn(0, maxY)
                    runCatching { wm.updateViewLayout(root, layout) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
