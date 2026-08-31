package com.zaid.screenrecorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Android 10+ software mixer for AudioPlaybackCapture + microphone.
 *
 * Each AudioRecord owns a dedicated blocking reader thread so a temporarily quiet/blocked
 * source cannot stall the other one. PCM chunks are mixed into one continuous timeline and
 * encoded once to AAC, which also avoids trying to mux two independent audio tracks later.
 */
@TargetApi(Build.VERSION_CODES.Q)
class PlaybackMicMixBackend(
    private val context: Context,
    projection: MediaProjection?
) : AudioCaptureBackend {
    private val projection: MediaProjection = requireNotNull(projection) { "MediaProjection permission token was rejected" }
    override val id = "media-projection-playback+mic"

    override fun probe(): AudioBackendCapabilities {
        val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return AudioBackendCapabilities(
            available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permission,
            internalAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permission,
            microphone = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permission,
            detail = if (permission) "AudioPlaybackCapture + microphone PCM mixer ready" else "RECORD_AUDIO permission required"
        )
    }

    @SuppressLint("MissingPermission")
    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Internal + microphone capture requires Android 10+" }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is required for internal + microphone capture")
        }

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val sampleRate = config.audioSampleRate.coerceIn(16_000, 96_000)
        val channels = config.audioChannels.coerceIn(1, 2)
        val playbackMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val outputSamplesPerFrame = channels
        val chunkFrames = (sampleRate / 50).coerceAtLeast(320) // ~20 ms
        val playbackChunkSamples = chunkFrames * channels
        val micChunkSamples = chunkFrames // microphone is mono for widest device compatibility

        val playbackMin = AudioRecord.getMinBufferSize(sampleRate, playbackMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(playbackChunkSamples * 2)
        val micMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(micChunkSamples * 2)

        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val playbackRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(capture)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(playbackMask)
                    .build()
            )
            .setBufferSizeInBytes(max(playbackMin * 4, playbackChunkSamples * 8))
            .build()

        val micRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(micMin * 4, micChunkSamples * 8))
            .build()

        check(playbackRecord.state == AudioRecord.STATE_INITIALIZED) { "Internal AudioRecord initialization failed" }
        check(micRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone AudioRecord initialization failed" }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate.coerceAtLeast(128_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, playbackChunkSamples * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val playbackQueue = ArrayBlockingQueue<ShortArray>(16)
        val micQueue = ArrayBlockingQueue<ShortArray>(16)
        val running = AtomicBoolean(true)
        val projectionStopped = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val startedNs = SystemClock.elapsedRealtimeNanos()

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionStopped.set(true)
                running.set(false)
                runCatching { playbackRecord.stop() }
                runCatching { micRecord.stop() }
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        fun offerLatest(queue: ArrayBlockingQueue<ShortArray>, chunk: ShortArray) {
            if (!queue.offer(chunk)) {
                queue.poll()
                queue.offer(chunk)
            }
        }

        fun reader(
            name: String,
            record: AudioRecord,
            samplesPerChunk: Int,
            queue: ArrayBlockingQueue<ShortArray>
        ) = thread(name = name, priority = Thread.MAX_PRIORITY) {
            try {
                while (running.get()) {
                    val buffer = ShortArray(samplesPerChunk)
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        count > 0 -> offerLatest(queue, if (count == buffer.size) buffer else buffer.copyOf(count))
                        count == 0 -> Thread.yield()
                        !running.get() -> break
                        else -> error("$name AudioRecord.read failed: $count")
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    failure.compareAndSet(null, t)
                    running.set(false)
                    runCatching { playbackRecord.stop() }
                    runCatching { micRecord.stop() }
                }
            }
        }

        codec.start()
        playbackRecord.startRecording()
        micRecord.startRecording()
        check(playbackRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Internal AudioRecord did not start" }
        check(micRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone AudioRecord did not start" }

        val playbackReader = reader("ZaidPlaybackReader", playbackRecord, playbackChunkSamples, playbackQueue)
        val micReader = reader("ZaidMicReader", micRecord, micChunkSamples, micQueue)

        val encoderWorker = thread(name = "ZaidAudioMixer", priority = Thread.MAX_PRIORITY) {
            var muxerStarted = false
            var muxerStopped = false
            var trackIndex = -1
            var totalFrames = 0L
            var eosQueued = false
            var eosSeen = false
            val info = MediaCodec.BufferInfo()

            fun drain(timeoutUs: Long) {
                var keepDraining = true
                while (keepDraining && !eosSeen) {
                    when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> keepDraining = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed more than once" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = codec.getOutputBuffer(outputIndex)
                            if (encoded != null && muxerStarted && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, encoded, info)
                            }
                            eosSeen = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            try {
                while (!eosSeen) {
                    drain(0)

                    if (!running.get() && playbackQueue.isEmpty() && micQueue.isEmpty() && !eosQueued) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                totalFrames * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        }
                    } else if (!eosQueued) {
                        val playback = playbackQueue.poll(25, TimeUnit.MILLISECONDS)
                        val mic = micQueue.poll(25, TimeUnit.MILLISECONDS)
                        if (playback == null && mic == null) continue

                        val playbackFrames = (playback?.size ?: 0) / channels
                        val micFrames = mic?.size ?: 0
                        val frames = max(playbackFrames, micFrames).coerceAtMost(chunkFrames)
                        if (frames <= 0) continue

                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex < 0) continue
                        val input = codec.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        input.order(ByteOrder.nativeOrder())

                        for (frame in 0 until frames) {
                            val micSample = mic?.getOrNull(frame)?.toInt() ?: 0
                            for (channel in 0 until outputSamplesPerFrame) {
                                val playbackSample = playback?.getOrNull(frame * channels + channel)?.toInt() ?: 0
                                // Leave headroom for simultaneous loud game + microphone audio.
                                val mixed = (playbackSample * 0.72f + micSample * 0.55f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                input.putShort(mixed.toShort())
                            }
                        }

                        val bytes = frames * channels * 2
                        val ptsUs = totalFrames * 1_000_000L / sampleRate
                        totalFrames += frames
                        codec.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)
                    }

                    if (eosQueued) drain(10_000)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                running.set(false)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                        .onSuccess { muxerStopped = true }
                        .onFailure { failure.compareAndSet(null, it) }
                }
                runCatching { muxer.release() }.onFailure { failure.compareAndSet(null, it) }
                if (!muxerStopped || failure.get() != null || !output.exists() || output.length() <= 1024L) output.delete()
            }
        }

        return AudioCaptureHandle(output, startedNs, sampleRate, channels) {
            running.set(false)
            runCatching { playbackRecord.stop() }
            runCatching { micRecord.stop() }
            playbackReader.join(2_000)
            micReader.join(2_000)
            encoderWorker.join(10_000)
            runCatching { playbackRecord.release() }
            runCatching { micRecord.release() }
            runCatching { projection.unregisterCallback(projectionCallback) }

            check(!playbackReader.isAlive && !micReader.isAlive) { "Audio capture reader did not stop cleanly" }
            check(!encoderWorker.isAlive) { "Audio mixer/encoder did not stop cleanly" }
            failure.get()?.let { throw IllegalStateException("Internal + microphone audio failed: ${it.message}", it) }
            check(output.exists() && output.length() > 1024L) {
                if (projectionStopped.get()) "MediaProjection stopped before audio could be finalized"
                else "Internal + microphone capture did not produce a valid AAC track"
            }
        }
    }
}
