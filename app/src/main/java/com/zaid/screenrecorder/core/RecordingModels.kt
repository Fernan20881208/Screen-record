package com.zaid.screenrecorder.core

enum class VideoCodec(val mime: String) { AVC("video/avc"), HEVC("video/hevc") }
enum class AudioMode { INTERNAL, MICROPHONE, INTERNAL_AND_MIC, NONE }
enum class RecordingProfile { EFFICIENT, QUALITY, ULTRA, CUSTOM }

data class RecordingConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 60,
    val videoBitrate: Int = 8_000_000,
    val codec: VideoCodec = VideoCodec.AVC,
    val audioMode: AudioMode = AudioMode.INTERNAL,
    val audioSampleRate: Int = 48_000,
    val audioChannels: Int = 2,
    val audioBitrate: Int = 256_000,
    val gameMode: Boolean = true,
    val showOverlay: Boolean = false
)

data class RecordingStats(
    val encodedFrames: Long,
    val durationMs: Long,
    val averageFps: Double,
    val minimumWindowFps: Double?,
    val averageBitrate: Long,
    val droppedFrames: Long?,
    val droppedFramesSource: String
)

data class RecordingStatus(
    val active: Boolean = false,
    val elapsedMs: Long = 0,
    val effectiveConfig: RecordingConfig = RecordingConfig(),
    val actualFps: Double? = null,
    val droppedFrames: Long? = null,
    val message: String? = null
)
