package com.zaid.screenrecorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Android 10+ internal playback capture for media/game audio permitted by the source app. */
class PlaybackCaptureBackend(
    private val context: Context,
    projection: MediaProjection?
) : AudioCaptureBackend {
    private val projection: MediaProjection = requireNotNull(projection) { "MediaProjection permission token was rejected" }
    override val id = "media-projection-playback"

    override fun probe(): AudioBackendCapabilities {
        val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return AudioBackendCapabilities(
            available = Build.VERSION.SDK_INT >= 29 && permission,
            internalAudio = Build.VERSION.SDK_INT >= 29 && permission,
            microphone = false,
            detail = when {
                Build.VERSION.SDK_INT < 29 -> "AudioPlaybackCapture requires Android 10+"
                !permission -> "RECORD_AUDIO permission is required for playback capture"
                else -> "MediaProjection AudioPlaybackCapture ready for media/game audio"
            }
        )
    }

    @SuppressLint("MissingPermission")
    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle {
        check(Build.VERSION.SDK_INT >= 29) { "Internal playback capture requires Android 10+" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is required for internal playback capture")
        }

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val sampleRate = config.audioSampleRate.coerceIn(16_000, 96_000)
        val requestedChannels = config.audioChannels.coerceIn(1, 2)
        val stereoMask = AudioFormat.CHANNEL_IN_STEREO
        val monoMask = AudioFormat.CHANNEL_IN_MONO
        val stereoMin = AudioRecord.getMinBufferSize(sampleRate, stereoMask, AudioFormat.ENCODING_PCM_16BIT)
        val channels = if (requestedChannels == 2 && stereoMin > 0) 2 else 1
        val channelMask = if (channels == 2) stereoMask else monoMask
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)

        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(capture)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 4)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Internal AudioRecord initialization failed" }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate.coerceAtLeast(96_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 4)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val running = AtomicBoolean(true)
        val projectionStopped = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionStopped.set(true)
                running.set(false)
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))

        val worker = thread(name = "ZaidPlaybackCapture", priority = Thread.NORM_PRIORITY) {
            var muxerStarted = false
            var muxerStopped = false
            var trackIndex = -1
            var totalFrames = 0L
            var inputEosQueued = false
            var outputEosSeen = false
            val info = MediaCodec.BufferInfo()
            try {
                codec.start()
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Internal AudioRecord did not enter recording state" }

                while (!outputEosSeen) {
                    if (!inputEosQueued) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)
                            if (input != null) {
                                input.clear()
                                if (running.get() && !projectionStopped.get()) {
                                    val maxRead = minOf(input.capacity(), minBuffer * 2)
                                    val bytes = record.read(input, maxRead, AudioRecord.READ_BLOCKING)
                                    if (bytes > 0) {
                                        val frames = bytes / (2 * channels)
                                        val ptsUs = totalFrames * 1_000_000L / sampleRate
                                        totalFrames += frames
                                        codec.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)
                                    } else {
                                        codec.queueInputBuffer(inputIndex, 0, 0, totalFrames * 1_000_000L / sampleRate, 0)
                                    }
                                } else {
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        totalFrames * 1_000_000L / sampleRate,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEosQueued = true
                                }
                            }
                        }
                    }

                    var draining = true
                    while (draining && !outputEosSeen) {
                        val timeoutUs = if (inputEosQueued) 10_000L else 0L
                        when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                            MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                check(!muxerStarted) { "AAC output format changed more than once" }
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            else -> if (outputIndex >= 0) {
                                val buffer = codec.getOutputBuffer(outputIndex)
                                if (buffer != null && muxerStarted && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(trackIndex, buffer, info)
                                }
                                outputEosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                        .onSuccess { muxerStopped = true }
                        .onFailure { failure.compareAndSet(null, it) }
                }
                runCatching { muxer.release() }
                    .onFailure { failure.compareAndSet(null, it) }
                if (!muxerStopped || failure.get() != null || !output.exists() || output.length() <= 1024L) {
                    output.delete()
                }
            }
        }

        return AudioCaptureHandle(output, startedNs, sampleRate, channels) {
            running.set(false)
            worker.join(8_000)
            if (worker.isAlive) {
                runCatching { record.stop() }
                worker.join(2_000)
            }
            runCatching { projection.unregisterCallback(callback) }
            check(!worker.isAlive) { "Internal audio encoder did not stop cleanly" }
            failure.get()?.let { throw IllegalStateException("Internal audio encode failed: ${it.message}", it) }
            check(output.exists() && output.length() > 1024L) {
                if (projectionStopped.get()) "MediaProjection stopped before internal audio could be finalized"
                else "Internal audio capture did not produce a valid AAC track"
            }
        }
    }
}
