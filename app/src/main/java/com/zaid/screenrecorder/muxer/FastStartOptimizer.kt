package com.zaid.screenrecorder.muxer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Moves the MP4 `moov` atom in front of `mdat` and fixes chunk offsets.
 *
 * Android's MediaMuxer normally writes `moov` at EOF. That is valid MP4, but a few vendor
 * gallery/player stacks are less tolerant of large VFR captures with a tail `moov`. Keeping the
 * index up front also makes incomplete/slowly indexed files fail less mysteriously.
 */
internal object FastStartOptimizer {
    private data class Box(val offset: Long, val size: Long, val type: String, val headerSize: Int)

    private val containerTypes = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex"
    )

    fun optimizeInPlace(file: File): Boolean {
        if (!file.exists() || file.length() < 32L) return false

        val top = RandomAccessFile(file, "r").use { input -> scanTopLevel(input) }
        val mdat = top.firstOrNull { it.type == "mdat" } ?: return false
        val moov = top.firstOrNull { it.type == "moov" } ?: return false

        // Already fast-start compatible.
        if (moov.offset < mdat.offset) return true
        if (moov.size > Int.MAX_VALUE) return false

        val moovBytes = ByteArray(moov.size.toInt())
        RandomAccessFile(file, "r").use { input ->
            input.seek(moov.offset)
            input.readFully(moovBytes)
        }

        if (!patchChunkOffsets(moovBytes, 0, moovBytes.size, moov.size)) return false

        val tmp = File(file.parentFile, ".${file.name}.faststart-${System.nanoTime()}")
        return try {
            RandomAccessFile(file, "r").use { input ->
                RandomAccessFile(tmp, "rw").use { output ->
                    output.setLength(0L)
                    copyRange(input, output, 0L, mdat.offset)
                    output.write(moovBytes)
                    copyRange(input, output, mdat.offset, moov.offset - mdat.offset)
                    val afterMoov = moov.offset + moov.size
                    if (afterMoov < input.length()) {
                        copyRange(input, output, afterMoov, input.length() - afterMoov)
                    }
                    output.fd.sync()
                }
            }

            if (tmp.length() != file.length()) {
                tmp.delete()
                return false
            }

            val backup = File(file.parentFile, ".${file.name}.pre-faststart")
            backup.delete()
            if (!file.renameTo(backup)) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                backup.renameTo(file)
                tmp.delete()
                return false
            }
            backup.delete()
            true
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    private fun scanTopLevel(input: RandomAccessFile): List<Box> {
        val result = mutableListOf<Box>()
        var offset = 0L
        val end = input.length()
        while (offset + 8L <= end) {
            val box = readBox(input, offset, end) ?: break
            result += box
            offset += box.size
        }
        return result
    }

    private fun readBox(input: RandomAccessFile, offset: Long, limit: Long): Box? {
        if (offset + 8L > limit) return null
        input.seek(offset)
        val size32 = input.readInt().toLong() and 0xffff_ffffL
        val typeBytes = ByteArray(4)
        input.readFully(typeBytes)
        val type = typeBytes.toString(Charsets.ISO_8859_1)
        var size = size32
        var header = 8
        if (size32 == 1L) {
            if (offset + 16L > limit) return null
            size = input.readLong()
            header = 16
        } else if (size32 == 0L) {
            size = limit - offset
        }
        if (size < header || offset + size > limit) return null
        return Box(offset, size, type, header)
    }

    private fun patchChunkOffsets(data: ByteArray, start: Int, end: Int, shift: Long): Boolean {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = uint32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            var size = size32
            if (size32 == 1L) {
                if (pos + 16 > end) return false
                size = int64(data, pos + 8)
                header = 16
            } else if (size32 == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || size > Int.MAX_VALUE || pos + size.toInt() > end) return false

            when (type) {
                "stco" -> {
                    val body = pos + header
                    if (body + 8 > pos + size) return false
                    val count = uint32(data, body + 4)
                    if (count > Int.MAX_VALUE) return false
                    var p = body + 8
                    repeat(count.toInt()) {
                        if (p + 4 > pos + size) return false
                        val old = uint32(data, p)
                        val updated = old + shift
                        if (updated > 0xffff_ffffL) return false
                        putUInt32(data, p, updated)
                        p += 4
                    }
                }
                "co64" -> {
                    val body = pos + header
                    if (body + 8 > pos + size) return false
                    val count = uint32(data, body + 4)
                    if (count > Int.MAX_VALUE) return false
                    var p = body + 8
                    repeat(count.toInt()) {
                        if (p + 8 > pos + size) return false
                        val old = int64(data, p)
                        if (old < 0L || Long.MAX_VALUE - old < shift) return false
                        putInt64(data, p, old + shift)
                        p += 8
                    }
                }
                in containerTypes -> {
                    if (!patchChunkOffsets(data, pos + header, pos + size.toInt(), shift)) return false
                }
            }
            pos += size.toInt()
        }
        return true
    }

    private fun copyRange(input: RandomAccessFile, output: RandomAccessFile, start: Long, length: Long) {
        input.seek(start)
        var remaining = length
        val buffer = ByteArray(1024 * 1024)
        while (remaining > 0L) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            check(read > 0) { "Unexpected EOF while relocating MP4 atoms" }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun uint32(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun int64(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun putUInt32(data: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt())
    }

    private fun putInt64(data: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(value)
    }
}
