package com.zaid.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import com.zaid.screenrecorder.audio.AudioCaptureEngine
import com.zaid.screenrecorder.audio.AudioFlingerBackend
import com.zaid.screenrecorder.audio.AudioSelection
import com.zaid.screenrecorder.audio.MicrophoneBackend
import com.zaid.screenrecorder.audio.RootAudioBackend
import com.zaid.screenrecorder.audio.VendorAudioBackend
import com.zaid.screenrecorder.core.AppState
import com.zaid.screenrecorder.core.AudioMode
import com.zaid.screenrecorder.core.RecordingConfig
import com.zaid.screenrecorder.core.RecordingStatus
import com.zaid.screenrecorder.muxer.MuxerEngine
import com.zaid.screenrecorder.recorder.PerformanceMonitor
import com.zaid.screenrecorder.recorder.RecordingSession
import com.zaid.screenrecorder.root.RootManager
import com.zaid.screenrecorder.ui.RecordingOverlay
import com.zaid.screenrecorder.video.DisplayCapabilityDetector
import com.zaid.screenrecorder.video.EncoderCapabilityDetector
import com.zaid.screenrecorder.video.NativeRootBackend
import com.zaid.screenrecorder.video.ScreenRecordEngine
import com.zaid.screenrecorder.video.SystemScreenRecordBackend
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.zaid.screenrecorder.START"
        const val ACTION_STOP = "com.zaid.screenrecorder.STOP"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1200
    }

    private val root = RootManager()
    private lateinit var videoEngine: ScreenRecordEngine
    private lateinit var audioEngine: AudioCaptureEngine
    private val muxer = MuxerEngine()
    private val performance = PerformanceMonitor()
    private val worker = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var ticker: ScheduledFuture<*>? = null
    private var session: RecordingSession? = null
    private lateinit var overlay: RecordingOverlay

    override fun onCreate() {
        super.onCreate()
        val display = DisplayCapabilityDetector(this, root)
        val encoders = EncoderCapabilityDetector()
        val systemBackend = SystemScreenRecordBackend(root)
        videoEngine = ScreenRecordEngine(display, encoders, listOf(systemBackend, NativeRootBackend()))
        audioEngine = AudioCaptureEngine(listOf(RootAudioBackend(root), AudioFlingerBackend(root), VendorAudioBackend(root)), MicrophoneBackend())
        overlay = RecordingOverlay(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> if (session == null) startRecording(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(intent: Intent) {
        val config = RecordingConfig(
            fps = intent.getIntExtra(EXTRA_FPS, 60),
            videoBitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000),
            audioMode = AudioMode.INTERNAL
        )
        startForeground(NOTIFICATION_ID, notification(config, 0, "Preparing…"))
        worker.execute {
            try {
                val rootState = root.detect()
                check(rootState.available) { "Zaid Screen Recorder requiere acceso root para utilizar su motor de captura avanzado." }
                val resolved = videoEngine.resolve(config)
                val base = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Zaid Screen Recorder").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val tempVideo = File(cacheDir, "recording-$stamp-video.mp4")
                val tempAudio = File(cacheDir, "recording-$stamp-audio.m4a")
                val videoLog = File(filesDir, "logs/last-screenrecord.log")
                videoLog.parentFile?.mkdirs(); videoLog.writeText("")
                val audio = runCatching { audioEngine.start(resolved.config, tempAudio) }
                    .getOrElse { AudioSelection(null, null, "Audio start failed: ${it.message}") }
                val videoHandle = videoEngine.start(resolved, tempVideo, videoLog)
                val output = File(base, "Zaid-Screen-Recorder-$stamp.mp4")
                val now = SystemClock.elapsedRealtimeNanos()
                session = RecordingSession(resolved.config, resolved, videoHandle, audio.handle, tempVideo, tempAudio, output, videoLog, now)
                AppState.update(RecordingStatus(true, 0, resolved.config, message = "${resolved.reason} ${audio.detail}"))
                if (resolved.config.showOverlay) overlay.show(resolved.config)
                ticker = scheduler.scheduleAtFixedRate({ updateTicker() }, 0, 1, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                AppState.update(RecordingStatus(false, message = t.message ?: "Recording failed"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateTicker() {
        val s = session ?: return
        val elapsed = (SystemClock.elapsedRealtimeNanos() - s.startedNs) / 1_000_000L
        AppState.update(AppState.recording.value.copy(active = true, elapsedMs = elapsed))
        overlay.update(elapsed)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s.config, elapsed, "Recording"))
    }

    private fun stopRecording() {
        val current = session ?: run { stopSelf(); return }
        ticker?.cancel(false); ticker = null
        worker.execute {
            try {
                current.resolvedVideo.backend.stop(current.videoHandle)
                current.audioHandle?.stop()
                overlay.hide()
                val finalFile = muxer.mux(current.tempVideo, current.audioHandle?.file, current.outputFile, current.videoHandle.startedNs, current.audioHandle?.startedNs)
                val stats = performance.analyze(finalFile, current.videoLog)
                MediaScannerConnection.scanFile(this, arrayOf(finalFile.absolutePath), arrayOf("video/mp4"), null)
                AppState.update(RecordingStatus(false, stats.durationMs, current.config, stats.averageFps, stats.droppedFrames, "Saved ${finalFile.name}; avg ${"%.2f".format(Locale.US, stats.averageFps)} FPS; ${stats.droppedFramesSource}"))
                current.tempVideo.delete(); current.tempAudio.delete()
            } catch (t: Throwable) {
                AppState.update(RecordingStatus(false, message = "Finalize failed: ${t.message}"))
            } finally {
                session = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(config: RecordingConfig, elapsedMs: Long, status: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 9, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val seconds = elapsedMs / 1000
        val time = "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
        val stopIcon = Icon.createWithResource(this, R.drawable.ic_stat_record)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Zaid Screen Recorder · $status")
            .setContentText("$time · ${config.width}x${config.height} · target ${config.fps} FPS · ${config.videoBitrate / 1_000_000} Mbps · ${config.codec}")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(stopIcon, "Detener", stopPending).build())
            .build()
    }

    override fun onDestroy() {
        ticker?.cancel(true)
        overlay.hide()
        scheduler.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }
}
