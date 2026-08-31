package com.zaid.screenrecorder.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.zaid.screenrecorder.core.VideoCodec

data class EncoderInfo(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val supported720pFps: Set<Int>
)

data class EncoderCapabilities(val encoders: List<EncoderInfo>) {
    fun supportedFps(codec: VideoCodec, hardwareOnly: Boolean = true): Set<Int> = encoders
        .filter { it.mime.equals(codec.mime, true) && (!hardwareOnly || it.hardwareAccelerated) }
        .flatMap { it.supported720pFps }
        .toSet()
}

class EncoderCapabilityDetector {
    fun detect(): EncoderCapabilities {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val result = buildList {
            infos.filter { it.isEncoder }.forEach { info ->
                info.supportedTypes.filter { it.equals("video/avc", true) || it.equals("video/hevc", true) }.forEach { mime ->
                    val video = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@forEach
                    val fps = listOf(30, 60, 90, 120).filter { rate ->
                        runCatching { video.areSizeAndRateSupported(1280, 720, rate.toDouble()) }.getOrDefault(false)
                    }.toSet()
                    add(EncoderInfo(
                        name = info.name,
                        mime = mime,
                        hardwareAccelerated = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.lowercase().contains("google") && !info.name.lowercase().contains("ffmpeg"),
                        softwareOnly = if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else false,
                        supported720pFps = fps
                    ))
                }
            }
        }
        return EncoderCapabilities(result)
    }
}
