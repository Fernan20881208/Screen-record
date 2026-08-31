package com.zaid.screenrecorder.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import com.zaid.screenrecorder.core.RecordingConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MicrophoneBackend : AudioCaptureBackend {
    override val id = "microphone"
    override fun probe() = AudioBackendCapabilities(true, internalAudio = false, microphone = true, detail = "Android AudioRecord microphone backend")

    override fun start(config: RecordingConfig, output: File): AudioCaptureHandle {
        output.parentFile?.mkdirs()
        val requestedChannels = config.audioChannels.coerceIn(1, 2)
        val stereoMask = AudioFormat.CHANNEL_IN_STEREO
        val monoMask = AudioFormat.CHANNEL_IN_MONO
        val stereoMin = AudioRecord.getMinBufferSize(config.audioSampleRate, stereoMask, AudioFormat.ENCODING_PCM_16BIT)
        val channels = if (requestedChannels == 2 && stereoMin > 0) 2 else 1
        val channelMask = if (channels == 2) stereoMask else monoMask
        val minBuffer = AudioRecord.getMinBufferSize(config.audioSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(config.audioSampleRate).setChannelMask(channelMask).build())
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone AudioRecord initialization failed" }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.audioSampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val running = AtomicBoolean(true)
        val startedNs = SystemClock.elapsedRealtimeNanos()

        val worker = thread(name = "ZaidMicCapture", priority = Thread.NORM_PRIORITY) {
            var muxerStarted = false
            var trackIndex = -1
            var totalFrames = 0L
            var eosQueued = false
            val info = MediaCodec.BufferInfo()
            try {
                codec.start()
                record.startRecording()
                while (running.get() || !eosQueued) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: continue
                        input.clear()
                        if (running.get()) {
                            val maxRead = minOf(input.capacity(), minBuffer)
                            val bytes = record.read(input, maxRead)
                            if (bytes > 0) {
                                val frames = bytes / (2 * channels)
                                val ptsUs = totalFrames * 1_000_000L / config.audioSampleRate
                                totalFrames += frames
                                codec.queueInputBuffer(inputIndex, 0, bytes, ptsUs, 0)
                            } else codec.queueInputBuffer(inputIndex, 0, 0, totalFrames * 1_000_000L / config.audioSampleRate, 0)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, totalFrames * 1_000_000L / config.audioSampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }

                    while (true) {
                        val outputIndex = codec.dequeueOutputBuffer(info, 0)
                        when {
                            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    trackIndex = muxer.addTrack(codec.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                            }
                            outputIndex >= 0 -> {
                                val buffer = codec.getOutputBuffer(outputIndex)
                                if (buffer != null && muxerStarted && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(trackIndex, buffer, info)
                                }
                                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outputIndex, false)
                                if (eos) return@thread
                            }
                        }
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { codec.stop() }
                codec.release()
                if (muxerStarted) runCatching { muxer.stop() }
                muxer.release()
            }
        }

        return AudioCaptureHandle(output, startedNs, config.audioSampleRate, channels) {
            running.set(false)
            worker.join(5_000)
        }
    }
}
