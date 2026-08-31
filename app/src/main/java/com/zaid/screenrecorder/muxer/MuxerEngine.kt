package com.zaid.screenrecorder.muxer

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

class MuxerEngine {
    fun mux(video: File, audio: File?, output: File, videoStartNs: Long, audioStartNs: Long?): File {
        output.parentFile?.mkdirs()
        if (audio == null || !audio.exists() || audio.length() == 0L) {
            video.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
            return output
        }
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val vSource = findTrack(videoExtractor, "video/")
        val aSource = findTrack(audioExtractor, "audio/")
        check(vSource >= 0) { "No video track found" }
        if (aSource < 0) {
            videoExtractor.release(); audioExtractor.release()
            video.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
            return output
        }
        videoExtractor.selectTrack(vSource)
        audioExtractor.selectTrack(aSource)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        readRotation(video)?.takeIf { it in setOf(90, 180, 270) }?.let(muxer::setOrientationHint)
        val vTarget = muxer.addTrack(videoExtractor.getTrackFormat(vSource))
        val aTarget = muxer.addTrack(audioExtractor.getTrackFormat(aSource))
        muxer.start()
        val sessionStartNs = minOf(videoStartNs, audioStartNs ?: videoStartNs)
        copyTrack(videoExtractor, muxer, vTarget, (videoStartNs - sessionStartNs) / 1_000L)
        copyTrack(audioExtractor, muxer, aTarget, ((audioStartNs ?: sessionStartNs) - sessionStartNs) / 1_000L)
        runCatching { muxer.stop() }
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()
        return output
    }

    private fun readRotation(video: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } finally {
            retriever.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
    } ?: -1

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, targetTrack: Int, offsetUs: Long) {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        var firstPts = -1L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            if (firstPts < 0) firstPts = pts
            info.set(0, size, (pts - firstPts).coerceAtLeast(0) + offsetUs, extractor.sampleFlags)
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
    }
}
