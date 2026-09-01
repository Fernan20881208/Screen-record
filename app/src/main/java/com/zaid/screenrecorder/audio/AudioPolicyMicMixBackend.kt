package com.zaid.screenrecorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Internal audio + microphone without MediaProjection.
 *
 * Internal playback comes from a privileged AudioPolicy LOOP_BACK|RENDER Remote Submix.
 * Microphone has its own AudioRecord. Both feed independent queues and are mixed into one
 * continuous PCM clock before AAC encoding, so a late/quiet source cannot stop the other.
 */
class AudioPolicyMicMixBackend(private val context: Context) : AudioCaptureBackend {
    override val id = "audio-policy-remote-submix+mic"
    private val loopback = AudioPolicyLoopback(context)

    override fun probe(): AudioBackendCapabilities {
        val loop = loopback.probe()
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return AudioBackendCapabilities(
            available = loop.available && mic,
            internalAudio = loop.available,
            microphone = mic,
            detail = when {
                !loop.available -> loop.detail
                !mic -> "RECORD_AUDIO permission required for microphone mixing"
                else -> "Privileged AudioPolicy Remote Submix + microphone mixer ready"
            }
        )
    }

    @SuppressLint("MissingPermission")
    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle {
        val caps = probe()
        check(caps.available) { caps.detail }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO is required for microphone mixing")
        }

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val logFile = File(context.filesDir, "logs/last-audio.log").apply {
            parentFile?.mkdirs()
            writeText("backend=$id\n")
        }
        val logLock = Any()
        fun log(message: String) = synchronized(logLock) {
            runCatching { logFile.appendText("${SystemClock.elapsedRealtime()} $message\n") }
            Unit
        }

        val sampleRate = config.audioSampleRate.coerceIn(16_000, 48_000)
        val channels = config.audioChannels.coerceIn(1, 2)
        val chunkFrames = (sampleRate / 50).coerceAtLeast(320) // 20 ms
        val playbackSamples = chunkFrames * channels
        val micSamples = chunkFrames

        val loopSession = loopback.open(sampleRate, channels)
        val playbackRecord = loopSession.record

        val micMin = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(micSamples * 2)
        val micRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(micMin * 4, micSamples * 8))
            .build()
        check(micRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone AudioRecord initialization failed" }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val codecFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate.coerceIn(128_000, 320_000))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, playbackSamples * 2)
        }
        codec.configure(codecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val playbackQueue = ArrayBlockingQueue<ShortArray>(24)
        val micQueue = ArrayBlockingQueue<ShortArray>(24)
        val running = AtomicBoolean(true)
        val playbackAlive = AtomicBoolean(true)
        val micAlive = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        val playbackSamplesRead = AtomicLong(0)
        val micSamplesRead = AtomicLong(0)
        val playbackDrops = AtomicLong(0)
        val micDrops = AtomicLong(0)
        val startedNs = SystemClock.elapsedRealtimeNanos()

        fun offerLatest(queue: ArrayBlockingQueue<ShortArray>, chunk: ShortArray, drops: AtomicLong) {
            if (!queue.offer(chunk)) {
                queue.poll()
                drops.incrementAndGet()
                queue.offer(chunk)
            }
        }

        fun reader(
            name: String,
            record: AudioRecord,
            requestedSamples: Int,
            queue: ArrayBlockingQueue<ShortArray>,
            alive: AtomicBoolean,
            samplesRead: AtomicLong,
            drops: AtomicLong
        ) = thread(name = name, priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                while (running.get()) {
                    val buffer = ShortArray(requestedSamples)
                    val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        count > 0 -> {
                            samplesRead.addAndGet(count.toLong())
                            offerLatest(queue, if (count == buffer.size) buffer else buffer.copyOf(count), drops)
                        }
                        count == 0 -> Thread.yield()
                        !running.get() -> break
                        else -> throw IllegalStateException("$name AudioRecord.read failed: $count")
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) log("$name stopped: ${t.javaClass.simpleName}: ${t.message}")
                // One input dying must not kill the other input or AAC timeline.
                alive.set(false)
            } finally {
                log("$name exit samples=${samplesRead.get()} drops=${drops.get()}")
            }
        }

        codec.start()
        try {
            playbackRecord.startRecording()
            check(playbackRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Remote Submix AudioRecord did not enter RECORDING state"
            }
            micRecord.startRecording()
            check(micRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone AudioRecord did not enter RECORDING state"
            }
        } catch (t: Throwable) {
            runCatching { micRecord.release() }
            loopSession.close()
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
            throw t
        }

        log("started ${loopSession.detail}; mic=MIC; sampleRate=$sampleRate channels=$channels")

        val playbackReader = reader(
            "ZaidSubmixReader", playbackRecord, playbackSamples, playbackQueue,
            playbackAlive, playbackSamplesRead, playbackDrops
        )
        val micReader = reader(
            "ZaidMicReader", micRecord, micSamples, micQueue,
            micAlive, micSamplesRead, micDrops
        )

        val encoderWorker = thread(name = "ZaidAudioMixer", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var muxerStarted = false
            var muxerStopped = false
            var trackIndex = -1
            var totalFrames = 0L
            var eosQueued = false
            var eosSeen = false
            var nextTickNs = SystemClock.elapsedRealtimeNanos()
            var lastHealthLogMs = SystemClock.elapsedRealtime()
            val info = MediaCodec.BufferInfo()

            fun drain(timeoutUs: Long) {
                var draining = true
                while (draining && !eosSeen) {
                    when (val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed more than once" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = codec.getOutputBuffer(outputIndex)
                            if (encoded != null && muxerStarted && info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
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

                    if (!running.get() && !eosQueued) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0,
                                totalFrames * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        }
                    } else if (!eosQueued) {
                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        if (nowNs < nextTickNs) {
                            val remainingNs = nextTickNs - nowNs
                            if (remainingNs > 1_000_000L) Thread.sleep(remainingNs / 1_000_000L)
                        }
                        nextTickNs += chunkFrames * 1_000_000_000L / sampleRate

                        val playback = playbackQueue.poll()
                        val mic = micQueue.poll()
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex < 0) continue
                        val input = codec.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        input.order(ByteOrder.nativeOrder())

                        // A missing source contributes silence. This gives AAC one continuous clock
                        // even if the game is quiet or one AudioRecord is temporarily starved.
                        for (frame in 0 until chunkFrames) {
                            val micSample = mic?.getOrNull(frame)?.toInt() ?: 0
                            for (channel in 0 until channels) {
                                val playbackSample = playback?.getOrNull(frame * channels + channel)?.toInt() ?: 0
                                val mixed = (playbackSample * 0.78f + micSample * 0.52f)
                                    .toInt()
                                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                input.putShort(mixed.toShort())
                            }
                        }

                        val bytes = chunkFrames * channels * 2
                        val ptsUs = totalFrames * 1_000_000L / sampleRate
                        totalFrames += chunkFrames
                        codec.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)

                        val nowMs = SystemClock.elapsedRealtime()
                        if (nowMs - lastHealthLogMs >= 2_000L) {
                            lastHealthLogMs = nowMs
                            log(
                                "health frames=$totalFrames playbackAlive=${playbackAlive.get()} micAlive=${micAlive.get()} " +
                                    "playbackSamples=${playbackSamplesRead.get()} micSamples=${micSamplesRead.get()} " +
                                    "playbackQ=${playbackQueue.size} micQ=${micQueue.size} " +
                                    "playbackDrops=${playbackDrops.get()} micDrops=${micDrops.get()}"
                            )
                        }
                    }

                    if (eosQueued) drain(10_000)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                log("encoder failed: ${t.javaClass.simpleName}: ${t.message}")
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
                if (!muxerStopped || failure.get() != null || !output.exists() || output.length() <= 1024L) {
                    output.delete()
                }
                log("encoder exit frames=$totalFrames muxerStopped=$muxerStopped failure=${failure.get()?.message}")
            }
        }

        return AudioCaptureHandle(output, startedNs, sampleRate, channels) {
            running.set(false)
            runCatching { playbackRecord.stop() }
            runCatching { micRecord.stop() }
            playbackReader.join(2_500)
            micReader.join(2_500)
            encoderWorker.join(10_000)
            runCatching { micRecord.release() }
            loopSession.close()

            check(!encoderWorker.isAlive) { "AudioPolicy mixer/encoder did not stop cleanly" }
            failure.get()?.let { throw IllegalStateException("AudioPolicy audio encode failed: ${it.message}", it) }
            check(output.exists() && output.length() > 1024L) {
                "AudioPolicy Remote Submix + microphone did not produce a valid AAC track"
            }
        }
    }
}
