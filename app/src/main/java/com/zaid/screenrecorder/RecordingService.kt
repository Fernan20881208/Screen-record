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
import com.zaid.screenrecorder.recorder.RecordingSegment
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
        const val ACTION_PAUSE = "com.zaid.screenrecorder.PAUSE"
        const val ACTION_RESUME = "com.zaid.screenrecorder.RESUME"
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
    @Volatile private var session: RecordingSession? = null
    @Volatile private var stopping = false
    private lateinit var overlay: RecordingOverlay

    override fun onCreate() {
        super.onCreate()
        val display = DisplayCapabilityDetector(this, root)
        val encoders = EncoderCapabilityDetector()
        val systemBackend = SystemScreenRecordBackend(root)
        videoEngine = ScreenRecordEngine(display, encoders, listOf(systemBackend, NativeRootBackend()))
        audioEngine = AudioCaptureEngine(listOf(RootAudioBackend(root), AudioFlingerBackend(root), VendorAudioBackend(root)), MicrophoneBackend(this))
        overlay = RecordingOverlay(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_START -> if (session == null && !stopping) startRecording(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(intent: Intent) {
        val config = RecordingConfig(
            fps = intent.getIntExtra(EXTRA_FPS, 60),
            videoBitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000),
            audioMode = AudioMode.INTERNAL,
            showOverlay = true
        )
        startForeground(NOTIFICATION_ID, notification(config, 0, false, "Preparing…"))
        worker.execute {
            try {
                val rootState = root.detect()
                check(rootState.available) { "Zaid Screen Recorder requiere acceso root para utilizar su motor de captura avanzado." }
                val resolved = videoEngine.resolve(config)
                val parent = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                val base = File(parent, "Zaid Screen Recorder").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val output = File(base, "Zaid-Screen-Recorder-$stamp.mp4")
                val videoLog = File(filesDir, "logs/last-screenrecord.log")
                videoLog.parentFile?.mkdirs(); videoLog.writeText("")
                val current = RecordingSession(
                    config = resolved.config,
                    resolvedVideo = resolved,
                    outputFile = output,
                    videoLog = videoLog,
                    cachePrefix = "recording-$stamp",
                    startedNs = SystemClock.elapsedRealtimeNanos()
                )
                val audioDetail = startSegment(current)
                session = current
                AppState.update(RecordingStatus(true, false, 0, resolved.config, message = "${resolved.reason} $audioDetail"))
                if (resolved.config.showOverlay) overlay.show(resolved.config, false)
                ticker = scheduler.scheduleAtFixedRate({ updateTicker() }, 0, 1, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                AppState.update(RecordingStatus(false, message = t.message ?: "Recording failed"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startSegment(current: RecordingSession): String {
        val index = current.nextSegmentIndex++
        val tempVideo = File(cacheDir, "${current.cachePrefix}-$index-video.mp4")
        val tempAudio = File(cacheDir, "${current.cachePrefix}-$index-audio.m4a")
        val muxed = File(cacheDir, "${current.cachePrefix}-$index-segment.mp4")
        val audio = runCatching { audioEngine.start(current.config, tempAudio) }
            .getOrElse { AudioSelection(null, null, "Audio start failed: ${it.message}") }
        return try {
            val videoHandle = videoEngine.start(current.resolvedVideo, tempVideo, current.videoLog)
            current.currentSegment = RecordingSegment(index, videoHandle, audio.handle, tempVideo, tempAudio, muxed, videoHandle.startedNs)
            current.paused = false
            audio.detail
        } catch (t: Throwable) {
            audio.handle?.stop()
            throw t
        }
    }

    private fun finalizeCurrentSegment(current: RecordingSession, actionNs: Long): File? {
        val segment = current.currentSegment ?: return null
        current.elapsedBeforeSegmentMs += ((actionNs - segment.startedNs) / 1_000_000L).coerceAtLeast(0L)
        current.resolvedVideo.backend.stop(segment.videoHandle)
        segment.audioHandle?.stop()
        val finalized = muxer.mux(
            segment.tempVideo,
            segment.audioHandle?.file,
            segment.muxedFile,
            segment.videoHandle.startedNs,
            segment.audioHandle?.startedNs
        )
        if (finalized.exists() && finalized.length() > 0L) current.completedSegments += finalized
        segment.tempVideo.delete()
        segment.tempAudio.delete()
        current.currentSegment = null
        return finalized
    }

    private fun pauseRecording() {
        val current = session ?: return
        if (current.paused || stopping) return
        worker.execute {
            if (current !== session || current.paused || stopping) return@execute
            try {
                finalizeCurrentSegment(current, SystemClock.elapsedRealtimeNanos())
                current.paused = true
                val elapsed = current.elapsedBeforeSegmentMs
                AppState.update(AppState.recording.value.copy(active = true, paused = true, elapsedMs = elapsed, message = "Grabación pausada; segmento guardado"))
                overlay.update(elapsed, true)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current.config, elapsed, true, "Paused"))
            } catch (t: Throwable) {
                AppState.update(AppState.recording.value.copy(message = "Pause failed: ${t.message}"))
            }
        }
    }

    private fun resumeRecording() {
        val current = session ?: return
        if (!current.paused || stopping) return
        worker.execute {
            if (current !== session || !current.paused || stopping) return@execute
            try {
                val detail = startSegment(current)
                AppState.update(AppState.recording.value.copy(active = true, paused = false, elapsedMs = current.elapsedBeforeSegmentMs, message = "Grabación reanudada · $detail"))
                overlay.update(current.elapsedBeforeSegmentMs, false)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current.config, current.elapsedBeforeSegmentMs, false, "Recording"))
            } catch (t: Throwable) {
                current.paused = true
                AppState.update(AppState.recording.value.copy(paused = true, message = "Resume failed: ${t.message}"))
                overlay.setPaused(true)
            }
        }
    }

    private fun updateTicker() {
        val current = session ?: return
        val elapsed = current.elapsedMs(SystemClock.elapsedRealtimeNanos())
        AppState.update(AppState.recording.value.copy(active = true, paused = current.paused, elapsedMs = elapsed))
        overlay.update(elapsed, current.paused)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current.config, elapsed, current.paused, if (current.paused) "Paused" else "Recording"))
    }

    private fun stopRecording() {
        val current = session ?: run { stopSelf(); return }
        if (stopping) return
        stopping = true
        ticker?.cancel(false); ticker = null
        overlay.hide()
        worker.execute {
            try {
                if (current.currentSegment != null) finalizeCurrentSegment(current, SystemClock.elapsedRealtimeNanos())
                val finalFile = muxer.concatenate(current.completedSegments, current.outputFile)
                val stats = performance.analyze(finalFile, current.videoLog)
                MediaScannerConnection.scanFile(this, arrayOf(finalFile.absolutePath), arrayOf("video/mp4"), null)
                AppState.update(RecordingStatus(false, false, stats.durationMs, current.config, stats.averageFps, stats.droppedFrames, "Saved ${finalFile.name}; avg ${"%.2f".format(Locale.US, stats.averageFps)} FPS; ${stats.droppedFramesSource}"))
                current.completedSegments.forEach { it.delete() }
            } catch (t: Throwable) {
                AppState.update(RecordingStatus(false, message = "Finalize failed: ${t.message}"))
            } finally {
                session = null
                stopping = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(config: RecordingConfig, elapsedMs: Long, paused: Boolean, status: String): Notification {
        val stopPending = serviceAction(ACTION_STOP, 9)
        val pauseResumeAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseResumePending = serviceAction(pauseResumeAction, if (paused) 7 else 8)
        val seconds = elapsedMs / 1000
        val time = "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_record)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Zaid Screen Recorder · $status")
            .setContentText("$time · ${config.width}x${config.height} · target ${config.fps} FPS · ${config.videoBitrate / 1_000_000} Mbps · ${config.codec}")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, if (paused) "Resumir" else "Pausar", pauseResumePending).build())
            .addAction(Notification.Action.Builder(icon, "Detener", stopPending).build())
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        ticker?.cancel(true)
        overlay.hide()
        session?.currentSegment?.let { segment ->
            runCatching { session?.resolvedVideo?.backend?.stop(segment.videoHandle) }
            runCatching { segment.audioHandle?.stop() }
        }
        scheduler.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }
}
