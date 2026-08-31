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
        check(isPlayable(video)) { "Raw screenrecord MP4 has no readable video samples" }

        if (audio == null || !audio.exists() || audio.length() == 0L || !hasTrack(audio, "audio/")) {
            return copyValidated(video, output)
        }

        if (output.exists()) output.delete()
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val vSource = findTrack(videoExtractor, "video/")
            val aSource = findTrack(audioExtractor, "audio/")
            check(vSource >= 0) { "No video track found in raw capture" }
            check(aSource >= 0) { "No AAC audio track found in internal-audio capture" }

            videoExtractor.selectTrack(vSource)
            audioExtractor.selectTrack(aSource)

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            readRotation(video)?.takeIf { it in setOf(90, 180, 270) }?.let(muxer::setOrientationHint)
            val vTarget = muxer.addTrack(videoExtractor.getTrackFormat(vSource))
            val aTarget = muxer.addTrack(audioExtractor.getTrackFormat(aSource))
            muxer.start()
            muxerStarted = true

            val sessionStartNs = minOf(videoStartNs, audioStartNs ?: videoStartNs)
            copySelectedTrack(videoExtractor, muxer, vTarget, (videoStartNs - sessionStartNs) / 1_000L)
            copySelectedTrack(audioExtractor, muxer, aTarget, ((audioStartNs ?: sessionStartNs) - sessionStartNs) / 1_000L)

            muxer.stop()
            muxerStopped = true
        } catch (t: Throwable) {
            output.delete()
            throw IllegalStateException("MP4 mux failed: ${t.message}", t)
        } finally {
            if (muxerStarted && !muxerStopped) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }

        check(isPlayable(output)) {
            output.delete()
            "Muxer produced an MP4 without readable video samples"
        }
        return output
    }

    fun concatenate(segments: List<File>, output: File): File {
        val valid = segments.filter { isPlayable(it) }
        check(valid.isNotEmpty()) { "No playable recording segments" }
        output.parentFile?.mkdirs()
        if (valid.size == 1) return copyValidated(valid.first(), output)

        val first = MediaExtractor().apply { setDataSource(valid.first().absolutePath) }
        val firstVideo = findTrack(first, "video/")
        check(firstVideo >= 0) { "First segment has no video track" }
        val firstAudio = findTrack(first, "audio/")
        val videoFormat = first.getTrackFormat(firstVideo)
        val audioFormat = if (firstAudio >= 0) first.getTrackFormat(firstAudio) else null
        first.release()

        if (output.exists()) output.delete()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var stopped = false
        try {
            readRotation(valid.first())?.takeIf { it in setOf(90, 180, 270) }?.let(muxer::setOrientationHint)
            val videoTarget = muxer.addTrack(videoFormat)
            val audioTarget = audioFormat?.let(muxer::addTrack)
            muxer.start()
            started = true

            var timelineUs = 0L
            valid.forEach { segment ->
                val videoBounds = trackBounds(segment, "video/")
                    ?: error("Segment ${segment.name} has no video samples")
                val audioBounds = if (audioTarget != null) trackBounds(segment, "audio/") else null
                val firstPts = listOfNotNull(videoBounds.first, audioBounds?.first).minOrNull() ?: videoBounds.first
                val lastPts = listOfNotNull(videoBounds.second, audioBounds?.second).maxOrNull() ?: videoBounds.second
                appendTrack(segment, "video/", muxer, videoTarget, firstPts, timelineUs)
                if (audioTarget != null && audioBounds != null) {
                    appendTrack(segment, "audio/", muxer, audioTarget, firstPts, timelineUs)
                }
                timelineUs += (lastPts - firstPts + 1L).coerceAtLeast(1L)
            }

            muxer.stop()
            stopped = true
        } catch (t: Throwable) {
            output.delete()
            throw IllegalStateException("Segment concatenation failed: ${t.message}", t)
        } finally {
            if (started && !stopped) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        check(isPlayable(output)) {
            output.delete()
            "Concatenation produced an invalid MP4"
        }
        return output
    }

    fun isPlayable(file: File): Boolean {
        if (!file.exists() || file.length() <= 1024L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = findTrack(extractor, "video/")
            if (videoTrack < 0) return false
            extractor.selectTrack(videoTrack)
            val probe = ByteBuffer.allocate(4 * 1024 * 1024)
            extractor.readSampleData(probe, 0) > 0 && extractor.sampleTime >= 0L
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun hasTrack(file: File, prefix: String): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            findTrack(extractor, prefix) >= 0
        } catch (_: Throwable) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun copyValidated(source: File, output: File): File {
        if (output.exists()) output.delete()
        source.inputStream().use { input -> output.outputStream().use { input.copyTo(it) } }
        check(isPlayable(output)) {
            output.delete()
            "Copied MP4 failed playback validation"
        }
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
        val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var firstPts = -1L
        var samples = 0L
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            if (pts < 0) break
            if (firstPts < 0) firstPts = pts
            info.set(
                0,
                size,
                (pts - firstPts).coerceAtLeast(0L) + offsetUs,
                extractorFlagsToCodecFlags(extractor.sampleFlags)
            )
            muxer.writeSampleData(targetTrack, buffer, info)
            samples++
            if (!extractor.advance()) break
        }
        check(samples > 0L) { "Track contained no media samples" }
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
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                val pts = (sampleTime - segmentFirstPts).coerceAtLeast(0L) + timelineUs
                info.set(0, size, pts, extractorFlagsToCodecFlags(extractor.sampleFlags))
                muxer.writeSampleData(targetTrack, buffer, info)
                samples++
                if (!extractor.advance()) break
            }
            check(samples > 0L) { "Segment track $prefix contained no samples" }
        } finally {
            extractor.release()
        }
    }

    private fun extractorFlagsToCodecFlags(sampleFlags: Int): Int {
        var codecFlags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }
}
