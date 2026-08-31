package com.zaid.screenrecorder.muxer

import android.media.MediaCodec
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
        copySelectedTrack(videoExtractor, muxer, vTarget, (videoStartNs - sessionStartNs) / 1_000L)
        copySelectedTrack(audioExtractor, muxer, aTarget, ((audioStartNs ?: sessionStartNs) - sessionStartNs) / 1_000L)
        runCatching { muxer.stop() }
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()
        return output
    }

    fun concatenate(segments: List<File>, output: File): File {
        val valid = segments.filter { it.exists() && it.length() > 0L }
        check(valid.isNotEmpty()) { "No completed recording segments" }
        output.parentFile?.mkdirs()
        if (valid.size == 1) {
            valid.first().inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
            return output
        }

        val first = MediaExtractor().apply { setDataSource(valid.first().absolutePath) }
        val firstVideo = findTrack(first, "video/")
        check(firstVideo >= 0) { "First segment has no video track" }
        val firstAudio = findTrack(first, "audio/")
        val videoFormat = first.getTrackFormat(firstVideo)
        val audioFormat = if (firstAudio >= 0) first.getTrackFormat(firstAudio) else null
        first.release()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        readRotation(valid.first())?.takeIf { it in setOf(90, 180, 270) }?.let(muxer::setOrientationHint)
        val videoTarget = muxer.addTrack(videoFormat)
        val audioTarget = audioFormat?.let(muxer::addTrack)
        muxer.start()

        var timelineUs = 0L
        valid.forEach { segment ->
            val videoBounds = trackBounds(segment, "video/")
            val audioBounds = if (audioTarget != null) trackBounds(segment, "audio/") else null
            val firstPts = listOfNotNull(videoBounds?.first, audioBounds?.first).minOrNull() ?: 0L
            val lastPts = listOfNotNull(videoBounds?.second, audioBounds?.second).maxOrNull() ?: firstPts
            appendTrack(segment, "video/", muxer, videoTarget, firstPts, timelineUs)
            if (audioTarget != null) appendTrack(segment, "audio/", muxer, audioTarget, firstPts, timelineUs)
            timelineUs += (lastPts - firstPts + 1L).coerceAtLeast(1L)
        }

        runCatching { muxer.stop() }
        muxer.release()
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

    private fun copySelectedTrack(extractor: MediaExtractor, muxer: MediaMuxer, targetTrack: Int, offsetUs: Long) {
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var firstPts = -1L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            if (firstPts < 0) firstPts = pts
            info.set(0, size, (pts - firstPts).coerceAtLeast(0L) + offsetUs, extractor.sampleFlags)
            muxer.writeSampleData(targetTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun trackBounds(file: File, prefix: String): Pair<Long, Long>? {
        val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        return try {
            val index = findTrack(extractor, prefix)
            if (index < 0) return null
            extractor.selectTrack(index)
            var first = -1L
            var last = -1L
            while (extractor.sampleTime >= 0) {
                val pts = extractor.sampleTime
                if (first < 0) first = pts
                last = pts
                if (!extractor.advance()) break
            }
            if (first < 0) null else first to last
        } finally {
            extractor.release()
        }
    }

    private fun appendTrack(file: File, prefix: String, muxer: MediaMuxer, targetTrack: Int, segmentFirstPts: Long, timelineUs: Long) {
        val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
        try {
            val index = findTrack(extractor, prefix)
            if (index < 0) return
            extractor.selectTrack(index)
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = (extractor.sampleTime - segmentFirstPts).coerceAtLeast(0L) + timelineUs
                info.set(0, size, pts, extractor.sampleFlags)
                muxer.writeSampleData(targetTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }
}
