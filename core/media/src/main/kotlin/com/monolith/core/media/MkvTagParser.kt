package com.monolith.core.media

import android.content.Context
import android.net.Uri
import com.monolith.core.model.MediaMetadataInfo
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * Zero-copy EBML / Matroska / WebM tag parser using [FileChannel] for local files and
 * `content://` [android.os.ParcelFileDescriptor] streams.
 */
object MkvTagParser {

    private const val ID_EBML_HEADER = 0x1a45dfa3
    private const val ID_SEGMENT = 0x18538067
    private const val ID_INFO = 0x1549a966
    private const val ID_SEGMENT_TITLE = 0x7ba9
    private const val ID_TAGS = 0x1254c367
    private const val ID_TAG = 0x7373
    private const val ID_SIMPLE_TAG = 0x67c8
    private const val ID_TAG_NAME = 0x45a3
    private const val ID_TAG_STRING = 0x4487

    private const val MAX_ROOT_ELEMENTS = 64
    private const val MAX_SEGMENT_ELEMENTS = 5000
    private const val MAX_INFO_OR_TAGS_ELEMENTS = 1000
    private const val MAX_SIMPLE_TAG_ELEMENTS = 100
    private const val MAX_STRING_BYTES = 64 * 1024

    fun parse(context: Context, uriString: String): MediaMetadataInfo? {
        if (uriString.isBlank()) return null
        return runCatching {
            when {
                uriString.startsWith("content://") -> {
                    context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                            parseChannel(channel)
                        }
                    }
                }
                uriString.startsWith("file://") -> {
                    val path = Uri.parse(uriString).path ?: return null
                    parse(File(path))
                }
                uriString.startsWith("/") -> parse(File(uriString))
                else -> null
            }
        }.getOrNull()
    }

    fun parse(file: File): MediaMetadataInfo? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        return runCatching {
            FileInputStream(file).channel.use { channel ->
                parseChannel(channel)
            }
        }.getOrNull()
    }

    private fun parseChannel(channel: FileChannel): MediaMetadataInfo? {
        val fileSize = channel.size()
        if (fileSize < 4L) return null

        val scratch = ByteBuffer.allocate(8)
        var hasEbmlHeader = false
        var segmentTitle = ""
        val tags = HashMap<String, String>()

        var rootCount = 0
        while (channel.position() < fileSize && rootCount < MAX_ROOT_ELEMENTS) {
            rootCount++
            val id = readVintId(channel, scratch) ?: break
            val size = readVintSize(channel, scratch) ?: break
            val payloadStart = channel.position()
            val payloadEnd = resolvePayloadEnd(payloadStart, size, fileSize) ?: break

            when (id) {
                ID_EBML_HEADER -> {
                    hasEbmlHeader = true
                    channel.position(payloadEnd)
                }
                ID_SEGMENT -> {
                    if (!hasEbmlHeader) return null
                    segmentTitle = parseSegment(channel, scratch, payloadEnd, tags)
                    break
                }
                else -> {
                    if (!hasEbmlHeader) return null
                    channel.position(payloadEnd)
                }
            }
        }

        val title = tags["TITLE"].orEmpty().ifBlank { segmentTitle }
        val artist = tags["ARTIST"]
            ?: tags["ALBUM_ARTIST"]
            ?: tags["AUTHOR"]
            ?: ""
        val album = tags["ALBUM"].orEmpty()
        val genre = tags["GENRE"].orEmpty()

        if (title.isBlank() && artist.isBlank() && album.isBlank() && genre.isBlank()) {
            return null
        }

        return MediaMetadataInfo(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
        )
    }

    private fun parseSegment(
        channel: FileChannel,
        scratch: ByteBuffer,
        segmentEnd: Long,
        tags: MutableMap<String, String>,
    ): String {
        var segmentTitle = ""
        var count = 0
        var parsedInfo = false
        var parsedTags = false

        while (channel.position() < segmentEnd && count < MAX_SEGMENT_ELEMENTS) {
            count++
            val id = readVintId(channel, scratch) ?: break
            val size = readVintSize(channel, scratch) ?: break
            val payloadStart = channel.position()
            val payloadEnd = resolvePayloadEnd(payloadStart, size, segmentEnd) ?: break

            when (id) {
                ID_INFO -> {
                    val infoTitle = parseInfo(channel, scratch, payloadEnd)
                    if (infoTitle.isNotBlank()) {
                        segmentTitle = infoTitle
                    }
                    parsedInfo = true
                }
                ID_TAGS -> {
                    parseTags(channel, scratch, payloadEnd, tags)
                    parsedTags = true
                }
            }

            channel.position(payloadEnd)
            if (parsedInfo && parsedTags && tags.containsKey("TITLE")) {
                break
            }
        }
        return segmentTitle
    }

    private fun parseInfo(
        channel: FileChannel,
        scratch: ByteBuffer,
        infoEnd: Long,
    ): String {
        var count = 0
        while (channel.position() < infoEnd && count < MAX_INFO_OR_TAGS_ELEMENTS) {
            count++
            val id = readVintId(channel, scratch) ?: break
            val size = readVintSize(channel, scratch) ?: break
            val payloadStart = channel.position()
            val payloadEnd = resolvePayloadEnd(payloadStart, size, infoEnd) ?: break

            if (id == ID_SEGMENT_TITLE && size in 1..MAX_STRING_BYTES) {
                val title = readUtf8String(channel, size.toInt())
                if (title.isNotBlank()) return title
            }

            channel.position(payloadEnd)
        }
        return ""
    }

    private fun parseTags(
        channel: FileChannel,
        scratch: ByteBuffer,
        tagsEnd: Long,
        outTags: MutableMap<String, String>,
    ) {
        var count = 0
        while (channel.position() < tagsEnd && count < MAX_INFO_OR_TAGS_ELEMENTS) {
            count++
            val id = readVintId(channel, scratch) ?: break
            val size = readVintSize(channel, scratch) ?: break
            val payloadStart = channel.position()
            val payloadEnd = resolvePayloadEnd(payloadStart, size, tagsEnd) ?: break

            if (id == ID_TAG) {
                parseTag(channel, scratch, payloadEnd, outTags)
            }

            channel.position(payloadEnd)
        }
    }

    private fun parseTag(
        channel: FileChannel,
        scratch: ByteBuffer,
        tagEnd: Long,
        outTags: MutableMap<String, String>,
    ) {
        var count = 0
        while (channel.position() < tagEnd && count < MAX_INFO_OR_TAGS_ELEMENTS) {
            count++
            val id = readVintId(channel, scratch) ?: break
            val size = readVintSize(channel, scratch) ?: break
            val payloadStart = channel.position()
            val payloadEnd = resolvePayloadEnd(payloadStart, size, tagEnd) ?: break

            if (id == ID_SIMPLE_TAG) {
                parseSimpleTag(channel, scratch, payloadEnd, outTags)
            }

            channel.position(payloadEnd)
        }
    }

    private fun parseSimpleTag(
        channel: FileChannel,
        scratch: ByteBuffer,
        simpleTagEnd: Long,
        outTags: MutableMap<String, String>,
    ) {
        var name = ""
        var value = ""
        var count = 0

        while (channel.position() < simpleTagEnd && count < MAX_SIMPLE_TAG_ELEMENTS) {
            count++
            val id = readVintId(channel, scratch) ?: break
            val size = readVintSize(channel, scratch) ?: break
            val payloadStart = channel.position()
            val payloadEnd = resolvePayloadEnd(payloadStart, size, simpleTagEnd) ?: break

            when (id) {
                ID_TAG_NAME -> if (size in 1..MAX_STRING_BYTES) {
                    name = readUtf8String(channel, size.toInt()).trim().uppercase()
                }
                ID_TAG_STRING -> if (size in 1..MAX_STRING_BYTES) {
                    value = readUtf8String(channel, size.toInt()).trim()
                }
            }

            channel.position(payloadEnd)
        }

        if (name.isNotEmpty() && value.isNotEmpty() && !outTags.containsKey(name)) {
            outTags[name] = value
        }
    }

    private fun resolvePayloadEnd(payloadStart: Long, size: Long, containerEnd: Long): Long? {
        if (size < 0L) return containerEnd
        val end = payloadStart + size
        return if (end < payloadStart || end > containerEnd) containerEnd else end
    }

    private fun readVintId(channel: FileChannel, scratch: ByteBuffer): Int? {
        val b0 = readUnsignedByte(channel, scratch) ?: return null
        if (b0 == 0) return null
        val len = Integer.numberOfLeadingZeros(b0) - 24 + 1
        if (len !in 1..4) return null

        var value = b0
        for (i in 1 until len) {
            val next = readUnsignedByte(channel, scratch) ?: return null
            value = (value shl 8) or next
        }
        return value
    }

    private fun readVintSize(channel: FileChannel, scratch: ByteBuffer): Long? {
        val b0 = readUnsignedByte(channel, scratch) ?: return null
        if (b0 == 0) return null
        val len = Integer.numberOfLeadingZeros(b0) - 24 + 1
        if (len !in 1..8) return null

        val mask = (1L shl (8 - len)) - 1L
        var value = b0.toLong() and mask
        for (i in 1 until len) {
            val next = readUnsignedByte(channel, scratch) ?: return null
            value = (value shl 8) or next.toLong()
        }

        val allOnes = if (len == 8) -1L else (1L shl (7 * len)) - 1L
        return if (value == allOnes) -1L else value
    }

    private fun readUnsignedByte(channel: FileChannel, scratch: ByteBuffer): Int? {
        scratch.clear()
        scratch.limit(1)
        val read = channel.read(scratch)
        if (read != 1) return null
        return scratch.get(0).toInt() and 0xFF
    }

    private fun readUtf8String(channel: FileChannel, length: Int): String {
        val buffer = ByteBuffer.allocate(length)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read <= 0) break
        }
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000')
    }
}
